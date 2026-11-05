package org.spdr.companion.core.filter

import org.spdr.companion.core.DEG

/** Tunables of the graph particle filter. Defaults follow the spec (§9). */
data class FilterConfig(
    val particles: Int = 1000,
    /** Relative step length noise. */
    val sigmaL: Double = 0.10,
    /** Random walk of the step scale k, per step. */
    val sigmaK: Double = 0.002,
    /** Random walk of the heading offset θ, per step (rad). */
    val sigmaTheta: Double = 0.5 * DEG,
    /** Random walk of the residual gyro bias, per step (rad/s). */
    val sigmaBias: Double = 0.005 * DEG,
    /** Initial spread of the residual gyro bias (rad/s). */
    val sigmaBias0: Double = 0.2 * DEG,
    /** Walking heading vs gallery direction (rad). */
    val sigmaPsi: Double = 20 * DEG,
    /** Uniform floor added to the heading likelihood so one bad heading can't wipe everything. */
    val headingFloor: Double = 0.05,
    /** Branch choice sharpness at junctions (rad). */
    val sigmaBranch: Double = 30 * DEG,
    /** Probability a particle considers a U-turn when the reverse direction fits much better. */
    val uTurnProb: Double = 0.1,
    /** Weight multiplier when a particle bounces off a dead end. */
    val deadEndPenalty: Double = 0.3,
    /** Barometric height vs gallery height (m). Only used when the graph has real z. */
    val sigmaZ: Double = 1.5,
    val useBarometer: Boolean = true,
    /** Magnetic heading offset measurement (rad), robust. NaN measurements are skipped. */
    val sigmaCompass: Double = 35 * DEG,
    val compassFloor: Double = 0.3,
    val useCompass: Boolean = true,
    /** Manual reset position uncertainty (m). */
    val sigmaReset: Double = 3.0,
    /** Resample when N_eff < ratio·N. */
    val resampleRatio: Double = 0.5,
    /** Roughening noise after resampling. */
    val roughK: Double = 0.003,
    val roughTheta: Double = 0.5 * DEG,
    val roughBias: Double = 0.01 * DEG,
    /** Particles closer than this (m) belong to the same position hypothesis. */
    val clusterRadius: Double = 12.0,
    val kMin: Double = 0.5,
    val kMax: Double = 1.7,

    // ---- reported position continuity -------------------------------------------------------
    /**
     * A walker cannot teleport, so a hypothesis further away than they could have walked since
     * the last report is only reported once it outweighs the nearby one by 1/this.
     */
    val jumpPenalty: Double = 0.15,
    /** Slack on how far the reported position may move between two reports (m). */
    val reachFloor: Double = 4.0,
    /** How much further than the distance actually walked the report may move. */
    val reachMargin: Double = 2.0,

    // ---- multi-step alignment ---------------------------------------------------------------
    /**
     * Off by default, and measured rather than assumed. The displacement over several steps is far
     * less noisy than one step and must line up with the straight line covered on the graph, which
     * sounds like a strong extra constraint — but a product of per-step heading likelihoods along a
     * path *already is* that constraint, and when the per-step errors are independent it is a
     * sufficient statistic for it. So an explicit chord term re-uses the same measurements with a
     * tighter sigma and over-tightens. Measured over seeded runs: mean error 3.1 → 3.5 m and worst
     * 5 → 10 m on a dense grid, neutral on an irregular network and on a shallow Y at 8° and 20°,
     * better only at a Y of 12° (22/24 → 24/24 correct branch, 3.3 → 1.9 m).
     *
     * Kept because the stall penalty below is genuinely new information — path shape, not
     * direction — and because real heading errors are correlated in ways the simulator does not
     * model. Turn it on to try it against a recording; do not turn it on blind.
     */
    val useChord: Boolean = false,
    /** Metres of walking that make up one chord. */
    val chordWindow: Double = 10.0,
    /** Only use a chord if the walk over it was at least this straight (|Σ| / Σ| |). */
    val chordStraightness: Double = 0.75,
    /** Chord direction vs the straight line covered on the graph (rad). */
    val sigmaChord: Double = 12 * DEG,
    /** Robust floor on the chord likelihood. */
    val chordFloor: Double = 0.1,
    /** A particle that covered less than this share of the expected chord barely moved at all. */
    val chordStallRatio: Double = 0.4,
)
