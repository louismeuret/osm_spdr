package org.spdr.companion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.spdr.companion.core.pdr.StepDetector
import kotlin.math.PI
import kotlin.math.sin

/** The step length model: cadence and amplitude, blended (spec §5.3, extended). */
class StepLengthTest {
    private val d = StepDetector()
    private val nominalDt = 1 / 1.9

    @Test fun aNominalStepIsANominalLength() {
        assertEquals(0.70, d.rawLength(nominalDt, 3.0), 1e-9)
    }

    @Test fun quickeningYourStepLengthensIt() {
        assertTrue(d.rawLength(1 / 2.3, 3.0) > d.rawLength(nominalDt, 3.0))
        assertTrue(d.rawLength(1 / 1.6, 3.0) < d.rawLength(nominalDt, 3.0))
    }

    @Test fun walkingHarderLengthensIt() {
        assertTrue(d.rawLength(nominalDt, 5.0) > d.rawLength(nominalDt, 3.0))
        assertTrue(d.rawLength(nominalDt, 1.5) < d.rawLength(nominalDt, 3.0))
    }

    /** Neither feature on its own may swing the answer as far as it would alone. */
    @Test fun blendingHalvesTheDamageFromEitherFeatureGoingWrong() {
        // a missed step halves the apparent cadence; pure cadence would halve the length
        val missed = d.rawLength(2 * nominalDt, 3.0) / d.rawLength(nominalDt, 3.0)
        assertTrue("missed step scaled length by $missed", missed > 0.70 && missed < 0.85)
        // a phone that shifts in its pouch changes amplitude but not cadence
        val shifted = d.rawLength(nominalDt, 1.0) / d.rawLength(nominalDt, 3.0)
        assertTrue("amplitude drop scaled length by $shifted", shifted > 0.70 && shifted < 0.95)
    }

    @Test fun cadenceIsClampedToWalkingSpeeds() {
        // a two-second gap is a pause, not a stride: the cadence term stops believing it
        assertEquals(d.rawLength(1.0, 3.0), d.rawLength(2.0, 3.0), 1e-12)
        assertEquals(d.rawLength(1 / 2.6, 3.0), d.rawLength(0.3, 3.0), 1e-12)
    }

    @Test fun theWeightSelectsBetweenTheTwoModels() {
        d.frequencyWeight = 0.0
        val amplitudeOnly = d.rawLength(1 / 2.3, 3.0)
        assertEquals("amplitude-only must ignore cadence", amplitudeOnly, d.rawLength(1 / 1.6, 3.0), 1e-12)
        d.frequencyWeight = 1.0
        val cadenceOnly = d.rawLength(nominalDt, 9.0)
        assertEquals("cadence-only must ignore amplitude", cadenceOnly, d.rawLength(nominalDt, 1.0), 1e-12)
    }

    /** End to end through the peak detector, on a clean 1.9 Hz walk. */
    @Test fun detectsStepsAndGivesPlausibleLengths() {
        val det = StepDetector()
        val lengths = ArrayList<Double>()
        var t = 0L
        val seconds = 12.0
        for (i in 0 until (seconds / 0.01).toInt()) {
            val a = 9.81 + 2.0 * sin(2 * PI * 1.9 * (t / 1e9))
            det.onAccel(t, a.toFloat(), 0f, 0f)?.let { lengths.add(it.length) }
            t += 10_000_000L
        }
        val expected = (seconds * 1.9).toInt()
        assertTrue("got ${lengths.size} steps, expected about $expected",
            lengths.size in (expected - 3)..(expected + 1))
        val mean = lengths.drop(1).average()
        assertTrue("mean step length $mean m is not a plausible walk", mean in 0.45..0.95)
    }
}
