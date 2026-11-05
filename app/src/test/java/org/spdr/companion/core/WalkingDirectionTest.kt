package org.spdr.companion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.spdr.companion.core.pdr.HeadingTracker
import org.spdr.companion.core.pdr.Mounting
import org.spdr.companion.core.pdr.StepHeading
import org.spdr.companion.core.pdr.WalkingPca
import org.spdr.companion.core.pdr.quatToMatrix
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * A step cycle, as body acceleration in the world frame.
 *
 * Heel strike is the sharp event: the foot lands ahead of the body, so the ground pushes it up
 * hard and backwards at the same time. Push-off, half a cycle later, pushes up and forwards but
 * more gently and over longer. Sideways sway runs at the stride frequency, half the step
 * frequency, and is much smaller than the fore-aft swing.
 *
 * The tests below pin the conventions of the gait analysis against this model. The model encodes
 * the heel-strike argument that the sign resolution rests on; whether a given walker's phone
 * really shows it is a question for a recorded walk, which is why the per-step PCA diagnostics go
 * into `steps.csv`.
 */
object Gait {
    fun world(t: Double, phi: Double, period: Double = 0.55): DoubleArray {
        val p = (t / period) % 1.0
        val af = -2.0 * bump(p, 0.03, 0.10) + 1.2 * bump(p, 0.55, 0.18)
        val au = 4.0 * bump(p, 0.0, 0.08) + 1.5 * bump(p, 0.5, 0.15) - 1.0
        val al = 0.45 * sin(Math.PI * t / period)
        return doubleArrayOf(af * cos(phi) - al * sin(phi), af * sin(phi) + al * cos(phi), au)
    }

    private fun bump(p: Double, centre: Double, width: Double): Double {
        val d = wrap((p - centre) * TWO_PI) / TWO_PI
        return exp(-d * d / (2 * width * width))
    }
}

class WalkingPcaTest {
    private fun walk(pca: WalkingPca, phi: Double, seconds: Double, t0: Long = 1_000_000_000L): Long {
        var t = t0
        val n = (seconds / 0.01).toInt()
        for (i in 0 until n) {
            val a = Gait.world((t - 1_000_000_000L) / 1e9, phi)
            pca.add(t, a[0], a[1], 9.81 + a[2])
            t += 10_000_000L
        }
        return t - 10_000_000L
    }

    @Test fun findsTheWalkingDirection() {
        for (deg in listOf(0, 50, 140, -120, 179)) {
            val phi = deg * DEG
            val pca = WalkingPca()
            val t = walk(pca, phi, 2.5)
            val d = pca.solve(t)
            assertNotNull("no direction at $deg°", d)
            assertEquals("direction at $deg°", 0.0, wrap(d!!.heading - phi) / DEG, 12.0)
            assertTrue("axis should be elongated, was ${d.elongation}", d.elongation > 3.0)
            // the correlation is reported against the ambiguous axis: taken against the real
            // walking direction it must be negative, which is the convention the sign rests on
            val towardsPhi = abs(wrap(d.axis - phi)) < Math.PI / 2
            val corrForward = if (towardsPhi) d.verticalCorr else -d.verticalCorr
            assertTrue("forward/vertical correlation was $corrForward at $deg°", corrForward < -0.2)
        }
    }

    /** The axis alone is 180°-ambiguous: walking the opposite way must not look the same. */
    @Test fun resolvesTheForwardBackwardAmbiguity() {
        val forward = WalkingPca().let { it.solve(walk(it, 50 * DEG, 2.5))!! }
        val backward = WalkingPca().let { it.solve(walk(it, -130 * DEG, 2.5))!! }
        assertEquals(0.0, wrap(forward.heading - 50 * DEG) / DEG, 12.0)
        assertEquals(0.0, wrap(backward.heading + 130 * DEG) / DEG, 12.0)
        assertTrue(abs(wrap(forward.heading - backward.heading)) / DEG > 150)
    }

    @Test fun standingStillGivesNothing() {
        val pca = WalkingPca()
        var t = 1_000_000_000L
        for (i in 0 until 300) {
            pca.add(t, 0.02 * sin(i * 0.3), 0.02 * cos(i * 0.2), 9.81)
            t += 10_000_000L
        }
        assertNull(pca.solve(t))
    }

    @Test fun anEmptyWindowGivesNothing() {
        val pca = WalkingPca()
        val t = walk(pca, 0.0, 2.5)
        assertNotNull(pca.solve(t))
        // nothing added for five seconds: the window has run dry
        assertNull(pca.solve(t + 5_000_000_000L))
    }

    @Test fun followsAUTurn() {
        val pca = WalkingPca()
        var t = walk(pca, 20 * DEG, 2.5)
        assertEquals(0.0, wrap(pca.solve(t)!!.heading - 20 * DEG) / DEG, 12.0)
        // turn round and keep walking; the reversal has to survive the confirmation votes
        var resolved = Double.NaN
        for (round in 0 until 4) {
            t = walk(pca, -160 * DEG, 0.8, t + 10_000_000L)
            resolved = pca.solve(t)!!.heading
        }
        assertEquals("should have turned round", 0.0, wrap(resolved + 160 * DEG) / DEG, 15.0)
    }
}

/** The whole heading front end, from raw sensor samples to what the filter is handed. */
class HeadingTrackerTest {
    private val field = doubleArrayOf(0.0, 48 * cos(64 * DEG), -48 * sin(64 * DEG))

    /**
     * Plays a walk through the tracker.
     * @param att device attitude (device → ENU), constant: the phone is carried, not waved.
     * @param gameYaw yaw of the game rotation vector's world frame relative to ENU; NaN to
     * simulate a device without a game rotation vector.
     */
    private fun run(
        tracker: HeadingTracker, att: DoubleArray, phi: Double, gameYaw: Double, seconds: Double,
    ): Long {
        val g = Attitude.toDevice(att, doubleArrayOf(0.0, 0.0, 9.81))
        val m = Attitude.toDevice(att, field)
        val gameQ = if (gameYaw.isNaN()) null
        else Attitude.quat(Attitude.mul(Attitude.rot(-gameYaw, 0.0, 0.0), att))
        var t = 1_000_000_000L
        val n = (seconds / 0.01).toInt()
        for (i in 0 until n) {
            tracker.onGravity(t, floatArrayOf(g[0].toFloat(), g[1].toFloat(), g[2].toFloat()))
            tracker.onMagnetic(t, floatArrayOf(m[0].toFloat(), m[1].toFloat(), m[2].toFloat()))
            gameQ?.let { tracker.onGameRotation(t, it) }
            val a = Gait.world((t - 1_000_000_000L) / 1e9, phi)
            val dev = Attitude.toDevice(att, doubleArrayOf(a[0], a[1], a[2] + 9.81))
            tracker.onAccel(t, floatArrayOf(dev[0].toFloat(), dev[1].toFloat(), dev[2].toFloat()))
            t += 10_000_000L
        }
        return t - 10_000_000L
    }

    @Test fun handHeldRecoversAnAbsoluteWalkingHeading() {
        // phone held at a random angle, no game rotation vector: everything rides on the compass
        val att = Attitude.rot(-70 * DEG, 25 * DEG, 100 * DEG)
        val tracker = HeadingTracker(Mounting.HANDHELD)
        val t = run(tracker, att, 110 * DEG, Double.NaN, 6.0)
        val h = tracker.takeStepWindow(t, STEP_DT)
        assertEquals("walking heading", 0.0, wrap(h.psi - 110 * DEG) / DEG, 12.0)
        assertEquals("the compass frame is the map frame, so no offset", 0.0, h.offset / DEG, 1.0)
    }

    @Test fun handHeldMeasuresTheGameFrameOffset() {
        val att = Attitude.rot(15 * DEG, -10 * DEG, 80 * DEG)
        val tracker = HeadingTracker(Mounting.HANDHELD)
        val t = run(tracker, att, 110 * DEG, gameYaw = 40 * DEG, seconds = 6.0)
        val h = tracker.takeStepWindow(t, STEP_DT)
        assertEquals("heading in the game frame", 0.0, wrap(h.psi - 70 * DEG) / DEG, 12.0)
        assertEquals("offset of the game frame to the map", 0.0, wrap(h.offset - 40 * DEG) / DEG, 2.0)
    }

    @Test fun aRigidMountingUsesItsFixedAxis() {
        val phi = 110 * DEG
        val tracker = HeadingTracker(Mounting.CHEST_SCREEN_OUT)
        val t = run(tracker, Attitude.chestMount(phi), phi, gameYaw = 40 * DEG, seconds = 4.0)
        val h = tracker.takeStepWindow(t, STEP_DT)
        assertEquals(0.0, wrap(h.psi - (phi - 40 * DEG)) / DEG, 2.0)
        assertEquals(0.0, wrap(h.offset - 40 * DEG) / DEG, 2.0)
    }

    @Test fun declinationIsTakenOutOfTheOffset() {
        val phi = 110 * DEG
        val tracker = HeadingTracker(Mounting.CHEST_SCREEN_OUT, declination = 3 * DEG)
        val t = run(tracker, Attitude.chestMount(phi), phi, gameYaw = 40 * DEG, seconds = 4.0)
        assertEquals(0.0, wrap(tracker.takeStepWindow(t, STEP_DT).offset - 37 * DEG) / DEG, 2.0)
    }

    /**
     * Iron takes the compass away but not the gyro frame. The detector is a mean deviation over a
     * couple of seconds, so it closes a few steps after the field starts swinging, which the weak
     * compass likelihood in the filter is sized to absorb.
     */
    @Test fun aDisturbedFieldWithdrawsTheOffsetButNotTheHeading() {
        val phi = 110 * DEG
        val tracker = HeadingTracker(Mounting.CHEST_SCREEN_OUT)
        val att = Attitude.chestMount(phi)
        run(tracker, att, phi, gameYaw = 40 * DEG, seconds = 4.0)
        var t = disturb(tracker, att, 10_000_000_000L, 3.0)
        tracker.takeStepWindow(t, STEP_DT) // the step during which the field went bad
        t = disturb(tracker, att, t + 10_000_000L, 1.0)
        val h = tracker.takeStepWindow(t, STEP_DT)
        assertEquals("the gyro frame still knows where we point", 0.0, wrap(h.psi - (phi - 40 * DEG)) / DEG, 2.0)
        assertTrue("a disturbed field must not be offered as a measurement", h.offset.isNaN())
        assertTrue(tracker.magOffset(t).isNaN())
    }

    companion object {
        /** The gait model's step period, which is also the step interval the tracker is told. */
        private const val STEP_DT = 0.55
    }

    /** Walking past steel: the field strength swings by tens of µT every tenth of a second. */
    private fun disturb(tracker: HeadingTracker, att: DoubleArray, t0: Long, seconds: Double): Long {
        val g = Attitude.toDevice(att, doubleArrayOf(0.0, 0.0, 9.81))
        val gameQ = Attitude.quat(Attitude.mul(Attitude.rot(-40 * DEG, 0.0, 0.0), att))
        var t = t0
        for (i in 0 until (seconds / 0.01).toInt()) {
            val k = if (i % 20 < 10) 1.0 else 1.6
            val m = Attitude.toDevice(att, doubleArrayOf(field[0] * k, field[1] * k, field[2] * k))
            tracker.onGravity(t, floatArrayOf(g[0].toFloat(), g[1].toFloat(), g[2].toFloat()))
            tracker.onMagnetic(t, floatArrayOf(m[0].toFloat(), m[1].toFloat(), m[2].toFloat()))
            tracker.onGameRotation(t, gameQ)
            t += 10_000_000L
        }
        return t - 10_000_000L
    }

    /**
     * Walking rocks the torso left and right at the stride frequency, so a chest-mounted phone is
     * yawed a few degrees one way on a left step and the other way on a right step. Averaging the
     * pair cancels it; a single step cannot. Here the yaw swings ±8°, whose mean over one step is
     * ±2·8/π ≈ ±5.1°, and the two steps of a stride must cancel it to well under a degree.
     */
    @Test fun strideAveragingCancelsBodySway() {
        val phi = 110 * DEG
        val tracker = HeadingTracker(Mounting.CHEST_SCREEN_OUT)
        val steps = runSteps(tracker, Attitude.chestMount(phi), phi, yawSway = 8 * DEG, steps = 8)

        val paired = steps.filter { it.stride }
        assertTrue("most steps should pair into strides, ${paired.size}/${steps.size} did", paired.size >= 5)
        assertTrue("no step should look like a missed one here", steps.none { it.missedStep })

        val rawError = paired.map { abs(wrap(it.stepPsi - phi)) / DEG }.average()
        val strideError = paired.map { abs(wrap(it.psi - phi)) / DEG }.average()
        assertEquals("single steps should be off by the sway", 5.1, rawError, 1.0)
        assertTrue("stride average was $strideError°, single steps $rawError°", strideError < 1.0)
    }

    /** The sway, and so the foot, must alternate from one step to the next. */
    @Test fun theTwoFeetAreToldApartByTheSway() {
        val phi = 110 * DEG
        val tracker = HeadingTracker(Mounting.CHEST_SCREEN_OUT)
        val steps = runSteps(tracker, Attitude.chestMount(phi), phi, yawSway = 8 * DEG, steps = 8)
        val feet = steps.drop(1).map { it.foot }
        assertTrue("the sway should always be decisive, got $feet", feet.none { it == 0 })
        for (i in 1 until feet.size) assertEquals("feet must alternate: $feet", -feet[i - 1], feet[i])
    }

    /**
     * Plays `steps` whole step cycles and returns what the filter would have been handed for each.
     * @param yawSway amplitude of the torso's yaw oscillation, in phase with the sideways sway.
     */
    private fun runSteps(
        tracker: HeadingTracker, baseAtt: DoubleArray, phi: Double, yawSway: Double, steps: Int,
    ): List<StepHeading> {
        val out = ArrayList<StepHeading>()
        val period = 0.55
        var t = 1_000_000_000L
        val t0 = t
        for (k in 0 until steps) {
            val end = t0 + ((k + 1) * period * 1e9).toLong()
            while (t < end) {
                val time = (t - t0) / 1e9
                val att = Attitude.mul(Attitude.rot(yawSway * sin(Math.PI * time / period), 0.0, 0.0), baseAtt)
                val g = Attitude.toDevice(att, doubleArrayOf(0.0, 0.0, 9.81))
                val m = Attitude.toDevice(att, field)
                tracker.onGravity(t, floatArrayOf(g[0].toFloat(), g[1].toFloat(), g[2].toFloat()))
                tracker.onMagnetic(t, floatArrayOf(m[0].toFloat(), m[1].toFloat(), m[2].toFloat()))
                tracker.onGameRotation(t, Attitude.quat(att))
                val a = Gait.world(time, phi, period)
                val dev = Attitude.toDevice(att, doubleArrayOf(a[0], a[1], a[2] + 9.81))
                tracker.onAccel(t, floatArrayOf(dev[0].toFloat(), dev[1].toFloat(), dev[2].toFloat()))
                t += 10_000_000L
            }
            out.add(tracker.takeStepWindow(t - 10_000_000L, period))
        }
        return out
    }

    @Test fun quaternionAndMatrixAgree() {
        val r = Attitude.rot(37 * DEG, -22 * DEG, 155 * DEG)
        val back = DoubleArray(9)
        quatToMatrix(Attitude.quat(r), back)
        for (i in 0 until 9) assertEquals("element $i", r[i], back[i], 1e-6)
    }
}
