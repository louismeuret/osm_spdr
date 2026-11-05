package org.spdr.companion

import android.content.Context
import android.hardware.GeomagneticField
import android.os.SystemClock
import org.json.JSONObject
import org.spdr.companion.core.DEG
import org.spdr.companion.core.wrap
import org.spdr.companion.core.filter.Estimate
import org.spdr.companion.core.filter.FilterConfig
import org.spdr.companion.core.filter.ParticleFilter
import org.spdr.companion.core.geo.LatLon
import org.spdr.companion.core.graph.Graph
import org.spdr.companion.core.pdr.BaroHeight
import org.spdr.companion.core.pdr.HeadingTracker
import org.spdr.companion.core.pdr.Step
import org.spdr.companion.core.pdr.StepDetector
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.hypot
import kotlin.math.max

/**
 * The two directions the app can report, which are not the same thing and must not be confused.
 *
 * [phone] is where the phone is pointing, from the tilt-compensated compass, live with every
 * magnetometer sample — the quantity an ordinary compass app shows, and so the one to check
 * against one. [travel] is where the walker is going, which is read from the gait and therefore
 * cannot change more than once per step; it goes NaN when you stand still, because then you have
 * no direction of travel and the last one you had is not an answer.
 */
data class Facing(
    /** Live absolute heading of the phone (rad, CCW from east, true north), NaN if no compass. */
    val phone: Double,
    /** Absolute direction of travel (rad), NaN while standing still or not yet known. */
    val travel: Double,
    /** Spread of the learned heading offset behind [travel] (rad), NaN if it is compass-only. */
    val travelStd: Double,
    /** Whether [travel] uses the filter's learned offset rather than the bare compass. */
    val fused: Boolean,
    /** [travel] from the compass alone, for comparison; NaN when the compass is not trusted. */
    val travelFromCompass: Double,
)

/** Immutable view of the engine for the UI and the OsmAnd bridge. */
class Snapshot(
    val graph: Graph?,
    val estimate: Estimate?,
    /** Subsampled particle positions (local meters) and weights, for display. */
    val px: FloatArray,
    val py: FloatArray,
    val pw: FloatArray,
    val sensorsOn: Boolean,
    val stepsDetected: Int,
    val lastResetError: Double?,
    val resetCount: Int,
    val calibrating: Boolean,
    val calibrationSteps: Int,
    /** The tilt-compensated compass currently has a trustworthy absolute heading. */
    val compassOk: Boolean,
    /** Field strength and dip, or why the compass is not trusted. */
    val compassInfo: String,
    /** Hand-held mode: the gait analysis resolved a walking direction for the last step. */
    val walkDirOk: Boolean,
    /** Distance from the last logged GPS fix to the prediction at that moment (m), NaN if none. */
    val gpsError: Double,
    /** How many GPS fixes this session has logged. */
    val gpsFixes: Int,
    val facing: Facing,
    val baroOk: Boolean,
    val version: Long,
) {
    val initialized get() = estimate?.best != null

    fun toLatLon(x: Double, y: Double): LatLon? =
        graph?.projection?.let { LatLon(it.toLat(y), it.toLon(x)) }
}

/**
 * Glue between Android sensors, the PDR front end and the particle filter. All state changes go
 * through this object (synchronized), whatever thread they come from: sensor thread, UI, or the
 * OsmAnd callback binder thread.
 */
class Engine(private val context: Context, val prefs: Prefs) {
    var graph: Graph? = null
        private set
    private var filter: ParticleFilter? = null
    private val steps = StepDetector(prefs.stepScale)
    private val heading = HeadingTracker(prefs.mounting)
    private val baro = BaroHeight()
    private var log: SessionLog? = null
    private var sensorsOn = false
    private var stepCount = 0
    private var lastResetError: Double? = null
    private var resetCount = 0
    private var calibrating = false
    private var calibSum = 0.0
    private var calibSteps = 0
    private var walkDirOk = false
    private var gpsError = Double.NaN
    private var gpsFixes = 0
    private var version = 0L

    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()

    fun addListener(l: (Snapshot) -> Unit) { listeners.add(l); l(snapshot()) }
    fun removeListener(l: (Snapshot) -> Unit) { listeners.remove(l) }

    /**
     * Rebuilt only when something has actually changed, because the UI now also polls for a live
     * compass between steps and clustering a thousand particles is not free.
     */
    private var cachedEstimate: Estimate? = null

    private fun estimate(): Estimate? {
        val pf = filter ?: return null
        cachedEstimate?.let { return it }
        return pf.estimate().also { cachedEstimate = it }
    }

    private fun notifyChanged() {
        version++
        cachedEstimate = null // every path that notifies has just mutated something
        val s = snapshot()
        for (l in listeners) l(s)
    }

    private fun config() = FilterConfig(
        particles = prefs.particles,
        useCompass = prefs.useCompass,
        useBarometer = prefs.useBarometer,
    )

    // ---- graph ---------------------------------------------------------------------------

    /** Installs a new graph. A running estimate is carried over (re-spread 10 m around it). */
    @Synchronized
    fun setGraph(g: Graph) {
        val previous = filter?.estimate()?.best?.let { b -> toLatLon(b.x, b.y) }
        graph = g
        filter = null
        setDeclination(g)
        if (previous != null) {
            initAt(g.projection.toX(previous.lon), g.projection.toY(previous.lat), 10.0, "graph-change")
        }
        log?.event("GRAPH", "${g.source} nodes=${g.nodes.size} edges=${g.edges.size}")
        notifyChanged()
    }

    @Synchronized
    fun clearGraph() {
        persistCalibration()
        graph = null
        filter = null
        notifyChanged()
    }

    // ---- sensors -------------------------------------------------------------------------

    @Synchronized
    fun startSession(extra: JSONObject) {
        sensorsOn = true
        heading.resetGait()
        gpsError = Double.NaN
        gpsFixes = 0
        log = SessionLog(context, extra.put("graph", graph?.source ?: "none"))
        notifyChanged()
    }

    @Synchronized
    fun stopSession() {
        sensorsOn = false
        walkDirOk = false
        persistCalibration()
        log?.close()
        log = null
        notifyChanged()
    }

    val sessionDir get() = log?.dir

    fun rawLog(file: String, header: String, line: String) { log?.write(file, header, line) }

    @Synchronized
    fun onAccel(t: Long, v: FloatArray) {
        heading.onAccel(t, v)
        val step = steps.onAccel(t, v[0], v[1], v[2]) ?: return
        onStep(step)
    }

    @Synchronized
    fun onGameRotation(t: Long, v: FloatArray) = heading.onGameRotation(t, v)

    @Synchronized
    fun onMagRotation(t: Long, v: FloatArray) = heading.onMagRotation(t, v)

    @Synchronized
    fun onGravity(t: Long, v: FloatArray) = heading.onGravity(t, v)

    @Synchronized
    fun onMagnetic(t: Long, v: FloatArray) = heading.onMagnetic(t, v)

    @Synchronized
    fun onMagAccuracy(value: Int) = heading.compass.onAccuracy(value)

    @Synchronized
    fun onPressure(t: Long, hPa: Float) = baro.onPressure(t, hPa)

    /**
     * A GPS fix, for measuring the error of a validation walk above ground. Logged **beside the
     * prediction made at the same instant**, so the difference needs no interpolation afterwards,
     * together with the distance dead-reckoned so far — error as a share of distance walked is
     * the number the spec is judged on (§11).
     *
     * This never touches the filter. Feeding GPS back in would make the comparison circular; the
     * "GPS fix" button is the separate, deliberate way to do that.
     *
     * @param tNs the fix's `elapsedRealtimeNanos`, the same time base as the sensor timestamps.
     */
    @Synchronized
    fun onGpsFix(
        tNs: Long, lat: Double, lon: Double, accuracy: Double, altitude: Double,
        speed: Double, bearing: Double, provider: String,
    ) {
        gpsFixes++
        val g = graph
        val e = estimate()
        val b = e?.best
        var gx = Double.NaN
        var gy = Double.NaN
        var err = Double.NaN
        if (g != null) {
            gx = g.projection.toX(lon); gy = g.projection.toY(lat)
            if (b != null) err = hypot(b.x - gx, b.y - gy)
        }
        gpsError = err
        val ll = b?.let { toLatLon(it.x, it.y) }
        log?.write("gps.csv",
            "timestamp_ns,provider,lat,lon,accuracy_m,altitude_m,speed_mps,bearing_deg," +
                "gps_x,gps_y,est_lat,est_lon,est_x,est_y,error_m,confidence,spread_m,holding," +
                "steps,distance_m",
            "$tNs,$provider,$lat,$lon,${f(accuracy)},${f(altitude)},${f(speed)},${f(bearing)}," +
                "${f(gx)},${f(gy)},${ll?.lat ?: ""},${ll?.lon ?: ""},${f(b?.x ?: Double.NaN)}," +
                "${f(b?.y ?: Double.NaN)},${f(err)},${f(b?.weight ?: Double.NaN)}," +
                "${f(b?.spread ?: Double.NaN)},${if (e?.holding == true) 1 else 0}," +
                "$stepCount,${f(e?.distance ?: Double.NaN)}")
        notifyChanged()
    }

    private fun onStep(step: Step) {
        stepCount++
        val hd = heading.takeStepWindow(step.tNs, step.dt)
        val h = baro.height
        walkDirOk = hd.walkDir != null
        val wd = hd.walkDir
        log?.write("steps.csv",
            "timestamp_ns,amplitude,length,raw_length,dt,psi,step_psi,stride,foot,sway,missed," +
                "offset,offset_platform,baro_h,pca_axis,pca_elongation,pca_corr,pca_samples",
            "${step.tNs},${f(step.amplitude)},${f(step.length)},${f(step.raw)},${f(step.dt)}," +
                "${f(hd.psi)},${f(hd.stepPsi)},${if (hd.stride) 1 else 0},${hd.foot},${f(hd.sway)}," +
                "${if (hd.missedStep) 1 else 0},${f(hd.offset)},${f(hd.platformOffset)},${f(h)}," +
                "${f(wd?.axis ?: Double.NaN)},${f(wd?.elongation ?: Double.NaN)}," +
                "${f(wd?.verticalCorr ?: Double.NaN)},${wd?.samples ?: ""}")
        if (calibrating) { calibSum += step.raw; calibSteps++ }
        val pf = filter ?: return notifyChanged()
        pf.step(step, hd.psi, hd.offset, h)
        logEstimate(step.tNs, pf.estimate())
        notifyChanged()
    }

    /** Local meters → WGS84 on the current graph; cheaper than building a whole Snapshot. */
    fun toLatLon(x: Double, y: Double): LatLon? =
        graph?.projection?.let { LatLon(it.toLat(y), it.toLon(x)) }

    private fun logEstimate(t: Long, e: Estimate) {
        val b = e.best ?: return
        val ll = toLatLon(b.x, b.y) ?: return
        log?.write("estimates.csv", "timestamp_ns,lat,lon,x,y,edge,s,weight,spread,k,k_std,theta,theta_std,bias,neff",
            "$t,${ll.lat},${ll.lon},${f(b.x)},${f(b.y)},${graph!!.edges[b.edge].id},${f(b.s)},${f(b.weight)},${f(b.spread)}," +
                "${f(e.kMean)},${f(e.kStd)},${f(e.thetaMean)},${f(e.thetaStd)},${f(e.biasMean)},${f(e.neff)}")
    }

    // ---- position fixes and manual corrections ---------------------------------------------

    /** Starts tracking around (x, y) (local meters of the current graph). */
    @Synchronized
    fun initAt(x: Double, y: Double, radius: Double, source: String) {
        val g = graph ?: return
        val pf = ParticleFilter(g, config())
        val now = SystemClock.elapsedRealtimeNanos()
        val off = if (prefs.useCompass) heading.magOffset(now) else Double.NaN
        pf.init(x, y, radius, prefs.kMean, prefs.kStd,
            thetaMean = off, thetaStd = if (off.isNaN()) Math.PI else 30 * Math.PI / 180)
        filter = pf
        log?.event("INIT", "$source x=${f(x)} y=${f(y)} r=${f(radius)} compass=${!off.isNaN()}")
        notifyChanged()
    }

    /** "I am here": a confirmed position. Starts the filter if it wasn't running. */
    @Synchronized
    fun fixPosition(x: Double, y: Double, sigma: Double, source: String) {
        val pf = filter
        if (pf == null) { initAt(x, y, sigma, source); return }
        val err = pf.reset(x, y, sigma, baro.height)
        lastResetError = err
        resetCount++
        log?.event("RESET", "$source x=${f(x)} y=${f(y)} sigma=${f(sigma)} error_before=${err?.let { f(it) }}")
        notifyChanged()
    }

    @Synchronized
    fun nudge(d: Double) { filter?.nudge(d); log?.event("NUDGE", f(d)); notifyChanged() }

    @Synchronized
    fun uTurn() { filter?.uTurn(); log?.event("UTURN", ""); notifyChanged() }

    @Synchronized
    fun selectHypothesis(x: Double, y: Double) {
        filter?.selectHypothesis(x, y); log?.event("SELECT", "x=${f(x)} y=${f(y)}"); notifyChanged()
    }

    @Synchronized
    fun rejectBest() { filter?.rejectBest(); log?.event("REJECT", ""); notifyChanged() }

    @Synchronized
    fun mark(label: String) { log?.event("MARK", label); notifyChanged() }

    @Synchronized
    fun stopTracking() { persistCalibration(); filter = null; notifyChanged() }

    // ---- calibration -----------------------------------------------------------------------

    @Synchronized
    fun startCalibration() { calibrating = true; calibSum = 0.0; calibSteps = 0; notifyChanged() }

    /** Ends a walk of known length; returns the new step length scale, or null if too few steps. */
    @Synchronized
    fun finishCalibration(meters: Double): Double? {
        calibrating = false
        val k = if (calibSteps >= 10 && calibSum > 0) meters / calibSum else null
        if (k != null) {
            prefs.stepScale = k
            steps.stepScale = k
            // the filter's k now multiplies a calibrated length: restart its prior at 1
            prefs.kMean = 1.0; prefs.kStd = 0.05
            log?.event("CALIBRATION", "scale=${f(k)} steps=$calibSteps distance=$meters")
        }
        notifyChanged()
        return k
    }

    @Synchronized
    fun cancelCalibration() { calibrating = false; notifyChanged() }

    @Synchronized
    fun applySettings() {
        heading.mounting = prefs.mounting
        steps.stepScale = prefs.stepScale
        graph?.let { setDeclination(it) }
    }

    /** Carries the learned step scale to the next session, only if resets actually taught something. */
    private fun persistCalibration() {
        val e = filter?.estimate() ?: return
        if (resetCount == 0) return
        prefs.kMean = e.kMean
        prefs.kStd = max(0.03, e.kStd)
        resetCount = 0
    }

    // ---- snapshot --------------------------------------------------------------------------

    @Synchronized
    fun snapshot(): Snapshot {
        val pf = filter
        val est = estimate()
        val m = if (pf == null) 0 else minOf(pf.n, 400)
        val px = FloatArray(m); val py = FloatArray(m); val pw = FloatArray(m)
        if (pf != null) {
            val stride = pf.n / m.coerceAtLeast(1)
            for (j in 0 until m) {
                val i = j * stride
                val e = pf.graph.edges[pf.edge[i]]
                px[j] = e.x(pf.s[i]).toFloat(); py[j] = e.y(pf.s[i]).toFloat(); pw[j] = pf.w[i].toFloat()
            }
        }
        val now = SystemClock.elapsedRealtimeNanos()
        val psi = heading.travelHeading(now)
        val offset = if (psi.isNaN()) Double.NaN else heading.magOffset(now)
        val fromCompass = if (offset.isNaN()) Double.NaN else wrap(psi + offset)
        // the filter's θ is only worth showing once the galleries walked have pinned it down
        val fused = est?.best != null && est.thetaStd < MAX_USEFUL_THETA_STD && !psi.isNaN()
        val facing = Facing(
            phone = heading.phoneHeading,
            travel = if (fused) wrap(psi + est!!.thetaMean) else fromCompass,
            travelStd = if (fused) est!!.thetaStd else Double.NaN,
            fused = fused,
            travelFromCompass = fromCompass,
        )
        return Snapshot(graph, est, px, py, pw, sensorsOn, stepCount, lastResetError, resetCount,
            calibrating, calibSteps, heading.compass.valid, heading.compass.status,
            walkDirOk && prefs.mounting.fromGait, gpsError, gpsFixes, facing,
            !baro.height.isNaN(), version)
    }

    /**
     * The map's local frame is true-north ENU while the compass reads magnetic north, so the
     * declination at the map origin has to be taken out. It varies slowly enough that the origin
     * is a good enough place to evaluate it for a few-km network.
     */
    private fun setDeclination(g: Graph) {
        val d = runCatching {
            GeomagneticField(g.projection.lat0.toFloat(), g.projection.lon0.toFloat(), 0f,
                System.currentTimeMillis()).declination.toDouble() * DEG
        }.getOrDefault(0.0)
        heading.declination = d
        log?.event("DECLINATION", "%.2f deg".format(java.util.Locale.US, d / DEG))
    }

    private fun f(v: Double) = if (v.isNaN()) "" else "%.4f".format(java.util.Locale.US, v)

    private companion object {
        /** Above about 40° of uncertainty the learned heading offset is not worth displaying. */
        const val MAX_USEFUL_THETA_STD = 40 * DEG
    }
}
