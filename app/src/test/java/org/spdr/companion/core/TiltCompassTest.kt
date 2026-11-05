package org.spdr.companion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.spdr.companion.core.pdr.TiltCompass
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Attitude from accelerometer + magnetometer, in orientations the phone is actually used in. */
class TiltCompassTest {

    private fun feed(
        c: TiltCompass, r: DoubleArray, seconds: Double = 6.0,
        useGravitySensor: Boolean = true, field: DoubleArray = FIELD,
    ) {
        val g = Attitude.toDevice(r, doubleArrayOf(0.0, 0.0, 9.81))
        val m = Attitude.toDevice(r, field)
        var t = 1_000_000_000L
        val n = (seconds / 0.02).toInt()
        for (i in 0 until n) {
            if (useGravitySensor) c.onGravity(t, g[0].toFloat(), g[1].toFloat(), g[2].toFloat())
            else c.onAccel(t, g[0].toFloat(), g[1].toFloat(), g[2].toFloat())
            c.onMagnetic(t, m[0].toFloat(), m[1].toFloat(), m[2].toFloat())
            t += 20_000_000L
        }
    }

    /** Heading of device axis j as the ground truth attitude has it. */
    private fun trueHeading(r: DoubleArray, j: Int) = atan2(r[3 + j], r[j])

    private fun checkAxes(r: DoubleArray, c: TiltCompass, declination: Double = 0.0) {
        for (j in 0 until 3) {
            val expected = trueHeading(r, j)
            val got = c.axisHeading(j)
            if (hypot(r[j], r[3 + j]) < 0.35) continue // near-vertical axis: no heading to check
            assertFalse("axis $j should have a heading", got.isNaN())
            assertEquals("axis $j", 0.0, wrap(got - (expected - declination)) / DEG, 1.0)
        }
    }

    @Test fun flat() = orientation(30.0, 0.0, 0.0)
    @Test fun upright() = orientation(120.0, 0.0, 90.0)
    @Test fun upsideDown() = orientation(200.0, 0.0, 180.0)
    @Test fun tilted() = orientation(70.0, 20.0, 45.0)
    @Test fun screenDown() = orientation(-100.0, 0.0, 175.0)

    private fun orientation(yaw: Double, pitch: Double, roll: Double) {
        val r = Attitude.rot(yaw * DEG, pitch * DEG, roll * DEG)
        val c = TiltCompass()
        feed(c, r)
        assertTrue("compass should be valid: ${c.status}", c.valid)
        assertEquals(48.0, c.fieldStrength, 0.5)
        assertEquals(64.0, c.dip / DEG, 1.0)
        checkAxes(r, c)
    }

    @Test fun worksFromTheAccelerometerAlone() {
        val r = Attitude.rot(145 * DEG, 10 * DEG, 60 * DEG)
        val c = TiltCompass()
        feed(c, r, useGravitySensor = false)
        assertTrue(c.status, c.valid)
        checkAxes(r, c)
    }

    @Test fun declinationTakesHeadingsToTrueNorth() {
        val r = Attitude.rot(30 * DEG, 0.0, 0.0)
        val c = TiltCompass()
        c.declination = 5 * DEG
        feed(c, r)
        checkAxes(r, c, declination = 5 * DEG)
    }

    @Test fun rejectsAnImplausibleField() {
        val r = Attitude.rot(30 * DEG, 0.0, 0.0)
        val c = TiltCompass()
        feed(c, r, field = doubleArrayOf(0.0, 90.0, -190.0))
        assertFalse(c.valid)
        assertTrue(c.axisHeading(1).isNaN())
    }

    @Test fun rejectsAFieldAlignedWithGravity() {
        val r = Attitude.rot(30 * DEG, 0.0, 0.0)
        val c = TiltCompass()
        feed(c, r, field = doubleArrayOf(0.0, 1.0, -48.0))
        assertFalse(c.valid)
    }

    @Test fun rejectsADisturbedField() {
        val r = Attitude.rot(30 * DEG, 0.0, 0.0)
        val c = TiltCompass()
        feed(c, r)
        assertTrue(c.valid)
        // walking past a steel girder: the strength swings by tens of µT
        val m = Attitude.toDevice(r, FIELD)
        var t = 10_000_000_000L
        for (i in 0 until 100) {
            val s = if (i % 20 < 10) 1.0 else 1.5
            c.onMagnetic(t, (m[0] * s).toFloat(), (m[1] * s).toFloat(), (m[2] * s).toFloat())
            t += 20_000_000L
        }
        assertFalse("a swinging field must not be trusted", c.valid)
        assertEquals("field disturbed", c.status)
    }

    @Test fun unreliableAccuracyDisqualifiesIt() {
        val r = Attitude.rot(30 * DEG, 0.0, 0.0)
        val c = TiltCompass()
        feed(c, r)
        assertTrue(c.valid)
        c.onAccuracy(TiltCompass.ACCURACY_UNRELIABLE)
        feed(c, r, seconds = 0.1)
        assertFalse(c.valid)
    }

    companion object {
        /** 48 µT at 64° dip, as in western Europe: north and down in the world frame. */
        private val FIELD = doubleArrayOf(0.0, 48 * cos(64 * DEG), -48 * sin(64 * DEG))
    }
}

/** 3×3 rotation helpers shared by the heading tests. */
object Attitude {
    /** Device → world (ENU) rotation matrix, row-major, from yaw/pitch/roll in radians. */
    fun rot(yaw: Double, pitch: Double, roll: Double): DoubleArray {
        val cz = cos(yaw); val sz = sin(yaw)
        val cy = cos(pitch); val sy = sin(pitch)
        val cx = cos(roll); val sx = sin(roll)
        val z = doubleArrayOf(cz, -sz, 0.0, sz, cz, 0.0, 0.0, 0.0, 1.0)
        val y = doubleArrayOf(cy, 0.0, sy, 0.0, 1.0, 0.0, -sy, 0.0, cy)
        val x = doubleArrayOf(1.0, 0.0, 0.0, 0.0, cx, -sx, 0.0, sx, cx)
        return mul(mul(z, y), x)
    }

    fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val o = DoubleArray(9)
        for (i in 0 until 3) for (j in 0 until 3) {
            var s = 0.0
            for (k in 0 until 3) s += a[i * 3 + k] * b[k * 3 + j]
            o[i * 3 + j] = s
        }
        return o
    }

    /** World → device: the transpose, which is what a sensor in the device reads. */
    fun toDevice(r: DoubleArray, w: DoubleArray) = doubleArrayOf(
        r[0] * w[0] + r[3] * w[1] + r[6] * w[2],
        r[1] * w[0] + r[4] * w[1] + r[7] * w[2],
        r[2] * w[0] + r[5] * w[1] + r[8] * w[2],
    )

    /** Quaternion (x, y, z, w) of a rotation matrix, in rotation-vector sensor order. */
    fun quat(r: DoubleArray): FloatArray {
        val tr = r[0] + r[4] + r[8]
        val x: Double; val y: Double; val z: Double; val w: Double
        if (tr > 0) {
            val s = kotlin.math.sqrt(tr + 1.0) * 2
            w = 0.25 * s; x = (r[7] - r[5]) / s; y = (r[2] - r[6]) / s; z = (r[3] - r[1]) / s
        } else if (r[0] > r[4] && r[0] > r[8]) {
            val s = kotlin.math.sqrt(1.0 + r[0] - r[4] - r[8]) * 2
            w = (r[7] - r[5]) / s; x = 0.25 * s; y = (r[1] + r[3]) / s; z = (r[2] + r[6]) / s
        } else if (r[4] > r[8]) {
            val s = kotlin.math.sqrt(1.0 + r[4] - r[0] - r[8]) * 2
            w = (r[2] - r[6]) / s; x = (r[1] + r[3]) / s; y = 0.25 * s; z = (r[5] + r[7]) / s
        } else {
            val s = kotlin.math.sqrt(1.0 + r[8] - r[0] - r[4]) * 2
            w = (r[3] - r[1]) / s; x = (r[2] + r[6]) / s; y = (r[5] + r[7]) / s; z = 0.25 * s
        }
        return floatArrayOf(x.toFloat(), y.toFloat(), z.toFloat(), w.toFloat())
    }

    /** Attitude of a phone held on the chest, screen facing out, walking towards `phi`. */
    fun chestMount(phi: Double) = doubleArrayOf(
        -sin(phi), 0.0, cos(phi),
        cos(phi), 0.0, sin(phi),
        0.0, 1.0, 0.0,
    )
}
