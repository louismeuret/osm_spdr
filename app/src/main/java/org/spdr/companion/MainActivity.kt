package org.spdr.companion

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import org.spdr.companion.core.bearingDeg
import org.spdr.companion.core.cardinal
import org.spdr.companion.core.pdr.Mounting
import org.spdr.companion.core.wrap
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var app: SpdrApp
    private lateinit var status: TextView
    private lateinit var map: MapCanvasView
    private lateinit var btnStart: Button
    private val ui = Handler(Looper.getMainLooper())
    private var last: Snapshot? = null
    private var uiPending = false
    private var calibDialog: AlertDialog? = null
    private var lastFix: Location? = null

    private val engineListener: (Snapshot) -> Unit = { s ->
        last = s
        if (!uiPending) { uiPending = true; ui.postDelayed({ uiPending = false; render() }, 150) }
    }
    private val bridgeListener: () -> Unit = { ui.post { render() } }

    /**
     * Steps are the only thing that pushes a snapshot, so between them nothing on screen moved —
     * which froze the compass exactly when you would use it, standing still and turning, and left
     * a stale needle on screen after the session stopped. So while the activity is in front, pull
     * a fresh snapshot a few times a second. Nothing runs when the screen is off, since the
     * activity is stopped by then and the service does its own throttled notification.
     */
    private val poll = object : Runnable {
        override fun run() {
            last = app.engine.snapshot()
            render()
            ui.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as SpdrApp
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        map = findViewById(R.id.map)
        btnStart = findViewById(R.id.btnStart)

        btnStart.setOnClickListener { toggleTracking() }
        findViewById<Button>(R.id.btnGps).setOnClickListener { gpsFix() }
        findViewById<Button>(R.id.btnOsmAnd).setOnClickListener { openOsmAnd() }
        findViewById<Button>(R.id.btnBack).setOnClickListener { app.engine.nudge(-5.0) }
        findViewById<Button>(R.id.btnFwd).setOnClickListener { app.engine.nudge(5.0) }
        findViewById<Button>(R.id.btnUturn).setOnClickListener { app.engine.uTurn() }
        findViewById<Button>(R.id.btnReject).setOnClickListener { app.engine.rejectBest() }
        findViewById<Button>(R.id.btnGraph).setOnClickListener { graphMenu(it) }
        findViewById<Button>(R.id.btnCalib).setOnClickListener { calibrate() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { settings() }
        findViewById<Button>(R.id.btnMark).setOnClickListener { mark() }

        map.onLongPressAt = { x, y -> confirmPositionAt(x, y) }
        map.onHypothesisTapped = { h ->
            AlertDialog.Builder(this)
                .setMessage("Switch to this hypothesis (${(h.weight * 100).toInt()}%)?")
                .setPositiveButton("That's me") { _, _ -> app.engine.selectHypothesis(h.x, h.y) }
                .setNegativeButton("Cancel", null).show()
        }
        requestPermissionsIfNeeded()
        app.bridge.connect()
    }

    override fun onStart() {
        super.onStart()
        app.engine.addListener(engineListener)
        app.bridge.addStatusListener(bridgeListener)
        ui.postDelayed(poll, POLL_MS)
    }

    override fun onStop() {
        ui.removeCallbacks(poll)
        app.engine.removeListener(engineListener)
        app.bridge.removeStatusListener(bridgeListener)
        super.onStop()
    }

    // Volume up = MARK (works with gloves through a pouch)
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) { mark(); return true }
        return super.onKeyDown(keyCode, event)
    }

    private fun render() {
        val s = last ?: return
        map.update(s)
        btnStart.text = if (s.sensorsOn) "■ Stop" else "▶ Start"
        val g = s.graph
        val sb = StringBuilder()
        sb.append(if (g == null) "Graph: none" else "Graph: ${g.nodes.size} nodes, ${g.edges.size} edges, ${(g.totalLength / 1000).fmt(1)} km")
        sb.append('\n').append(app.bridge.status)
        val e = s.estimate
        val b = e?.best
        sb.append('\n')
        if (!s.sensorsOn) sb.append("Sensors off. ")
        sb.append("Steps ${s.stepsDetected}")
        if (e != null) sb.append(" · walked ${e.distance.fmt(0)} m")
        sb.append(if (s.compassOk) " · compass (${s.compassInfo})" else " · no compass (${s.compassInfo})")
        if (app.prefs.mounting.fromGait) sb.append(if (s.walkDirOk) " · gait dir" else " · no gait dir")
        sb.append(if (s.baroOk) " · baro" else "")
        if (s.gpsFixes > 0) {
            sb.append(" · GPS ${s.gpsFixes} fix")
            if (!s.gpsError.isNaN()) sb.append(", off by ${s.gpsError.fmt(1)} m")
        }
        sb.append('\n')
        sb.append('\n').append(facingLine(s))
        if (b == null) {
            sb.append("No position: long-press the map, use GPS fix, or \"I'm here\" in OsmAnd.")
        } else {
            sb.append("Conf ${(b.weight * 100).toInt()}% ±${(b.spread + 3).fmt(0)} m · ${e.hypotheses.size} hyp · k ${e.kMean.fmt(3)}±${e.kStd.fmt(3)}")
            if (e.holding) sb.append("\nRE-LOCALISING: a likelier position exists too far away to walk to; holding this one.")
            sb.append("\nθ ±${Math.toDegrees(e.thetaStd).fmt(0)}° · gyro bias ${Math.toDegrees(e.biasMean).fmt(3)}°/s")
            s.lastResetError?.let { sb.append(" · last fix error ${it.fmt(1)} m") }
        }
        if (s.calibrating) sb.append("\nCALIBRATING: ${s.calibrationSteps} steps")
        status.text = sb.toString()
        calibDialog?.setMessage(calibMessage(s))
    }

    /**
     * The direction of travel the system computes, as a bearing. Shows the compass-only value
     * next to it once the filter has its own: the two disagreeing is worth seeing, since that is
     * the magnetic error the map constraint is having to absorb.
     */
    private fun facingLine(s: Snapshot): String {
        val f = s.facing
        val sb = StringBuilder("Phone ")
        sb.append(if (f.phone.isNaN()) "— (no compass)" else "${bearingDeg(f.phone).fmt(0)}° ${cardinal(f.phone)}")
        sb.append(" · travel ")
        if (f.travel.isNaN()) {
            sb.append(if (s.stepsDetected == 0) "— (not walking yet)" else "— (standing)")
            return sb.toString()
        }
        sb.append("${bearingDeg(f.travel).fmt(0)}° ${cardinal(f.travel)}")
        if (f.fused) {
            sb.append(" ±${Math.toDegrees(f.travelStd).fmt(0)}°")
            if (!f.travelFromCompass.isNaN()) {
                val d = Math.toDegrees(wrap(f.travelFromCompass - f.travel))
                sb.append(" (compass ${if (d >= 0) "+" else ""}${d.fmt(0)}°)")
            }
        } else {
            sb.append(" · compass only, not yet checked against the galleries")
        }
        return sb.toString()
    }

    // ---- tracking ----------------------------------------------------------------------------

    private fun toggleTracking() {
        if (last?.sensorsOn == true) {
            SpdrService.stop(this)
            return
        }
        if (!hasAnyLocationPermission()) {
            requestPermissionsIfNeeded()
            toast("Location permission is needed for the foreground service and GPS fixes")
            return
        }
        if (app.engine.graph == null) toast("No graph loaded yet: steps are counted but the position can't be tracked")
        SpdrService.start(this)
    }

    private fun confirmPositionAt(x: Double, y: Double) {
        val g = app.engine.graph ?: return
        val p = g.nearest(x, y, 100.0)
        if (p == null) { toast("No gallery within 100 m of that point"); return }
        val e = g.edges[p.edge]
        val px = e.x(p.s); val py = e.y(p.s)
        val started = last?.initialized == true
        AlertDialog.Builder(this)
            .setTitle(if (started) "Correct position" else "Start tracking here")
            .setItems(arrayOf("I'm here (±3 m)", "I'm near here (±10 m)", "Somewhere around (±30 m)")) { _, which ->
                val sigma = when (which) { 0 -> 3.0; 1 -> 10.0; else -> 30.0 }
                app.engine.fixPosition(px, py, sigma, "map")
            }
            .setNegativeButton("Cancel", null).show()
    }

    /**
     * Collects fixes from every enabled provider. Raw GPS alone can take minutes (or never fix
     * indoors); the fused / network providers are what Google Maps shows within seconds.
     */
    private fun gpsFix() {
        val lm = getSystemService(LocationManager::class.java)
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            requestPermissionsIfNeeded()
            toast("Location permission denied: allow it in Android Settings › Apps › sPDR › Permissions")
            return
        }
        if (!lm.isLocationEnabled) return toast("Location is switched off in Android settings")

        val providers = buildList {
            if (fine) add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
        }.filter { p -> runCatching { lm.isProviderEnabled(p) }.getOrDefault(false) }

        var best: Location? = null
        val latest = HashMap<String, Location>()
        val text = TextView(this).apply { setPadding(48, 24, 48, 0) }
        fun ageS(l: Location) = (android.os.SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000_000L
        fun refresh() {
            val sb = StringBuilder()
            if (!fine) sb.append("Only APPROXIMATE location is allowed: set \"Precise\" in Android Settings › Apps › sPDR › Permissions › Location.\n\n")
            if (providers.isEmpty()) sb.append("No location provider enabled.\n")
            for (p in providers) {
                val l = latest[p]
                sb.append(p).append(": ").append(if (l == null) "waiting…" else
                    String.format(Locale.US, "±%.0f m, %d s ago", l.accuracy, ageS(l))).append('\n')
            }
            best?.let { sb.append(String.format(Locale.US, "\nBest: ±%.0f m (%s)\n%.6f, %.6f", it.accuracy, it.provider, it.latitude, it.longitude)) }
            text.text = sb.toString()
        }
        fun offer(l: Location) {
            if (ageS(l) > 120) return
            latest[l.provider ?: "?"] = l
            val b = best
            // prefer better accuracy, but let a fresh fix replace one that is much older
            if (b == null || l.accuracy <= b.accuracy || ageS(b) - ageS(l) > 30) best = l
            refresh()
        }
        for (p in providers) runCatching { lm.getLastKnownLocation(p) }.getOrNull()?.let { offer(it) }
        val listener = LocationListener { offer(it) }
        for (p in providers) runCatching { lm.requestLocationUpdates(p, 500L, 0f, listener, Looper.getMainLooper()) }
        refresh()
        val ticker = object : Runnable { override fun run() { refresh(); ui.postDelayed(this, 1000) } }
        ui.postDelayed(ticker, 1000)
        fun stop() { lm.removeUpdates(listener); ui.removeCallbacks(ticker) }

        AlertDialog.Builder(this)
            .setTitle("Position fix (do this before going underground)")
            .setView(text)
            .setPositiveButton("Use best fix") { _, _ ->
                stop()
                val l = best ?: return@setPositiveButton toast("No fix yet")
                lastFix = l
                useFix(l)
            }
            .setNegativeButton("Cancel") { _, _ -> stop() }
            .setOnCancelListener { stop() }
            .show()
    }

    private fun useFix(l: Location) {
        val g = app.engine.graph ?: return toast("Fix kept; load a graph to use it")
        val x = g.projection.toX(l.longitude); val y = g.projection.toY(l.latitude)
        val near = g.nearest(x, y, 500.0) ?: return toast("No graph within 500 m of the GPS fix")
        app.engine.fixPosition(x, y, maxOf(l.accuracy.toDouble(), 5.0), "gps")
        if (near.dist > 50) toast("Nearest gallery is ${near.dist.toInt()} m from the fix")
    }

    private fun openOsmAnd() {
        val s = last
        val b = s?.estimate?.best
        val ll = b?.let { s.toLatLon(it.x, it.y) }
        if (ll != null) app.bridge.showOnMap(ll.lat, ll.lon)
        else {
            val pkg = app.bridge.installedPackages().firstOrNull() ?: return toast("OsmAnd is not installed")
            packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
        }
    }

    private fun mark() {
        val input = EditText(this).apply { hint = "label (optional)"; setSingleLine() }
        app.engine.mark("")
        toast("MARK logged")
        // offer a label without blocking the quick mark
        AlertDialog.Builder(this).setTitle("Label the mark?").setView(input)
            .setPositiveButton("Save") { _, _ -> if (input.text.isNotBlank()) app.engine.mark(input.text.toString()) }
            .setNegativeButton("Skip", null).show()
    }

    // ---- graph -------------------------------------------------------------------------------

    private fun graphMenu(anchor: android.view.View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, "OSM around me: underground ways")
        m.menu.add(0, 2, 0, "OSM around me: all walkable ways")
        m.menu.add(0, 3, 0, "OSM around a lat, lon…")
        m.menu.add(0, 4, 0, "Import GeoJSON / GPX / KML…")
        m.menu.add(0, 5, 0, "Show graph in OsmAnd")
        m.menu.add(0, 6, 0, "Clear graph")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> overpassHere(GraphStore.OsmFilter.UNDERGROUND)
                2 -> overpassHere(GraphStore.OsmFilter.WALKABLE)
                3 -> overpassAt()
                4 -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }, REQ_IMPORT)
                5 -> app.engine.graph?.let { g ->
                    app.bridge.showGraph(g) { ok ->
                        ui.post { toast(if (ok) "Graph sent to OsmAnd (Tracks › spdr_graph)" else
                            "OsmAnd refused the GPX: allow \"Tracks edit\" for sPDR in OsmAnd › Connected apps") }
                    }
                } ?: toast("No graph")
                6 -> AlertDialog.Builder(this).setMessage("Delete all downloaded and imported graph sources?")
                    .setPositiveButton("Delete") { _, _ -> app.store.clear(); app.engine.clearGraph() }
                    .setNegativeButton("Cancel", null).show()
            }
            true
        }
        m.show()
    }

    private fun currentLatLon(): Pair<Double, Double>? {
        val s = last
        s?.estimate?.best?.let { b -> s.toLatLon(b.x, b.y)?.let { return it.lat to it.lon } }
        lastFix?.let { return it.latitude to it.longitude }
        if (hasAnyLocationPermission()) {
            val lm = getSystemService(LocationManager::class.java)
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }?.let { return it.latitude to it.longitude }
        }
        return null
    }

    private fun overpassHere(filter: GraphStore.OsmFilter) {
        val p = currentLatLon() ?: return toast("No location known: use GPS fix first, or enter coordinates")
        download(p.first, p.second, filter)
    }

    private fun overpassAt() {
        val input = EditText(this).apply {
            hint = "48.8339, 2.3324"
            currentLatLon()?.let { setText(String.format(Locale.US, "%.6f, %.6f", it.first, it.second)) }
        }
        val under = CheckBox(this).apply { text = "Underground ways only"; isChecked = true }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0); addView(input); addView(under) }
        AlertDialog.Builder(this).setTitle("Download OSM ways (radius ${app.prefs.overpassRadius} m)").setView(box)
            .setPositiveButton("Download") { _, _ ->
                val parts = input.text.split(',', ' ', ';').mapNotNull { it.trim().toDoubleOrNull() }
                if (parts.size != 2) return@setPositiveButton toast("Expected: lat, lon")
                download(parts[0], parts[1], if (under.isChecked) GraphStore.OsmFilter.UNDERGROUND else GraphStore.OsmFilter.WALKABLE)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun download(lat: Double, lon: Double, filter: GraphStore.OsmFilter) {
        toast("Downloading OSM ways…")
        Thread {
            try {
                val g = app.store.downloadOverpass(lat, lon, app.prefs.overpassRadius, filter)
                app.engine.setGraph(g)
                ui.post { toast("Graph: ${g.edges.size} edges, ${(g.totalLength / 1000).fmt(1)} km") }
            } catch (e: Exception) {
                ui.post { toast("Download failed: ${e.message}") }
            }
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK || data == null) return
        val uris = data.clipData?.let { c -> (0 until c.itemCount).map { c.getItemAt(it).uri } } ?: listOfNotNull(data.data)
        Thread {
            var g: org.spdr.companion.core.graph.Graph? = null
            val errors = ArrayList<String>()
            for (u in uris) {
                val name = displayName(u)
                try {
                    val bytes = contentResolver.openInputStream(u)!!.use { it.readBytes() }
                    g = app.store.addSource(name, bytes)
                } catch (e: Exception) {
                    errors.add("$name: ${e.message}")
                }
            }
            g?.let { app.engine.setGraph(it) }
            ui.post {
                toast(if (errors.isEmpty()) "Imported ${uris.size} file(s): ${g?.edges?.size} edges, " +
                    "${g?.nodes?.count { it.landmark }} landmarks" else errors.joinToString("\n"))
            }
        }.start()
    }

    private fun displayName(u: android.net.Uri): String =
        contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: u.lastPathSegment ?: "import"

    // ---- calibration and settings --------------------------------------------------------

    private fun calibMessage(s: Snapshot) =
        "Walk straight, at your normal pace, a distance you know (≥ 30 m, e.g. measured on the map).\n\n" +
            "Steps so far: ${s.calibrationSteps}\n\nThen enter the distance and tap Finish."

    private fun calibrate() {
        if (last?.sensorsOn != true) return toast("Start sensors first (▶ Start)")
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "distance in meters"
        }
        app.engine.startCalibration()
        calibDialog = AlertDialog.Builder(this).setTitle("Step length calibration")
            .setMessage(calibMessage(last!!))
            .setView(input)
            .setPositiveButton("Finish") { _, _ ->
                val m = input.text.toString().toDoubleOrNull()
                if (m == null || m < 10) { app.engine.cancelCalibration(); return@setPositiveButton toast("Need a distance ≥ 10 m") }
                val k = app.engine.finishCalibration(m)
                toast(if (k == null) "Too few steps detected" else "Weinberg K = ${k.fmt(3)} saved")
            }
            .setNegativeButton("Cancel") { _, _ -> app.engine.cancelCalibration() }
            .setOnDismissListener { calibDialog = null }
            .show()
    }

    private fun settings() {
        val p = app.prefs
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0) }
        fun label(t: String) = TextView(this).apply { text = t; setPadding(0, pad / 2, 0, 0) }
        fun number(v: Number) = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(v.toString()) }

        val mountings = Mounting.values()
        val mountBtn = Button(this).apply { text = p.mounting.label; tag = p.mounting }
        mountBtn.setOnClickListener {
            AlertDialog.Builder(this).setItems(mountings.map { it.label }.toTypedArray()) { _, i ->
                mountBtn.text = mountings[i].label; mountBtn.tag = mountings[i]
            }.show()
        }
        val particles = number(p.particles)
        val radius = number(p.overpassRadius)
        val compass = CheckBox(this).apply {
            text = "Use compass (tilt-compensated, weak but robust)"; isChecked = p.useCompass
        }
        val baro = CheckBox(this).apply { text = "Use barometer (only if the graph has heights)"; isChecked = p.useBarometer }
        val raw = CheckBox(this).apply { text = "Log raw sensors (for offline analysis)"; isChecked = p.logRawSensors }
        val gps = CheckBox(this).apply {
            text = "Log GPS next to the prediction (validation walks)"; isChecked = p.logGps
        }
        val gpsNote = TextView(this).apply {
            text = "Writes gps.csv: each fix with the position predicted at that instant, the " +
                "error between them and the distance dead-reckoned so far. Never fed back into " +
                "the filter, so the comparison stays honest. Above ground only — underground " +
                "there is no GPS and it just costs battery."
            textSize = 12f
        }
        val pkgs = listOf("") + OsmAndBridge.PACKAGES
        val pkgBtn = Button(this).apply { text = p.osmandPackage.ifEmpty { "auto" }; tag = p.osmandPackage }
        pkgBtn.setOnClickListener {
            AlertDialog.Builder(this).setItems(pkgs.map { it.ifEmpty { "auto" } }.toTypedArray()) { _, i ->
                pkgBtn.text = pkgs[i].ifEmpty { "auto" }; pkgBtn.tag = pkgs[i]
            }.show()
        }
        val calibInfo = TextView(this).apply {
            text = "Step scale ${p.stepScale.fmt(3)} (1.0 = average adult) · learned k prior " +
                "${p.kMean.fmt(3)} ± ${p.kStd.fmt(3)}"
        }
        box.addView(label("Phone mounting")); box.addView(mountBtn)
        box.addView(TextView(this).apply {
            text = "Hand-held reads the walking direction from the gait, so the phone can be " +
                "turned or pocketed; a rigid mounting is more accurate. Each mounting keeps its " +
                "own step-length calibration."
            textSize = 12f
        })
        box.addView(label("Particles")); box.addView(particles)
        box.addView(label("OSM download radius (m)")); box.addView(radius)
        box.addView(compass); box.addView(baro); box.addView(raw)
        box.addView(gps); box.addView(gpsNote)
        box.addView(label("OsmAnd app")); box.addView(pkgBtn)
        box.addView(label("Calibration")); box.addView(calibInfo)
        AlertDialog.Builder(this).setTitle("Settings").setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                p.mounting = mountBtn.tag as Mounting
                p.particles = particles.text.toString().toIntOrNull()?.coerceIn(100, 5000) ?: p.particles
                p.overpassRadius = radius.text.toString().toIntOrNull()?.coerceIn(100, 5000) ?: p.overpassRadius
                p.useCompass = compass.isChecked
                p.useBarometer = baro.isChecked
                p.logRawSensors = raw.isChecked
                p.logGps = gps.isChecked
                val pkgChanged = p.osmandPackage != pkgBtn.tag as String
                p.osmandPackage = pkgBtn.tag as String
                app.engine.applySettings()
                if (pkgChanged) { app.bridge.disconnect(); app.bridge.connect() }
                toast("Saved. Particle count, GPS logging and sensor options apply at the next start.")
            }
            .setNeutralButton("Forget calibration") { _, _ ->
                p.stepScale = org.spdr.companion.core.pdr.StepDetector.DEFAULT_SCALE
                p.kMean = 1.0; p.kStd = 0.1
                app.engine.applySettings()
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ---- misc --------------------------------------------------------------------------------

    private fun hasAnyLocationPermission() =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun requestPermissionsIfNeeded() {
        val wanted = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    private fun toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }

    private fun Double.fmt(n: Int) = String.format(Locale.US, "%.${n}f", this)

    companion object {
        private const val REQ_IMPORT = 10
        /** 5 Hz: enough for a compass needle to feel live, cheap enough to ignore. */
        private const val POLL_MS = 200L
    }
}
