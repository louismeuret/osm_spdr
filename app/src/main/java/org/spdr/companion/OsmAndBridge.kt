package org.spdr.companion

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.IBinder
import android.util.Log
import net.osmand.aidlapi.IOsmAndAidlCallback
import net.osmand.aidlapi.IOsmAndAidlInterface
import net.osmand.aidlapi.contextmenu.AContextMenuButton
import net.osmand.aidlapi.contextmenu.ContextMenuButtonsParams
import net.osmand.aidlapi.gpx.AGpxBitmap
import net.osmand.aidlapi.gpx.ImportGpxParams
import net.osmand.aidlapi.logcat.OnLogcatMessageParams
import net.osmand.aidlapi.map.ALatLon
import net.osmand.aidlapi.map.ALocation
import net.osmand.aidlapi.map.SetLocationParams
import net.osmand.aidlapi.map.SetMapLocationParams
import net.osmand.aidlapi.maplayer.AMapLayer
import net.osmand.aidlapi.maplayer.AddMapLayerParams
import net.osmand.aidlapi.maplayer.UpdateMapLayerParams
import net.osmand.aidlapi.maplayer.point.AMapPoint
import net.osmand.aidlapi.maplayer.point.RemoveMapPointParams
import net.osmand.aidlapi.mapwidget.AMapWidget
import net.osmand.aidlapi.mapwidget.AddMapWidgetParams
import net.osmand.aidlapi.mapwidget.UpdateMapWidgetParams
import net.osmand.aidlapi.navigation.ADirectionInfo
import net.osmand.aidlapi.navigation.OnVoiceNavigationParams
import net.osmand.aidlapi.search.SearchResult
import org.spdr.companion.core.graph.Graph
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.hypot

/**
 * Everything that talks to OsmAnd, through its public AIDL API (v2):
 *  - pushes the estimate as OsmAnd's own location (`setLocation`), so OsmAnd's arrow, track
 *    recording and navigation follow the sPDR position;
 *  - draws alternative hypotheses and tappable reset points as a map layer, with context-menu
 *    buttons ("I'm here", "That's me") that come back here as manual corrections;
 *  - shows a confidence widget and can import the graph as a GPX track for display.
 * All AIDL calls run on one background thread; snapshots are coalesced.
 */
class OsmAndBridge(private val context: Context, private val engine: Engine) {
    @Volatile var status: String = "OsmAnd: not connected"
        private set
    @Volatile var connectedPackage: String? = null
        private set

    private val statusListeners = CopyOnWriteArrayList<() -> Unit>()
    fun addStatusListener(l: () -> Unit) = statusListeners.add(l)
    fun removeStatusListener(l: () -> Unit) = statusListeners.remove(l)

    @Volatile private var api: IOsmAndAidlInterface? = null
    private var bound = false
    private val exec = Executors.newSingleThreadExecutor { Thread(it, "osmand-bridge") }
    private val pending = AtomicReference<Snapshot?>()

    // touched only on the executor thread
    private val nodePoints = HashMap<String, DoubleArray>()
    private val hypPoints = HashMap<String, DoubleArray>()
    private var nodesGraph: Graph? = null
    private var nodesCenter: DoubleArray? = null
    private var widgetAdded = false
    private var lastWidgetText = ""
    private var layersReady = false
    private var locationRefused = false

    private val listener: (Snapshot) -> Unit = { update(it) }

    fun installedPackages(): List<String> = PACKAGES.filter { p ->
        runCatching { context.packageManager.getPackageInfo(p, 0); true }.getOrDefault(false)
    }

    fun connect() {
        if (bound) return
        val pref = engine.prefs.osmandPackage
        val candidates = if (pref.isNotEmpty()) listOf(pref) else installedPackages()
        for (p in candidates) {
            val intent = Intent(ACTION).setPackage(p)
            if (runCatching { context.bindService(intent, conn, Context.BIND_AUTO_CREATE) }.getOrDefault(false)) {
                bound = true
                connectedPackage = p
                setStatus("OsmAnd: connecting to $p…")
                engine.addListener(listener)
                return
            }
        }
        setStatus(if (candidates.isEmpty()) "OsmAnd: not installed" else "OsmAnd: bind refused")
    }

    fun disconnect() {
        engine.removeListener(listener)
        if (bound) runCatching { context.unbindService(conn) }
        bound = false
        api = null
        setStatus("OsmAnd: not connected")
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            api = IOsmAndAidlInterface.Stub.asInterface(service)
            exec.execute { safely("setup") { setup() } }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            api = null
            setStatus("OsmAnd: disconnected (will reconnect)")
        }
    }

    private fun setStatus(s: String) {
        status = s
        statusListeners.forEach { it() }
    }

    private inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "$what failed", e)
            setStatus("OsmAnd: $what failed (${e.javaClass.simpleName})")
        }
    }

    // ---- setup -----------------------------------------------------------------------------

    private fun setup() {
        val a = api ?: return
        nodePoints.clear(); hypPoints.clear(); nodesGraph = null; nodesCenter = null; widgetAdded = false
        layersReady = false; locationRefused = false
        val nodes = AMapLayer(LAYER_NODES, "sPDR reset points", 5.6f, emptyList()).apply {
            setCirclePointZoomBounds(0, 15)
            setSmallPointZoomBounds(16, 22)
            setBigPointZoomBounds(23, 23)
        }
        if (!a.addMapLayer(AddMapLayerParams(nodes))) {
            // OsmAnd registers unknown apps as disabled; the user has to switch us on once
            setStatus("OsmAnd refused: enable \"sPDR for OsmAnd\" in OsmAnd › Settings › Connected apps")
            return
        }
        a.addMapLayer(AddMapLayerParams(AMapLayer(LAYER_HYP, "sPDR hypotheses", 5.8f, emptyList())))
        // pointsIds = a dummy id so the buttons attach by layer only, not to every AIDL point
        a.addContextMenuButtons(ContextMenuButtonsParams(
            AContextMenuButton(BTN_HERE, "I'm here", "", "ic_action_location_color", "", false, true),
            AContextMenuButton(BTN_NEAR, "I'm near here", "±10 m", "", "", false, true),
            "spdr_nodes_buttons", context.packageName, LAYER_NODES, -1, listOf("-")), callback)
        a.addContextMenuButtons(ContextMenuButtonsParams(
            AContextMenuButton(BTN_SELECT, "That's me", "", "", "", false, true),
            AContextMenuButton(BTN_HERE, "I'm exactly here", "", "", "", false, true),
            "spdr_hyp_buttons", context.packageName, LAYER_HYP, -1, listOf("-")), callback)
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
        widgetAdded = a.addMapWidget(AddMapWidgetParams(AMapWidget(WIDGET_ID, "ic_action_gps_info", "sPDR",
            "widget_gps_info_day", "widget_gps_info_night", "—", "sPDR", 50, open)))
        setStatus("OsmAnd: connected ($connectedPackage)")
        layersReady = true
        pending.compareAndSet(null, engine.snapshot())
        drain()
    }

    // ---- updates ---------------------------------------------------------------------------

    fun update(s: Snapshot) {
        if (pending.getAndSet(s) == null) exec.execute { drain() }
    }

    private fun drain() {
        val s = pending.getAndSet(null) ?: return
        if (api == null) return
        if (!layersReady) { safely("setup") { setup() }; if (!layersReady) return }
        safely("update") { push(s) }
    }

    private fun push(s: Snapshot) {
        val a = api ?: return
        val g = s.graph ?: return
        val est = s.estimate
        val best = est?.best

        if (best != null && s.sensorsOn) {
            val ll = s.toLatLon(best.x, best.y)!!
            val acc = (3 + 1.5 * best.spread + 30 * (1 - best.weight)).toFloat()
            val bearing = ((90 - Math.toDegrees(best.heading)) % 360 + 360) % 360
            val ok = a.setLocation(SetLocationParams(ALocation(ll.lat, ll.lon, System.currentTimeMillis(),
                false, 0.0, false, 0f, true, bearing.toFloat(), true, acc, false, 0f), HOLD_GPS_MS))
            if (ok == locationRefused) {
                locationRefused = !ok
                setStatus(if (ok) "OsmAnd: connected, position pushed" else
                    "OsmAnd refused setLocation: in OsmAnd › Settings › Connected apps › sPDR, allow \"System\"")
            }
        }

        // hypotheses layer: alternatives always, the best one only when OsmAnd's arrow isn't showing it
        val hyps = ArrayList<AMapPoint>()
        val hypXY = HashMap<String, DoubleArray>()
        est?.hypotheses?.forEachIndexed { i, h ->
            if (i == 0 && s.sensorsOn) return@forEachIndexed
            if (i > 0 && h.weight < 0.03) return@forEachIndexed
            val id = "h$i"
            val ll = s.toLatLon(h.x, h.y)!!
            val pct = (h.weight * 100).toInt()
            val label = if (i == 0) "sPDR $pct%" else "Alt. $pct%"
            hyps.add(AMapPoint(id, label, "sPDR hypothesis: $pct% of the probability, ±${h.spread.toInt()} m",
                "sPDR", LAYER_HYP, if (i == 0) Color.rgb(30, 136, 229) else Color.rgb(255, 143, 0),
                ALatLon(ll.lat, ll.lon), listOf("Tap \"That's me\" if you are on this branch."), emptyMap()))
            hypXY[id] = doubleArrayOf(h.x, h.y)
        }
        sync(a, LAYER_HYP, hypPoints, hyps, hypXY)

        val c = best?.let { doubleArrayOf(it.x, it.y) } ?: nodesCenter ?: g.bounds.let {
            doubleArrayOf((it[0] + it[2]) / 2, (it[1] + it[3]) / 2)
        }
        val nc = nodesCenter
        if (nodesGraph !== g || nc == null || hypot(nc[0] - c[0], nc[1] - c[1]) > NODES_REFRESH_M) {
            refreshNodes(a, s, g, c)
        }

        if (widgetAdded) {
            val text = if (best == null) "—" else "${(best.weight * 100).toInt()}%"
            val desc = if (best == null) "sPDR" else "±${(best.spread + 3).toInt()} m"
            if (text + desc != lastWidgetText) {
                lastWidgetText = text + desc
                val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
                a.updateMapWidget(UpdateMapWidgetParams(AMapWidget(WIDGET_ID, "ic_action_gps_info", "sPDR",
                    "widget_gps_info_day", "widget_gps_info_night", text, desc, 50, open)))
            }
        }
    }

    /** Reset points around c: graph nodes, plus marks every 10 m along nearby galleries. */
    private fun refreshNodes(a: IOsmAndAidlInterface, s: Snapshot, g: Graph, c: DoubleArray) {
        nodesGraph = g
        nodesCenter = c
        val pts = ArrayList<AMapPoint>()
        val xy = HashMap<String, DoubleArray>()
        val near = g.nodes.map { it to hypot(it.x - c[0], it.y - c[1]) }
            .filter { it.second < NODES_RADIUS_M }.sortedBy { it.second }.take(MAX_NODES)
        for ((n, _) in near) {
            val deg = n.incident.size
            val (label, color) = when {
                n.name != null -> n.name to Color.rgb(216, 27, 96)
                deg == 1 -> "Dead end" to Color.rgb(120, 144, 156)
                deg == 2 -> "Bend" to Color.rgb(120, 144, 156)
                else -> "Junction ($deg ways)" to Color.rgb(253, 216, 53)
            }
            val ll = s.toLatLon(n.x, n.y)!!
            val id = "n${n.idx}"
            pts.add(AMapPoint(id, if (n.name != null) label else "", label, "sPDR", LAYER_NODES, color,
                ALatLon(ll.lat, ll.lon), listOf("Tap \"I'm here\" to correct the sPDR position."), emptyMap()))
            xy[id] = doubleArrayOf(n.x, n.y)
        }
        var ticks = 0
        loop@ for (ei in g.edgesNear(c[0], c[1], TICKS_RADIUS_M)) {
            val e = g.edges[ei]
            var d = TICK_SPACING_M
            while (d < e.length - TICK_SPACING_M / 2) {
                val x = e.x(d); val y = e.y(d)
                if (hypot(x - c[0], y - c[1]) < TICKS_RADIUS_M) {
                    val ll = s.toLatLon(x, y)!!
                    val id = "t${ei}_${d.toInt()}"
                    pts.add(AMapPoint(id, "", "Gallery point", "sPDR", LAYER_NODES, Color.rgb(79, 195, 247),
                        ALatLon(ll.lat, ll.lon), emptyList(), emptyMap()))
                    xy[id] = doubleArrayOf(x, y)
                    if (++ticks >= MAX_TICKS) break@loop
                }
                d += TICK_SPACING_M
            }
        }
        sync(a, LAYER_NODES, nodePoints, pts, xy)
    }

    /** Makes the OsmAnd layer contain exactly `points` (updateMapLayer only adds/replaces). */
    private fun sync(a: IOsmAndAidlInterface, layer: String, current: HashMap<String, DoubleArray>,
                     points: List<AMapPoint>, xy: Map<String, DoubleArray>) {
        val gone = current.keys - xy.keys
        for (id in gone) a.removeMapPoint(RemoveMapPointParams(layer, id))
        if (points.isNotEmpty()) a.updateMapLayer(UpdateMapLayerParams(AMapLayer(layer, layer, 5.6f, points)))
        current.clear()
        current.putAll(xy)
    }

    // ---- user actions ----------------------------------------------------------------------

    /** Imports the graph into OsmAnd as a visible GPX track. */
    fun showGraph(g: Graph, done: (Boolean) -> Unit) = exec.execute {
        var ok = false
        safely("GPX import") {
            ok = api?.importGpx(ImportGpxParams(GraphStore.toGpx(g), "spdr_graph.gpx", "#FF8F00", true)) == true
        }
        done(ok)
    }

    /** Centers OsmAnd's map on a point and brings OsmAnd to the front. */
    fun showOnMap(lat: Double, lon: Double) {
        exec.execute { safely("center map") { api?.setMapLocation(SetMapLocationParams(lat, lon, 18, Float.NaN, true)) } }
        val pkg = connectedPackage ?: installedPackages().firstOrNull() ?: return
        context.packageManager.getLaunchIntentForPackage(pkg)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            context.startActivity(it)
        }
    }

    private val callback = object : IOsmAndAidlCallback.Stub() {
        override fun onContextMenuButtonClicked(buttonId: Int, pointId: String?, layerId: String?) {
            val id = pointId ?: return
            exec.execute {
                val p = (if (layerId == LAYER_HYP) hypPoints else nodePoints)[id] ?: return@execute
                when (buttonId) {
                    BTN_HERE -> engine.fixPosition(p[0], p[1], 3.0, "osmand:$id")
                    BTN_NEAR -> engine.fixPosition(p[0], p[1], 10.0, "osmand:$id")
                    BTN_SELECT -> engine.selectHypothesis(p[0], p[1])
                }
            }
        }

        override fun onSearchComplete(resultSet: MutableList<SearchResult>?) {}
        override fun onUpdate() {}
        override fun onAppInitialized() {}
        override fun onGpxBitmapCreated(bitmap: AGpxBitmap?) {}
        override fun updateNavigationInfo(directionInfo: ADirectionInfo?) {}
        override fun onVoiceRouterNotify(params: OnVoiceNavigationParams?) {}
        override fun onKeyEvent(params: android.view.KeyEvent?) {}
        override fun onLogcatMessage(params: OnLogcatMessageParams?) {}
    }

    companion object {
        private const val TAG = "OsmAndBridge"
        const val ACTION = "net.osmand.aidl.OsmandAidlServiceV2"
        val PACKAGES = listOf("net.osmand.plus", "net.osmand", "net.osmand.dev")
        private const val LAYER_NODES = "spdr_nodes"
        private const val LAYER_HYP = "spdr_hyp"
        private const val WIDGET_ID = "spdr_widget"
        private const val BTN_HERE = 1
        private const val BTN_NEAR = 2
        private const val BTN_SELECT = 3
        /** OsmAnd ignores its own GPS this long after each pushed position. */
        private const val HOLD_GPS_MS = 30_000L
        private const val NODES_REFRESH_M = 25.0
        private const val NODES_RADIUS_M = 300.0
        private const val MAX_NODES = 150
        private const val TICKS_RADIUS_M = 70.0
        private const val TICK_SPACING_M = 10.0
        private const val MAX_TICKS = 150
    }
}
