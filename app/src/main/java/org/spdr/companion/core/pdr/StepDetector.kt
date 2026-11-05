package org.spdr.companion.core.pdr

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

data class Step(
    val tNs: Long,
    /** Peak-to-trough of the filtered acceleration norm over the step (m/s²). */
    val amplitude: Double,
    /** Step length before the per-particle scale `k` (m). */
    val length: Double,
    /** The same before the per-user scale too, which is what the known-distance walk fits. */
    val raw: Double,
    /** Time since the previous step, capped (s). */
    val dt: Double,
)

/**
 * Peak detector on the low-passed, gravity-free accelerometer norm (spec §5.2), with a step
 * length from both cadence and amplitude.
 *
 * The spec's Weinberg model, `L = K·(a_max − a_min)^¼`, uses amplitude alone. That holds up
 * poorly across walking speeds, and badly when the phone swings in a hand or rides loose in a
 * pouch, because then the amplitude says as much about the phone as about the stride. Cadence is
 * the other half of the story — people lengthen their step as they quicken it — and it is nearly
 * free here, since the step interval is already measured, and it is immune to how the phone is
 * carried. So the two are blended (Ladetto; Shin et al.), each normalised so that a nominal step
 * gives a nominal length:
 *
 *     L = scale · ( w·L₀·(f/f₀) + (1−w)·L₀·(amp/amp₀)^¼ )
 *
 * `scale` is the per-user, per-mounting number the known-distance walk fits, and the filter's
 * per-particle `k` refines it further from the resets. Blending also contains the failure modes:
 * a missed step halves the apparent cadence but leaves the amplitude alone, while a phone that
 * shifts in its pouch changes the amplitude but not the cadence, so either way only half the
 * estimate moves.
 */
class StepDetector(var stepScale: Double = DEFAULT_SCALE) {
    /** Share of the step length taken from cadence rather than from amplitude. */
    var frequencyWeight = 0.5
    private var lastT = 0L
    private var gravity = 9.81
    private var f1 = 0.0
    private var f2 = 0.0
    private var prev = 0.0
    private var prev2 = 0.0
    private var prevT = 0L
    private var trough = 0.0
    private var lastStepT = 0L
    private var peakAvg = 2.0

    fun onAccel(tNs: Long, ax: Float, ay: Float, az: Float): Step? {
        val norm = sqrt((ax * ax + ay * ay + az * az).toDouble())
        if (lastT == 0L) {
            lastT = tNs; gravity = norm
            return null
        }
        val dt = ((tNs - lastT) / 1e9).coerceIn(1e-4, 0.1)
        lastT = tNs
        gravity += dt / (GRAVITY_TAU + dt) * (norm - gravity)
        val a = dt / (LP_TAU + dt)
        f1 += a * (norm - gravity - f1)
        f2 += a * (f1 - f2)

        var step: Step? = null
        val isPeak = prev > prev2 && prev >= f2
        if (isPeak) {
            val since = (prevT - lastStepT) / 1e9
            val amp = prev - trough
            val threshold = max(MIN_PEAK, PEAK_FRACTION * peakAvg)
            if (prev > threshold && amp > MIN_AMPLITUDE && (lastStepT == 0L || since >= MIN_STEP_INTERVAL)) {
                peakAvg += 0.2 * (prev - peakAvg)
                val interval = if (lastStepT == 0L) MAX_STEP_INTERVAL else since.coerceAtMost(MAX_STEP_INTERVAL)
                val raw = rawLength(interval, amp)
                step = Step(prevT, amp, (stepScale * raw).coerceIn(0.15, 1.3), raw, interval)
                lastStepT = prevT
                trough = prev
            }
        }
        if (f2 < trough) trough = f2
        // let the trough recover slowly so one deep dip doesn't inflate later amplitudes
        trough += 0.002 * (f2 - trough)
        prev2 = prev; prev = f2; prevT = tNs
        return step
    }

    /**
     * Step length before the per-user scale. Cadence is clamped to a plausible walking range:
     * outside it the interval is telling us about a pause or a missed step, not about the stride.
     */
    fun rawLength(dt: Double, amplitude: Double): Double {
        val f = (1.0 / dt).coerceIn(MIN_FREQ, MAX_FREQ)
        val cadence = NOMINAL_LENGTH * f / NOMINAL_FREQ
        val swing = NOMINAL_LENGTH * (amplitude / NOMINAL_AMP).coerceAtLeast(0.0).pow(0.25)
        return frequencyWeight * cadence + (1 - frequencyWeight) * swing
    }

    companion object {
        /** Dimensionless now: 1.0 means an average adult, which is a usable starting point. */
        const val DEFAULT_SCALE = 1.0
        /** The nominal step the two models are pinned to: an average adult walking normally. */
        private const val NOMINAL_LENGTH = 0.70
        private const val NOMINAL_FREQ = 1.9
        private const val NOMINAL_AMP = 3.0
        private const val MIN_FREQ = 1.0
        private const val MAX_FREQ = 2.6
        private const val GRAVITY_TAU = 2.0
        private const val LP_TAU = 0.053 // two cascaded 1st-order stages ≈ 3 Hz
        private const val MIN_PEAK = 0.6
        private const val PEAK_FRACTION = 0.35
        private const val MIN_AMPLITUDE = 1.0
        private const val MIN_STEP_INTERVAL = 0.3
        private const val MAX_STEP_INTERVAL = 2.0
    }
}
