package org.spdr.companion.core.pdr

import org.spdr.companion.core.wrap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Walking direction recovered from one PCA window. */
data class WalkDir(
    /** Walking direction, sign resolved, in the frame of the accelerations (rad, CCW from +x). */
    val heading: Double,
    /**
     * The principal axis before the 180° ambiguity was resolved: [heading] is either this or this
     * turned round. Logged together with [verticalCorr], which is relative to it, so that a real
     * recording can be checked against the convention offline.
     */
    val axis: Double,
    /** λ1/λ2 of the horizontal covariance: how elongated, so how well defined, the axis is. */
    val elongation: Double,
    /** Correlation of the vertical acceleration with the acceleration along [axis]. */
    val verticalCorr: Double,
    val samples: Int,
)

/**
 * Walking direction from the horizontal accelerations of the last couple of steps, by PCA —
 * the direction the body moves in, which is what the map constrains, as opposed to the direction
 * the phone happens to be pointing. This is what makes a hand-held phone usable: the phone can be
 * turned, read, pocketed, and the walking axis is still recovered from the gait itself.
 *
 * At each step the body accelerates forward then backward, while the sideways accelerations are
 * much smaller, so over one or two steps the horizontal accelerations form an elongated cloud
 * whose principal axis is the walking axis. [elongation] says how elongated it is; a round cloud
 * (standing, fidgeting, an escalator) carries no direction and yields nothing.
 *
 * The axis is only known modulo 180°. The ambiguity is resolved by the phase relation with the
 * vertical acceleration: at heel strike the foot lands ahead of the body, so the ground pushes it
 * up and backwards at the same instant — the sharpest event of the step cycle — which makes
 * vertical and forward acceleration anti-correlated. So the forward direction is the one whose
 * correlation with the vertical is negative (Kourogi & Kurata; Khedr & El-Sheimy). Weak or
 * contradictory evidence falls back on continuity with the previous step, and a reversal has to
 * show up in [FLIP_VOTES] consecutive windows before it is accepted, so that a genuine U-turn is
 * still caught while single-step noise is not.
 *
 * Accuracy is roughly 10–30° per step, which is why it feeds a filter that averages it against
 * the map over many steps rather than being used on its own.
 *
 * Accelerations must be supplied in a frame whose z is up and whose horizontal axes are steady:
 * the game-rotation-vector world frame (gyro + accelerometer, no magnetometer) or, failing that,
 * the tilt compass frame. Gravity need not be removed — the window mean is subtracted anyway.
 */
class WalkingPca(
    /** Length of the PCA window (s): long enough for one full step cycle, short enough to turn. */
    var window: Double = 1.6,
    capacity: Int = 2048,
) {
    /**
     * The convention above: forward is the direction anti-correlated with the vertical. If a
     * recording shows the opposite phase relation for a given walker or carry position, flip this
     * rather than the code — `pca_axis` and `pca_corr` in `steps.csv` are what to check it against.
     */
    var forwardAntiCorrelatesWithVertical = true

    private val t = LongArray(capacity)
    private val ax = DoubleArray(capacity)
    private val ay = DoubleArray(capacity)
    private val au = DoubleArray(capacity)
    private val cap = capacity
    private var head = 0
    private var count = 0
    private var last = Double.NaN
    private var flipVotes = 0

    /** Last accepted walking direction, NaN if none yet. */
    val current get() = last

    fun add(tNs: Long, e: Double, n: Double, u: Double) {
        t[head] = tNs; ax[head] = e; ay[head] = n; au[head] = u
        head = (head + 1) % cap
        if (count < cap) count++
    }

    fun reset() { count = 0; head = 0; last = Double.NaN; flipVotes = 0 }

    /**
     * Mean acceleration perpendicular to `heading` over a time span, positive to the left: the
     * body's sway. It runs at the stride frequency, half the step frequency, because you sway
     * towards whichever leg is carrying you — so averaged over a single step it is one sign for a
     * left step and the other for a right one, which is how the foot is told apart. NaN if the
     * span holds too little data.
     */
    fun lateralMean(fromNs: Long, toNs: Long, heading: Double): Double {
        if (heading.isNaN()) return Double.NaN
        val lx = -sin(heading); val ly = cos(heading)
        var sum = 0.0
        var n = 0
        for (q in 0 until count) {
            val i = (head - 1 - q + cap * 2) % cap
            if (t[i] > toNs) continue
            if (t[i] < fromNs) break
            sum += ax[i] * lx + ay[i] * ly
            n++
        }
        return if (n < MIN_LATERAL_SAMPLES) Double.NaN else sum / n
    }

    /**
     * Solves the window ending at `nowNs`. Returns null when the window is too short, the cloud
     * too round or too quiet, or the direction still ambiguous with nothing to fall back on.
     */
    fun solve(nowNs: Long): WalkDir? {
        val from = nowNs - (window * 1e9).toLong()
        var n = 0
        var sx = 0.0; var sy = 0.0; var su = 0.0
        var oldest = Long.MAX_VALUE
        for (q in 0 until count) {
            val i = (head - 1 - q + cap * 2) % cap
            if (t[i] < from) break
            sx += ax[i]; sy += ay[i]; su += au[i]
            oldest = t[i]
            n++
        }
        if (n < MIN_SAMPLES || (nowNs - oldest) / 1e9 < MIN_SPAN) return null
        val mx = sx / n; val my = sy / n; val mu = su / n

        var sxx = 0.0; var sxy = 0.0; var syy = 0.0
        for (q in 0 until n) {
            val i = (head - 1 - q + cap * 2) % cap
            val dx = ax[i] - mx; val dy = ay[i] - my
            sxx += dx * dx; sxy += dx * dy; syy += dy * dy
        }
        sxx /= n; sxy /= n; syy /= n

        // principal axis of the 2×2 horizontal covariance
        val tr = sxx + syy
        val disc = sqrt(max(0.0, tr * tr / 4 - (sxx * syy - sxy * sxy)))
        val l1 = tr / 2 + disc
        val l2 = (tr / 2 - disc).coerceAtLeast(1e-9)
        if (l1 < MIN_VARIANCE) return null
        val elong = l1 / l2
        if (elong < MIN_ELONGATION) return null
        // eigenvector of λ1, taken from whichever row of (A − λ₁I) is better conditioned
        val axis = if (abs(l1 - syy) >= abs(l1 - sxx)) atan2(sxy, l1 - syy) else atan2(l1 - sxx, sxy)

        // phase relation with the vertical, on the same window
        val cs = kotlin.math.cos(axis); val sn = kotlin.math.sin(axis)
        var sff = 0.0; var suu = 0.0; var sfu = 0.0
        for (q in 0 until n) {
            val i = (head - 1 - q + cap * 2) % cap
            val f = (ax[i] - mx) * cs + (ay[i] - my) * sn
            val v = au[i] - mu
            sff += f * f; suu += v * v; sfu += f * v
        }
        val corr = if (sff > 1e-12 && suu > 1e-12) sfu / sqrt(sff * suu) else 0.0

        val byCorr = if ((corr <= 0) == forwardAntiCorrelatesWithVertical) axis else wrap(axis + PI)
        val byContinuity = if (last.isNaN()) Double.NaN
        else if (abs(wrap(axis - last)) <= PI / 2) axis else wrap(axis + PI)

        val heading: Double
        if (abs(corr) < MIN_CORR) {
            // the gait says nothing about which way along the axis: keep going the same way
            if (byContinuity.isNaN()) return null
            heading = byContinuity
            flipVotes = 0
        } else if (!byContinuity.isNaN() && abs(wrap(byCorr - byContinuity)) > PI / 2) {
            // the gait says we reversed: believe it only once it has said so a few windows running
            if (++flipVotes >= FLIP_VOTES) { heading = byCorr; flipVotes = 0 } else heading = byContinuity
        } else {
            heading = byCorr
            flipVotes = 0
        }
        last = heading
        return WalkDir(heading, axis, elong, corr, n)
    }

    companion object {
        private const val MIN_SAMPLES = 40
        /** The window must really span a step cycle, not just the tail of one (s). */
        private const val MIN_SPAN = 0.6
        /** Horizontal variance below this (m²/s⁴) is not walking. */
        private const val MIN_VARIANCE = 0.25
        private const val MIN_ELONGATION = 1.6
        private const val MIN_CORR = 0.12
        private const val FLIP_VOTES = 2
        private const val MIN_LATERAL_SAMPLES = 10
    }
}
