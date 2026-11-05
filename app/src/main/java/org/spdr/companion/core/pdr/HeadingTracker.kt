package org.spdr.companion.core.pdr

import org.spdr.companion.core.wrap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How the phone is carried, and therefore how its walking direction is obtained.
 *
 * For a rigid mounting one device axis points where the walker goes, so its heading is the walking
 * heading. Held in the hand the phone turns freely, so the walking direction has to be read from
 * the gait itself ([WalkingPca]).
 *
 * @param axis device axis along the walking direction (0 = x, 1 = y, 2 = z), −1 for hand-held.
 * @param reversed the walking direction is the negative of that axis.
 */
enum class Mounting(val label: String, val axis: Int, val reversed: Boolean = false) {
    CHEST_SCREEN_OUT("Chest, screen facing out", 2),
    CHEST_SCREEN_IN("Chest, screen facing body", 2, reversed = true),
    HAND_FLAT("Hand, flat, top forward", 1),
    HANDHELD("Hand-held, any orientation", -1);

    /** Whether the walking direction comes from the gait rather than from a fixed axis. */
    val fromGait get() = axis < 0
}

/** Everything the filter and the logs get about heading for one step. */
data class StepHeading(
    /**
     * The walking heading for the filter to use, in the tracking frame (rad, CCW from +x), NaN if
     * unknown: [stepPsi] averaged with the previous step when the two make up a stride.
     */
    val psi: Double,
    /** The raw single-step heading, before stride averaging; logged to see what it bought. */
    val stepPsi: Double,
    /** +1 or −1 for the two feet, 0 when the sway was too weak to tell them apart. */
    val foot: Int,
    /** Mean sideways acceleration over the step, positive to the left (m/s²). */
    val sway: Double,
    /** Two steps running on the same foot: one was probably missed, so the stride is broken. */
    val missedStep: Boolean,
    /** Whether [psi] is a stride average rather than a single step. */
    val stride: Boolean,
    /** Measured offset of the tracking frame to the map frame, from our own compass; NaN if none. */
    val offset: Double,
    /** The same offset from Android's fused rotation vector, logged for comparison only. */
    val platformOffset: Double,
    /** Gait analysis for this step, null when not hand-held or not resolvable. */
    val walkDir: WalkDir?,
)

/**
 * Heading front end: turns the orientation sensors into the two quantities the filter wants, both
 * in the math convention (rad, counter-clockwise from east, matching the map's local ENU frame).
 *
 *  - `psi`, the walking heading in a *relative* frame that is smooth and drifts slowly: the
 *    game rotation vector's world frame (gyro + accelerometer, no magnetometer), which is the only
 *    thing that still works surrounded by iron. Its azimuth origin is arbitrary; the offset to the
 *    map, θ, is a per-particle state the filter estimates.
 *  - `offset`, a weak absolute measurement of that same θ, from the tilt-compensated compass
 *    ([TiltCompass]): the phone's absolute heading from accelerometer and magnetometer, works in
 *    any orientation, gated and corrected for declination.
 *
 * With no game rotation vector on the device, the compass frame is used for both, in which case
 * the measured offset is zero by construction and the filter is left to work out the residual.
 */
class HeadingTracker(
    mounting: Mounting = Mounting.CHEST_SCREEN_OUT,
    /** Magnetic declination at the map origin (rad, east positive). */
    declination: Double = 0.0,
) {
    var mounting: Mounting = mounting
        set(value) { if (value != field) resetGait(); field = value }

    var declination: Double
        get() = compass.declination
        set(value) { compass.declination = value }

    val compass = TiltCompass()
    val gait = WalkingPca()
    private val gameRot = DoubleArray(9)
    private val platRot = DoubleArray(9)
    private val world = DoubleArray(3)
    private var gameT = 0L
    private var psiS = 0.0
    private var psiC = 0.0
    private var lastPsi = Double.NaN
    private var prevPsi = Double.NaN
    private var prevFoot = 0
    private var prevStepT = 0L
    private var offS = 0.0
    private var offC = 0.0
    private var platS = 0.0
    private var platC = 0.0
    private var smoothS = 0.0
    private var smoothC = 0.0
    private var lastOffT = 0L

    init { compass.declination = declination }

    /** Most recent walking heading in the tracking frame, NaN if none. */
    val current get() = lastPsi

    /**
     * Live absolute heading of the phone itself, updated with every magnetometer sample. The
     * direction of *travel* can only change once per step, since it is read from the gait, so the
     * two are reported separately rather than one standing in for the other.
     */
    val phoneHeading get() = compass.phoneHeading()

    /**
     * The walking heading, or NaN if the last step is too old to call it a direction of travel —
     * standing still, you have none, and showing the last one you had is a lie.
     */
    fun travelHeading(nowNs: Long): Double =
        if (prevStepT == 0L || nowNs - prevStepT > TRAVEL_STALE) Double.NaN else lastPsi

    /** Drops the acceleration window and the stride pairing, e.g. between sessions. */
    fun resetGait() {
        gait.reset()
        prevPsi = Double.NaN; prevFoot = 0; prevStepT = 0L; lastPsi = Double.NaN
    }

    fun onGameRotation(tNs: Long, v: FloatArray) {
        quatToMatrix(v, gameRot)
        gameT = tNs
        if (!mounting.fromGait) accumulatePsi(gameRot)
    }

    /** Android's fused orientation: kept only so the logs can compare it with our own compass. */
    fun onMagRotation(tNs: Long, v: FloatArray) {
        quatToMatrix(v, platRot)
        val tr = trackingRot(tNs) ?: return
        val j = horizontalAxis(tr)
        val absH = axisHeading(platRot, j)
        val relH = axisHeading(tr, j)
        if (absH.isNaN() || relH.isNaN()) return
        val d = wrap(absH - compass.declination - relH)
        platS += sin(d); platC += cos(d)
    }

    fun onGravity(tNs: Long, v: FloatArray) = compass.onGravity(tNs, v[0], v[1], v[2])

    fun onMagnetic(tNs: Long, v: FloatArray) {
        compass.onMagnetic(tNs, v[0], v[1], v[2])
        val tr = trackingRot(tNs) ?: return
        if (!usingGame(tNs) && !mounting.fromGait) accumulatePsi(tr)
        val j = horizontalAxis(tr)
        val absH = compass.axisHeading(j)
        val relH = axisHeading(tr, j)
        if (absH.isNaN() || relH.isNaN()) return
        val d = wrap(absH - relH)
        offS += sin(d); offC += cos(d)
        smoothS += EWMA * (sin(d) - smoothS); smoothC += EWMA * (cos(d) - smoothC)
        lastOffT = tNs
    }

    /**
     * Feeds the accelerometer: gravity for the compass when the device has no gravity sensor, and
     * the horizontal accelerations the gait analysis works on, rotated into the tracking frame.
     * The buffer is filled whatever the mounting, because the sway that tells the two feet apart
     * is read from it even when the walking direction comes from a fixed axis.
     */
    fun onAccel(tNs: Long, v: FloatArray) {
        compass.onAccel(tNs, v[0], v[1], v[2])
        val r = trackingRot(tNs) ?: return
        val x = v[0].toDouble(); val y = v[1].toDouble(); val z = v[2].toDouble()
        world[0] = r[0] * x + r[1] * y + r[2] * z
        world[1] = r[3] * x + r[4] * y + r[5] * z
        world[2] = r[6] * x + r[7] * y + r[8] * z
        gait.add(tNs, world[0], world[1], world[2])
    }

    /**
     * Slowly averaged compass offset, NaN when there is no recent magnetic data or when it is too
     * unsteady to mean anything — near iron the directions scatter and the vector average shrinks.
     * Used to initialise the filter's θ rather than as a per-step measurement.
     */
    fun magOffset(nowNs: Long): Double =
        if (lastOffT == 0L || nowNs - lastOffT > OFFSET_STALE || hypot(smoothS, smoothC) < MIN_RESULTANT) Double.NaN
        else atan2(smoothS, smoothC)

    /**
     * Heading quantities for the step of length `dt` seconds ending at `tNs`; clears the window
     * accumulators.
     *
     * The heading handed to the filter is averaged over a stride — this step and the previous one
     * — rather than over the single step. Walking rocks the body from side to side at the stride
     * frequency, so a left step and a right step are yawed a few degrees in opposite directions,
     * and averaging a pair cancels most of that while a single step cannot. Which foot it was
     * comes from the sign of the sway, so a pair is only averaged when the two steps really are
     * opposite feet, recent, and not separated by a missed step. (Successive strides overlap, so
     * consecutive measurements share a step and are not quite independent; removing a systematic
     * few degrees is worth more than that costs.)
     */
    fun takeStepWindow(tNs: Long, dt: Double): StepHeading {
        val walk = if (mounting.fromGait) gait.solve(tNs) else null
        // a step with no usable heading still happened: carry the last one, as a rigid mounting
        // does when the rotation vector goes quiet, and let the map and the filter's own heading
        // noise absorb it. `walkDir` stays null, so the gap is visible in the UI and the log.
        val stepPsi = when {
            mounting.fromGait -> walk?.heading ?: lastPsi
            psiS == 0.0 && psiC == 0.0 -> lastPsi
            else -> atan2(psiS, psiC)
        }
        if (!stepPsi.isNaN()) lastPsi = stepPsi

        val from = tNs - (dt.coerceIn(0.1, 2.0) * 1e9).toLong()
        val sway = gait.lateralMean(from, tNs, stepPsi)
        val foot = if (sway.isNaN() || abs(sway) < MIN_SWAY) 0 else if (sway > 0) 1 else -1
        val missed = foot != 0 && foot == prevFoot && tNs - prevStepT < STRIDE_MAX
        val pair = foot != 0 && prevFoot != 0 && foot != prevFoot &&
            !prevPsi.isNaN() && !stepPsi.isNaN() && tNs - prevStepT < STRIDE_MAX
        val psi = if (pair) atan2(sin(stepPsi) + sin(prevPsi), cos(stepPsi) + cos(prevPsi)) else stepPsi
        prevPsi = stepPsi; prevFoot = foot; prevStepT = tNs

        val off = if (hypot(offS, offC) < 1e-9) Double.NaN else atan2(offS, offC)
        val plat = if (hypot(platS, platC) < 1e-9) Double.NaN else atan2(platS, platC)
        psiS = 0.0; psiC = 0.0; offS = 0.0; offC = 0.0; platS = 0.0; platC = 0.0
        return StepHeading(psi, stepPsi, foot, sway, missed, pair, off, plat, walk)
    }

    // ---- internals ---------------------------------------------------------------------------

    private fun usingGame(tNs: Long) = gameT != 0L && tNs - gameT < GAME_STALE

    /**
     * The frame psi is expressed in: the game rotation vector while it is live, otherwise the
     * compass frame (noisier, since it is rebuilt from the magnetometer, but absolute).
     */
    private fun trackingRot(tNs: Long): DoubleArray? =
        if (usingGame(tNs)) gameRot else if (compass.valid) compass.rotation else null

    private fun accumulatePsi(r: DoubleArray) {
        val h = axisHeading(r, mounting.axis)
        if (h.isNaN()) return
        val f = if (mounting.reversed) wrap(h + PI) else h
        psiS += sin(f); psiC += cos(f)
    }

    /** Heading of device axis `j` in the world frame of `r`, NaN if that axis is near-vertical. */
    private fun axisHeading(r: DoubleArray, j: Int): Double {
        val e = r[j]; val n = r[3 + j]
        return if (hypot(e, n) < MIN_HORIZONTAL) Double.NaN else atan2(n, e)
    }

    /** Device axis furthest from vertical in `r`: the one whose heading is best defined. */
    private fun horizontalAxis(r: DoubleArray): Int {
        var best = 0
        var bestH = -1.0
        for (j in 0 until 3) {
            val h = hypot(r[j], r[3 + j])
            if (h > bestH) { bestH = h; best = j }
        }
        return best
    }

    companion object {
        private const val GAME_STALE = 1_000_000_000L
        private const val OFFSET_STALE = 2_000_000_000L
        private const val MIN_RESULTANT = 0.5
        private const val MIN_HORIZONTAL = 0.3
        private const val EWMA = 0.02
        /** Below this the sway does not say which foot it was (m/s²). */
        private const val MIN_SWAY = 0.06
        /** Two steps further apart than this are not one stride. */
        private const val STRIDE_MAX = 1_600_000_000L
        /** After this long without a step you are standing, not travelling. */
        private const val TRAVEL_STALE = 4_000_000_000L
    }
}

/**
 * Device → world rotation matrix (row-major) from a rotation vector, as
 * `SensorManager.getRotationMatrixFromVector` builds it. The scalar part is recomputed when the
 * sensor omits it.
 */
fun quatToMatrix(v: FloatArray, r: DoubleArray) {
    val x = v[0].toDouble(); val y = v[1].toDouble(); val z = v[2].toDouble()
    val w = if (v.size >= 4) v[3].toDouble() else sqrt((1 - x * x - y * y - z * z).coerceAtLeast(0.0))
    r[0] = 1 - 2 * (y * y + z * z); r[1] = 2 * (x * y - z * w);     r[2] = 2 * (x * z + y * w)
    r[3] = 2 * (x * y + z * w);     r[4] = 1 - 2 * (x * x + z * z); r[5] = 2 * (y * z - x * w)
    r[6] = 2 * (x * z - y * w);     r[7] = 2 * (y * z + x * w);     r[8] = 1 - 2 * (x * x + y * y)
}

/** Relative height from the barometer, low-passed (spec §5.5). */
class BaroHeight {
    private var p0 = Double.NaN
    private var h = Double.NaN
    private var lastT = 0L

    val height get() = h

    fun onPressure(tNs: Long, hPa: Float) {
        if (p0.isNaN()) p0 = hPa.toDouble()
        val raw = 44330.0 * (1 - Math.pow(hPa / p0, 1 / 5.255))
        if (h.isNaN()) {
            h = raw
        } else {
            val dt = ((tNs - lastT) / 1e9).coerceIn(0.0, 5.0)
            h += dt / (TAU + dt) * (raw - h)
        }
        lastT = tNs
    }

    companion object {
        private const val TAU = 0.8
    }
}
