package org.spdr.companion.core.pdr

import org.spdr.companion.core.DEG
import org.spdr.companion.core.wrap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Tilt-compensated compass: the phone's absolute attitude from the accelerometer and the
 * magnetometer alone, in any orientation.
 *
 * The accelerometer measures gravity, which says which way is down, so roll and pitch. The
 * magnetometer measures the Earth's field; projected on the horizontal plane (perpendicular to
 * gravity) its direction is magnetic north. Together they fix all three angles whether the phone
 * is flat, vertical or upside down — the same construction as `SensorManager.getRotationMatrix`,
 * written out here so every reading can be gated and logged.
 *
 * The accelerometer alone cannot give a heading: gravity is vertical, so it carries no information
 * about rotation around the vertical axis.
 *
 * Two things make this noisy for us. While walking, the accelerometer sees body accelerations on
 * top of gravity, so the tilt estimate wobbles: use `TYPE_GRAVITY` (Android's gyro-aided fusion)
 * when the device has it, and fall back to a one-second low pass of the raw accelerometer, which
 * averages over a full step. Underground, iron distorts the field, so each reading is gated on
 * field strength, on the angle between field and gravity, and on the stability of both; [valid]
 * says whether the heading can be trusted at all, and the filter still treats it as a weak,
 * robust measurement of the heading offset rather than as truth.
 */
class TiltCompass {
    /**
     * Magnetic declination at the map origin (rad, east positive). Headings returned by this
     * class are relative to **true** north, which is what the map's local ENU frame uses.
     */
    var declination = 0.0

    private val g = DoubleArray(3)       // smoothed gravity, device frame, pointing up
    private val m = DoubleArray(3)       // smoothed magnetic field, device frame
    private val rot = DoubleArray(9)     // device → world (ENU), row-major
    private var gT = 0L
    private var mT = 0L
    private var gravityT = 0L            // last real TYPE_GRAVITY event
    private var accuracy = ACCURACY_HIGH
    private var meanField = Double.NaN
    private var devField = 0.0
    private var meanDip = Double.NaN
    private var devDip = 0.0
    private var ok = false
    private var why = "no data"

    /** True while the current attitude is usable: field plausible, steady, and not vertical. */
    val valid get() = ok

    /** Device → world (ENU: x east, y north, z up) rotation matrix, row-major. Do not modify. */
    val rotation: DoubleArray get() = rot

    /** Smoothed field strength (µT), NaN before the first sample. */
    val fieldStrength get() = meanField

    /** Smoothed dip angle, positive below the horizon (rad). */
    val dip get() = meanDip

    /** One line for the status bar and the session log. */
    val status: String
        get() = if (ok) "${meanField.toInt()} µT, dip ${(meanDip / DEG).toInt()}°" else why

    /** Android's magnetometer accuracy; only `SENSOR_STATUS_UNRELIABLE` disqualifies it outright. */
    fun onAccuracy(value: Int) { accuracy = value }

    /** `TYPE_GRAVITY`: already gyro-aided, so it is used as is (lightly smoothed). */
    fun onGravity(tNs: Long, x: Float, y: Float, z: Float) {
        gravityT = tNs
        blend(g, x, y, z, alpha(tNs, gT, GRAVITY_TAU))
        gT = tNs
    }

    /** Raw accelerometer: only used as a gravity source when the device has no gravity sensor. */
    fun onAccel(tNs: Long, x: Float, y: Float, z: Float) {
        if (gravityT != 0L && tNs - gravityT < GRAVITY_FRESH) return
        blend(g, x, y, z, alpha(tNs, gT, ACCEL_TAU))
        gT = tNs
    }

    fun onMagnetic(tNs: Long, x: Float, y: Float, z: Float) {
        val a = alpha(tNs, mT, FIELD_TAU)
        val slow = alpha(tNs, mT, SLOW_TAU)
        blend(m, x, y, z, a)
        mT = tNs
        val raw = sqrt((x * x + y * y + z * z).toDouble())
        if (meanField.isNaN()) {
            meanField = raw
        } else {
            devField += slow * (abs(raw - meanField) - devField)
            meanField += slow * (raw - meanField)
        }
        recompute(slow)
    }

    /**
     * Heading of device axis `j` (0 = x, 1 = y, 2 = z) in the map frame: rad, counter-clockwise
     * from east, relative to true north. NaN when the compass is unusable or that axis points
     * too close to the vertical for its heading to mean anything.
     */
    fun axisHeading(j: Int): Double {
        if (!ok) return Double.NaN
        val e = rot[j]
        val n = rot[3 + j]
        if (hypot(e, n) < MIN_HORIZONTAL) return Double.NaN
        return wrap(atan2(n, e) - declination)
    }

    /**
     * Where the phone itself is pointing, right now: the heading of the top edge (device +y) when
     * it is far enough off vertical, otherwise of the back of the phone (device −z), which is what
     * you are facing when you hold it up to read it. This is the quantity an ordinary compass app
     * shows, so it is the one to check this against — it is **not** the direction of travel, which
     * comes from the gait and can only be known once per step.
     */
    fun phoneHeading(): Double {
        if (!ok) return Double.NaN
        val top = axisHeading(1)
        if (!top.isNaN()) return top
        val back = axisHeading(2)
        return if (back.isNaN()) Double.NaN else wrap(back + Math.PI)
    }

    /** The device axis currently furthest from vertical: the one whose heading is best defined. */
    fun mostHorizontalAxis(): Int {
        var best = 0
        var bestH = -1.0
        for (j in 0 until 3) {
            val h = hypot(rot[j], rot[3 + j])
            if (h > bestH) { bestH = h; best = j }
        }
        return best
    }

    /** Rotates a device-frame vector into the world frame (east, north, up) in `out`. */
    fun rotate(x: Double, y: Double, z: Double, out: DoubleArray) {
        out[0] = rot[0] * x + rot[1] * y + rot[2] * z
        out[1] = rot[3] * x + rot[4] * y + rot[5] * z
        out[2] = rot[6] * x + rot[7] * y + rot[8] * z
    }

    private fun alpha(tNs: Long, lastNs: Long, tau: Double): Double {
        if (lastNs == 0L) return 1.0
        val dt = ((tNs - lastNs) / 1e9).coerceIn(1e-4, 0.5)
        return dt / (tau + dt)
    }

    private fun blend(v: DoubleArray, x: Float, y: Float, z: Float, a: Double) {
        v[0] += a * (x - v[0]); v[1] += a * (y - v[1]); v[2] += a * (z - v[2])
    }

    /**
     * Rebuilds the rotation matrix. East comes from m × g (the accelerometer reads the reaction to
     * gravity, so g points up), north from up × east; that is the standard construction and it
     * needs the field and gravity not to be collinear.
     */
    private fun recompute(slow: Double) {
        ok = false
        if (gT == 0L || mT == 0L) { why = "no data"; return }
        if (accuracy <= ACCURACY_UNRELIABLE) { why = "compass uncalibrated"; return }
        val gn = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
        if (gn < MIN_G || gn > MAX_G) { why = "shaken"; return }
        val mn = sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2])
        if (mn < MIN_FIELD || mn > MAX_FIELD) { why = "field ${mn.toInt()} µT"; return }

        var ex = m[1] * g[2] - m[2] * g[1]
        var ey = m[2] * g[0] - m[0] * g[2]
        var ez = m[0] * g[1] - m[1] * g[0]
        val en = sqrt(ex * ex + ey * ey + ez * ez)
        if (en < MIN_SIN * mn * gn) { why = "field vertical"; return }
        ex /= en; ey /= en; ez /= en
        val ux = g[0] / gn; val uy = g[1] / gn; val uz = g[2] / gn
        rot[0] = ex; rot[1] = ey; rot[2] = ez
        rot[3] = uy * ez - uz * ey; rot[4] = uz * ex - ux * ez; rot[5] = ux * ey - uy * ex
        rot[6] = ux; rot[7] = uy; rot[8] = uz

        // the field has no east component by construction, so the dip follows from north and up
        val fn = rot[3] * m[0] + rot[4] * m[1] + rot[5] * m[2]
        val fu = rot[6] * m[0] + rot[7] * m[1] + rot[8] * m[2]
        val d = atan2(-fu, fn)
        if (meanDip.isNaN()) meanDip = d
        else { devDip += slow * (abs(wrap(d - meanDip)) - devDip); meanDip = wrap(meanDip + slow * wrap(d - meanDip)) }

        // a steady field is a believable field: iron shows up as the strength or the dip moving
        if (devField > MAX_DEV_FIELD || devDip > MAX_DEV_DIP) { why = "field disturbed"; return }
        ok = true
    }

    companion object {
        const val ACCURACY_UNRELIABLE = 0
        const val ACCURACY_HIGH = 3

        /** Earth's field is 25–65 µT; outside this range we are reading something else. */
        private const val MIN_FIELD = 20.0
        private const val MAX_FIELD = 75.0
        private const val MIN_G = 6.0
        private const val MAX_G = 14.0
        /** Minimum sine of the angle between field and gravity for north to be well defined. */
        private const val MIN_SIN = 0.15
        /** Minimum horizontal share of an axis for its heading to be meaningful. */
        private const val MIN_HORIZONTAL = 0.3
        private const val MAX_DEV_FIELD = 6.0
        private const val MAX_DEV_DIP = 12 * DEG
        private const val GRAVITY_TAU = 0.1
        private const val ACCEL_TAU = 1.0
        private const val FIELD_TAU = 0.3
        private const val SLOW_TAU = 2.0
        private const val GRAVITY_FRESH = 2_000_000_000L
    }
}
