package org.spdr.companion.core

import org.junit.Assert.assertTrue
import org.junit.Test
import org.spdr.companion.core.filter.FilterConfig
import org.spdr.companion.core.filter.ParticleFilter
import org.spdr.companion.core.pdr.Step
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A walker cannot teleport, so neither may the reported position. The filter's *belief* must be
 * free to put weight anywhere — that is how it recovers from a wrong branch — but what is shown
 * has to stay somewhere the person could actually have walked to, until the distant hypothesis
 * really dominates.
 */
class ReportContinuityTest {
    private val seeds = listOf(1L, 2L, 3L, 4L, 5L, 6L)

    /** Walks normally, then lies about the heading so nothing on the map fits and it must re-localise. */
    private fun jumpsWhileLost(jumpPenalty: Double): List<Double> {
        val jumps = ArrayList<Double>()
        for (sd in seeds) {
            val g = SimWalk.irregular(sd)
            val (x0, y0) = SimWalk.local(g, 0.0, 0.0)
            val start = g.nearest(x0, y0)!!
            val pf = ParticleFilter(g, FilterConfig(particles = 1200, jumpPenalty = jumpPenalty), seed = sd)
            pf.init(x0, y0, 2.0, kMean = 1.0, kStd = 0.05, thetaMean = 0.4, thetaStd = 15 * DEG)
            val w = SimWalk.Walker(g, start.edge, start.s, 1, kTrue = 1.05, thetaTrue = 0.4, seed = sd + 50)
            repeat(120) { w.step(pf) }
            var t = 90_000_000_000L
            var px = pf.estimate().best!!.x
            var py = pf.estimate().best!!.y
            repeat(120) {
                t += 600_000_000L
                pf.step(Step(t, 2.0, 0.7, 0.7, 0.6), wrap(2.2 + 0.3 * sin(t / 1e9)))
                val b = pf.estimate().best!!
                jumps.add(hypot(b.x - px, b.y - py))
                px = b.x; py = b.y
            }
        }
        return jumps
    }

    /**
     * Measured over 6 seeded runs, 720 reports while deliberately lost: the 99th percentile jump
     * falls from 47 m to 8 m and the jumps over 10 m from 14 to 8.
     *
     * The single **worst** jump is deliberately not asserted on, and barely improves (97 → 85 m):
     * once the old position has no weight left the filter has to relocalise, and crawling the dot
     * there at walking pace would be a worse lie than moving it. What continuity buys is that
     * jumps become rare and deliberate instead of routine, and `holding` says when one is pending.
     */
    @Test fun theReportedPositionDoesNotTeleportWhileRelocalising() {
        val free = jumpsWhileLost(1.0).sorted()   // continuity off: report follows the heaviest cluster
        val held = jumpsWhileLost(0.15).sorted()  // continuity on
        fun pct(l: List<Double>, p: Double) = l[(p * (l.size - 1)).toInt()]
        val p99Free = pct(free, 0.99)
        val p99Held = pct(held, 0.99)
        val bigFree = free.count { it > 10 }
        val bigHeld = held.count { it > 10 }
        assertTrue("without continuity the report should jump badly, p99 was $p99Free m", p99Free > 30.0)
        assertTrue("p99 jump $p99Held m should be small with continuity", p99Held < 15.0)
        assertTrue("$bigHeld jumps over 10 m with continuity vs $bigFree without", bigHeld < bigFree)
        assertTrue("ordinary reporting must move at walking pace", pct(held, 0.5) < 2.0)
    }

    @Test fun aConfirmedFixIsAllowedToJump() {
        val g = SimWalk.irregular(1)
        val (x0, y0) = SimWalk.local(g, 0.0, 0.0)
        val start = g.nearest(x0, y0)!!
        val pf = ParticleFilter(g, FilterConfig(particles = 800), seed = 7)
        pf.init(x0, y0, 2.0, kMean = 1.0, kStd = 0.05, thetaMean = 0.4, thetaStd = 15 * DEG)
        val w = SimWalk.Walker(g, start.edge, start.s, 1, kTrue = 1.0, thetaTrue = 0.4, seed = 9)
        repeat(60) { w.step(pf) }
        // "I am here", a long way from the estimate: the one jump that is legitimate
        val (fx, fy) = SimWalk.local(g, 160.0, 160.0)
        pf.reset(fx, fy, 3.0)
        val b = pf.estimate().best!!
        assertTrue("a confirmed fix must be honoured, landed ${hypot(b.x - fx, b.y - fy)} m away",
            hypot(b.x - fx, b.y - fy) < 15.0)
    }
}

/** The chord constraint is off by default; this keeps the code honest for when it is switched on. */
class ChordConstraintTest {
    private fun shallowY(halfDeg: Double) = SimWalk.build(listOf(
        SimWalk.line(-60.0, 0.0, 0.0, 0.0),
        SimWalk.line(0.0, 0.0, 200 * cos(halfDeg * DEG), 200 * sin(halfDeg * DEG)),
        SimWalk.line(0.0, 0.0, 200 * cos(halfDeg * DEG), -200 * sin(halfDeg * DEG)),
    ), joinCrossings = false)

    private fun straight(pf: ParticleFilter, heading: Double, n: Int, seed: Long, t0: Long): Long {
        val rng = java.util.Random(seed)
        var t = t0
        repeat(n) {
            t += 600_000_000L
            pf.step(Step(t, 2.0, 0.7, 0.7, 0.6), wrap(heading + rng.nextGaussian() * 25 * DEG))
        }
        return t
    }

    @Test fun picksTheRightBranchAtAShallowYWhenEnabled() {
        val half = 12.0
        val stem = 55.0
        var correct = 0
        var meanErr = 0.0
        val seeds = (1L..8L).toList()
        for (sd in seeds) {
            val g = shallowY(half)
            val (x0, y0) = SimWalk.local(g, -stem, 0.0)
            val pf = ParticleFilter(g, FilterConfig(particles = 1500, useChord = true), seed = sd)
            pf.init(x0, y0, 2.0, kMean = 1.0, kStd = 0.05, thetaMean = 0.0, thetaStd = 10 * DEG)
            val t = straight(pf, 0.0, 79, sd, 0L)
            straight(pf, half * DEG, 72, sd + 7, t)
            val along = (79 + 72) * 0.7 - stem
            val truth = SimWalk.local(g, along * cos(half * DEG), along * sin(half * DEG))
            val b = pf.estimate().best!!
            meanErr += hypot(b.x - truth.first, b.y - truth.second)
            val (_, oy) = SimWalk.local(g, 0.0, 0.0)
            if (b.y - oy > 0) correct++
        }
        assertTrue("$correct/${seeds.size} on the right branch", correct >= 7)
        assertTrue("mean error ${meanErr / seeds.size} m", meanErr / seeds.size < 5.0)
    }

    @Test fun theChordPathActuallyRuns() {
        val g = SimWalk.build(listOf(SimWalk.line(0.0, 0.0, 300.0, 0.0)))
        val (x0, y0) = SimWalk.local(g, 0.0, 0.0)
        val pf = ParticleFilter(g, FilterConfig(particles = 600, useChord = true), seed = 4)
        pf.init(x0, y0, 2.0, kMean = 1.0, kStd = 0.05, thetaMean = 0.0, thetaStd = 10 * DEG)
        straight(pf, 0.0, 40, 3, 0L) // 28 m at a 10 m window
        assertTrue("expected 2 chords over 28 m, got ${pf.chords}", pf.chords == 2)
    }

}
