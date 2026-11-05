package org.spdr.companion.core.filter

import org.spdr.companion.core.graph.Graph
import org.spdr.companion.core.pdr.Step
import org.spdr.companion.core.wrap
import java.util.Random
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/** One position hypothesis (a cluster of particles). Coordinates are local meters. */
data class Hypothesis(
    val x: Double,
    val y: Double,
    val edge: Int,
    val s: Double,
    /** Walking direction on the map (rad, CCW from east). */
    val heading: Double,
    /** Share of the total weight. */
    val weight: Double,
    /** RMS distance of the cluster's particles to its position (m). */
    val spread: Double,
)

data class Estimate(
    val hypotheses: List<Hypothesis>,
    val kMean: Double,
    val kStd: Double,
    val thetaMean: Double,
    val thetaStd: Double,
    val biasMean: Double,
    val neff: Double,
    val steps: Int,
    val distance: Double,
    /**
     * The reported best is not the heaviest hypothesis: a heavier one exists but is further away
     * than the walker could have got, so it is being held off until it really dominates. The
     * filter is re-localising; the position on screen is the last one that was reachable.
     */
    val holding: Boolean = false,
) {
    val best get() = hypotheses.firstOrNull()
}

/**
 * Particle filter on the gallery graph (spec §6). Each particle is a position on an edge plus its
 * own sensor error parameters (step scale k, heading offset θ, gyro bias), so map constraints and
 * manual resets select the parameters that explain the walk: that is the learning mechanism.
 */
class ParticleFilter(val graph: Graph, val cfg: FilterConfig = FilterConfig(), seed: Long = System.nanoTime()) {
    val n = cfg.particles
    private val rng = Random(seed)

    val edge = IntArray(n)
    val s = DoubleArray(n)
    val dir = IntArray(n)
    val k = DoubleArray(n)
    val theta = DoubleArray(n)
    val bias = DoubleArray(n)
    val z0 = DoubleArray(n)
    val w = DoubleArray(n)
    private val lik = DoubleArray(n)

    var initialized = false
        private set
    var steps = 0
        private set
    var distance = 0.0
        private set

    private var lastPsi = Double.NaN
    private var lastConfident: Hypothesis? = null

    /** Where the position was last reported, and how far we had walked then (spec: no teleports). */
    private var stickyX = Double.NaN
    private var stickyY = Double.NaN
    private var stickyDistance = 0.0

    /** Each particle's position when the current chord began, and the measured chord so far. */
    private val anchorX = DoubleArray(n)
    private val anchorY = DoubleArray(n)
    private var chordS = 0.0
    private var chordC = 0.0
    private var chordLen = 0.0

    /** How many multi-step chords have been used as a constraint. */
    var chords = 0
        private set
    private var distSinceConfident = 0.0
    private var lowLikStreak = 0
    private var recoveryLevel = 0
    private var stableSteps = 0

    /** Number of automatic re-spreads after particle depletion. */
    var recoveries = 0
        private set

    // scratch for resampling
    private val idx = IntArray(n)
    private val tmpI = IntArray(n)
    private val tmpD = DoubleArray(n)

    /**
     * Spreads particles on the graph around (x, y).
     * @param thetaMean heading offset prior (map heading − phone heading), or NaN if unknown.
     */
    fun init(
        x: Double, y: Double, radius: Double,
        kMean: Double = 1.0, kStd: Double = 0.1,
        thetaMean: Double = Double.NaN, thetaStd: Double = PI,
    ) {
        val fallback = graph.nearest(x, y, 2000.0) ?: error("graph has no edge near the start position")
        for (i in 0 until n) {
            val p = graph.nearest(x + rng.nextGaussian() * radius, y + rng.nextGaussian() * radius, 3 * radius + 5)
                ?: fallback
            edge[i] = p.edge; s[i] = p.s
            dir[i] = if (rng.nextBoolean()) 1 else -1
            k[i] = (kMean + rng.nextGaussian() * kStd).coerceIn(cfg.kMin, cfg.kMax)
            theta[i] = if (thetaMean.isNaN() || thetaStd >= PI) (rng.nextDouble() * 2 - 1) * PI
            else wrap(thetaMean + rng.nextGaussian() * thetaStd)
            bias[i] = rng.nextGaussian() * cfg.sigmaBias0
            z0[i] = Double.NaN
            w[i] = 1.0 / n
        }
        initialized = true
        steps = 0
        distance = 0.0
        lastConfident = Hypothesis(x, y, fallback.edge, fallback.s, 0.0, 1.0, radius)
        distSinceConfident = 0.0
        lowLikStreak = 0
        stickyX = x; stickyY = y; stickyDistance = 0.0
        reAnchor()
    }

    /**
     * Prediction + correction for one detected step.
     * @param psi mean phone heading over the step (game rotation frame).
     * @param magOffset magnetic heading − game heading over the step, NaN if unavailable.
     * @param baroH relative barometric height, NaN if unavailable.
     */
    fun step(step: Step, psi: Double, magOffset: Double = Double.NaN, baroH: Double = Double.NaN) {
        if (!initialized || psi.isNaN()) return
        lastPsi = psi
        val useBaro = cfg.useBarometer && graph.hasZ && !baroH.isNaN()
        val useCompass = cfg.useCompass && !magOffset.isNaN()
        var meanDs = 0.0
        for (i in 0 until n) {
            theta[i] = wrap(theta[i] - bias[i] * step.dt + rng.nextGaussian() * cfg.sigmaTheta)
            bias[i] += rng.nextGaussian() * cfg.sigmaBias
            k[i] = (k[i] + rng.nextGaussian() * cfg.sigmaK).coerceIn(cfg.kMin, cfg.kMax)
            val hdg = psi + theta[i]

            val e = graph.edges[edge[i]]
            if (rng.nextDouble() < cfg.uTurnProb && kotlin.math.abs(wrap(hdg - e.tangent(s[i], dir[i]))) > U_TURN_ANGLE) {
                dir[i] = -dir[i]
            }
            val ds = (k[i] * step.length + rng.nextGaussian() * cfg.sigmaL * step.length).coerceAtLeast(0.0)
            meanDs += w[i] * ds
            move(i, ds, hdg)

            val d = wrap(hdg - graph.edges[edge[i]].tangent(s[i], dir[i]))
            var l = cfg.headingFloor + exp(-d * d / (2 * cfg.sigmaPsi * cfg.sigmaPsi))
            if (useCompass) {
                val dc = wrap(theta[i] - magOffset)
                l *= cfg.compassFloor + exp(-dc * dc / (2 * cfg.sigmaCompass * cfg.sigmaCompass))
            }
            if (useBaro) {
                val z = graph.edges[edge[i]].z(s[i])
                if (z0[i].isNaN()) z0[i] = z - baroH
                val dz = baroH + z0[i] - z
                l *= 1e-3 + exp(-dz * dz / (2 * cfg.sigmaZ * cfg.sigmaZ))
            }
            lik[i] = l
        }
        steps++
        distance += meanDs
        distSinceConfident += meanDs

        var total = 0.0
        for (i in 0 until n) { w[i] *= lik[i]; total += w[i] }
        // total ≈ heading-likelihood of the whole cloud: stays near the floor only when nothing fits
        lowLikStreak = if (total < LOST_LIKELIHOOD) lowLikStreak + 1 else 0
        if (total > STABLE_LIKELIHOOD && ++stableSteps >= STABLE_STEPS) recoveryLevel = 0
        if (!(total > 1e-300) || lowLikStreak >= LOST_STEPS) {
            recover()
            return
        }
        for (i in 0 until n) w[i] /= total
        if (neff() < cfg.resampleRatio * n) resample(rough = true)

        // Over several steps the dead-reckoned displacement is far less noisy than over one, so
        // the straight line it describes is a much tighter constraint than any single heading.
        chordS += step.length * sin(psi)
        chordC += step.length * cos(psi)
        chordLen += step.length
        if (cfg.useChord && chordLen >= cfg.chordWindow) applyChord()

        estimate().best?.let {
            if (it.weight > 0.7 && it.spread < 15) { lastConfident = it; distSinceConfident = 0.0 }
            stickyX = it.x; stickyY = it.y; stickyDistance = distance
        }
    }

    /**
     * Weights every particle by how well the straight line it actually covered on the graph lines
     * up with the straight line we measured walking. Only applied over a window the walker really
     * walked straight through — otherwise the measured chord is short and its direction means
     * nothing — and a particle that barely moved while we covered several metres is penalised
     * whatever its direction. Windows do not overlap, so successive chords are independent.
     */
    private fun applyChord() {
        val mag = hypot(chordS, chordC)
        if (mag / chordLen >= cfg.chordStraightness) {
            val measured = atan2(chordS, chordC)
            var total = 0.0
            for (i in 0 until n) {
                val e = graph.edges[edge[i]]
                val dx = e.x(s[i]) - anchorX[i]
                val dy = e.y(s[i]) - anchorY[i]
                val chord = hypot(dx, dy)
                w[i] *= if (chord < cfg.chordStallRatio * k[i] * mag) {
                    cfg.chordFloor
                } else {
                    val d = wrap(measured + theta[i] - atan2(dy, dx))
                    cfg.chordFloor + exp(-d * d / (2 * cfg.sigmaChord * cfg.sigmaChord))
                }
                total += w[i]
            }
            if (total > 1e-300) {
                for (i in 0 until n) w[i] /= total
                chords++
                if (neff() < cfg.resampleRatio * n) resample(rough = true)
            } else {
                normalize()
            }
        }
        reAnchor()
    }

    private fun reAnchor() {
        for (i in 0 until n) {
            val e = graph.edges[edge[i]]
            anchorX[i] = e.x(s[i]); anchorY[i] = e.y(s[i])
        }
        chordS = 0.0; chordC = 0.0; chordLen = 0.0
    }

    /** Lets the next report go wherever the weight is, after the user has moved things by hand. */
    private fun freeReport() { stickyX = Double.NaN; stickyY = Double.NaN }

    /**
     * Moves particle i by ds meters in its current direction, choosing branches at nodes
     * by compatibility with the walking heading hdg.
     */
    private fun move(i: Int, ds: Double, hdg: Double) {
        var rem = ds
        var guard = 0
        while (guard++ < 64) {
            val e = graph.edges[edge[i]]
            val target = s[i] + dir[i] * rem
            if (target in 0.0..e.length) { s[i] = target; return }
            rem = if (dir[i] > 0) target - e.length else -target
            val node = if (dir[i] > 0) e.to else e.from
            val inc = graph.nodes[node].incident
            var count = 0
            var sum = 0.0
            for (c in inc) {
                val ce = graph.edges[c]
                if (ce.from == node && !(c == edge[i] && dir[i] < 0)) {
                    optE[count] = c; optD[count] = 1; optS[count] = 0.0
                    sum += branchWeight(hdg, ce.startHeading).also { optW[count] = it }
                    if (++count == MAX_DEGREE) break
                }
                if (ce.to == node && !(c == edge[i] && dir[i] > 0)) {
                    optE[count] = c; optD[count] = -1; optS[count] = ce.length
                    sum += branchWeight(hdg, ce.endHeading).also { optW[count] = it }
                    if (++count == MAX_DEGREE) break
                }
            }
            if (count == 0) {
                // dead end: bounce back
                s[i] = if (dir[i] > 0) e.length else 0.0
                dir[i] = -dir[i]
                w[i] *= cfg.deadEndPenalty
                continue
            }
            var r = rng.nextDouble() * sum
            var pick = count - 1
            for (j in 0 until count) { r -= optW[j]; if (r <= 0) { pick = j; break } }
            edge[i] = optE[pick]; dir[i] = optD[pick]; s[i] = optS[pick]
        }
    }

    private val optE = IntArray(MAX_DEGREE)
    private val optD = IntArray(MAX_DEGREE)
    private val optS = DoubleArray(MAX_DEGREE)
    private val optW = DoubleArray(MAX_DEGREE)

    private fun branchWeight(hdg: Double, out: Double): Double {
        val d = wrap(hdg - out)
        return 1e-6 + exp(-d * d / (2 * cfg.sigmaBranch * cfg.sigmaBranch))
    }

    fun neff(): Double {
        var s2 = 0.0
        for (i in 0 until n) s2 += w[i] * w[i]
        return 1.0 / s2
    }

    /** Systematic resampling, then optional roughening of the parameters. */
    private fun resample(rough: Boolean) {
        val step = 1.0 / n
        var u = rng.nextDouble() * step
        var c = w[0]
        var j = 0
        for (i in 0 until n) {
            while (u > c && j < n - 1) { j++; c += w[j] }
            idx[i] = j
            u += step
        }
        permuteI(edge); permuteI(dir)
        permuteD(s); permuteD(k); permuteD(theta); permuteD(bias); permuteD(z0)
        permuteD(anchorX); permuteD(anchorY)
        for (i in 0 until n) w[i] = step
        if (rough) for (i in 0 until n) {
            k[i] = (k[i] + rng.nextGaussian() * cfg.roughK).coerceIn(cfg.kMin, cfg.kMax)
            theta[i] = wrap(theta[i] + rng.nextGaussian() * cfg.roughTheta)
            bias[i] += rng.nextGaussian() * cfg.roughBias
        }
    }

    private fun permuteI(a: IntArray) { for (i in 0 until n) tmpI[i] = a[idx[i]]; tmpI.copyInto(a) }
    private fun permuteD(a: DoubleArray) { for (i in 0 until n) tmpD[i] = a[idx[i]]; tmpD.copyInto(a) }

    /**
     * Particle depletion (spec §6.6): re-spread positions around the last confident estimate,
     * keeping each particle's k, θ and bias so the calibration learned so far is not lost.
     * Repeated depletions without a stable period in between widen the search, and a fifth of
     * the current cloud is kept in case it was right after all.
     */
    private fun recover() {
        recoveries++
        val c = lastConfident ?: estimate().best ?: return
        val radius = ((30 + 0.3 * distSinceConfident) * (1 shl recoveryLevel)).coerceAtMost(500.0)
        recoveryLevel = (recoveryLevel + 1).coerceAtMost(4)
        val fallback = graph.nearest(c.x, c.y, 5000.0) ?: return
        for (i in 0 until n) {
            w[i] = 1.0 / n
            if (rng.nextDouble() < 0.2) continue
            val p = graph.nearest(c.x + rng.nextGaussian() * radius, c.y + rng.nextGaussian() * radius, radius) ?: fallback
            edge[i] = p.edge; s[i] = p.s
            dir[i] = if (rng.nextBoolean()) 1 else -1
        }
        lowLikStreak = 0
        stableSteps = 0
        reAnchor()
    }

    /**
     * Manual position fix (spec §6.5): a measurement, not a teleport. Particles are weighted by
     * their distance to the confirmed position, so the ones whose parameters explained the walk
     * survive; then all are snapped to that position.
     * @return distance between the estimate and the fix before applying it (m), or null if the
     * filter was not running yet.
     */
    fun reset(x: Double, y: Double, sigma: Double = cfg.sigmaReset, baroH: Double = Double.NaN): Double? {
        if (!initialized) return null
        val before = estimate().best
        val err = before?.let { hypot(it.x - x, it.y - y) }

        var maxLw = -Double.MAX_VALUE
        for (i in 0 until n) {
            val e = graph.edges[edge[i]]
            val d = hypot(e.x(s[i]) - x, e.y(s[i]) - y)
            tmpD[i] = ln(w[i].coerceAtLeast(1e-300)) - d * d / (2 * sigma * sigma)
            if (tmpD[i] > maxLw) maxLw = tmpD[i]
        }
        var total = 0.0
        for (i in 0 until n) { w[i] = exp(tmpD[i] - maxLw); total += w[i] }
        for (i in 0 until n) w[i] /= total
        resample(rough = true)

        val cands = graph.positionsWithin(x, y, maxOf(sigma, 3.0)).ifEmpty { listOfNotNull(graph.nearest(x, y, 2000.0)) }
        for (i in 0 until n) {
            val p = cands[rng.nextInt(cands.size)]
            edge[i] = p.edge; s[i] = p.s
            dir[i] = if (lastPsi.isNaN()) (if (rng.nextBoolean()) 1 else -1) else {
                val e = graph.edges[p.edge]
                val hdg = lastPsi + theta[i]
                val pf = exp(-sq(wrap(hdg - e.tangent(p.s, 1))) / (2 * cfg.sigmaPsi * cfg.sigmaPsi)) + 1e-3
                val pb = exp(-sq(wrap(hdg - e.tangent(p.s, -1))) / (2 * cfg.sigmaPsi * cfg.sigmaPsi)) + 1e-3
                if (rng.nextDouble() * (pf + pb) < pf) 1 else -1
            }
            if (!baroH.isNaN()) z0[i] = graph.edges[p.edge].z(p.s) - baroH
        }
        val p0 = cands[0]
        lastConfident = Hypothesis(x, y, p0.edge, p0.s, 0.0, 1.0, sigma)
        distSinceConfident = 0.0
        lowLikStreak = 0
        // a confirmed position is the one jump that is allowed
        stickyX = x; stickyY = y; stickyDistance = distance
        reAnchor()
        return err
    }

    /** Shifts every particle by d meters along its walking direction (negative = backwards). */
    fun nudge(d: Double) {
        if (!initialized || d == 0.0) return
        for (i in 0 until n) {
            if (d < 0) dir[i] = -dir[i]
            move(i, kotlin.math.abs(d), graph.edges[edge[i]].tangent(s[i], dir[i]))
            if (d < 0) dir[i] = -dir[i]
        }
        normalize()
        freeReport()
        reAnchor()
    }

    /** "I'm going the other way": flip walking direction and heading offset of every particle. */
    fun uTurn() {
        if (!initialized) return
        for (i in 0 until n) { dir[i] = -dir[i]; theta[i] = wrap(theta[i] + PI) }
        reAnchor()
    }

    /** Keeps only the particles near (x, y), e.g. the alternative hypothesis the user picked. */
    fun selectHypothesis(x: Double, y: Double) {
        if (!initialized) return
        for (i in 0 until n) {
            val e = graph.edges[edge[i]]
            if (hypot(e.x(s[i]) - x, e.y(s[i]) - y) > cfg.clusterRadius) w[i] *= 1e-4
        }
        normalize()
        resample(rough = true)
        // the user picked this hypothesis, so it becomes the reachable one
        stickyX = x; stickyY = y; stickyDistance = distance
        reAnchor()
    }

    /** Drops the most likely hypothesis in favor of the others ("not this branch"). */
    fun rejectBest() {
        val est = estimate()
        val best = est.best ?: return
        if (est.hypotheses.size < 2) return
        for (i in 0 until n) {
            val e = graph.edges[edge[i]]
            if (hypot(e.x(s[i]) - best.x, e.y(s[i]) - best.y) <= cfg.clusterRadius) w[i] *= 1e-4
        }
        normalize()
        resample(rough = true)
        freeReport()
        reAnchor()
    }

    private fun normalize() {
        var t = 0.0
        for (i in 0 until n) t += w[i]
        if (t > 0) for (i in 0 until n) w[i] /= t else for (i in 0 until n) w[i] = 1.0 / n
    }

    /** Clusters particles into position hypotheses (spec §6.7), best first. */
    fun estimate(): Estimate {
        if (!initialized) return Estimate(emptyList(), 1.0, 0.0, 0.0, PI, 0.0, 0.0, 0, 0.0)
        // per-edge aggregates
        val sumW = HashMap<Int, DoubleArray>() // [w, wx, wy]
        for (i in 0 until n) {
            val e = graph.edges[edge[i]]
            val a = sumW.getOrPut(edge[i]) { DoubleArray(3) }
            a[0] += w[i]; a[1] += w[i] * e.x(s[i]); a[2] += w[i] * e.y(s[i])
        }
        val clusters = ArrayList<Cl>()
        val edgeCluster = HashMap<Int, Cl>()
        for ((e, a) in sumW.entries.sortedByDescending { it.value[0] }) {
            val mx = a[1] / a[0]; val my = a[2] / a[0]
            val cl = clusters.firstOrNull { hypot(it.x - mx, it.y - my) < cfg.clusterRadius } ?: Cl(mx, my).also { clusters.add(it) }
            cl.weight += a[0]; cl.edges.add(e)
            if (a[0] > cl.bestEdgeW) { cl.bestEdgeW = a[0]; cl.bestEdge = e }
            edgeCluster[e] = cl
        }
        // A walker cannot teleport. The heaviest cluster is not reported if it sits further away
        // than they could have walked since the last report, unless it dominates the reachable one
        // outright — which is what stops the position flicking between distant branches, and what
        // keeps a re-spread after particle depletion from throwing the dot hundreds of metres.
        val free = stickyX.isNaN()
        val reach = (distance - stickyDistance) * cfg.reachMargin + cfg.reachFloor
        fun score(cl: Cl) =
            if (free || hypot(cl.x - stickyX, cl.y - stickyY) <= reach) cl.weight
            else cl.weight * cfg.jumpPenalty
        val ranked = clusters.sortedByDescending { score(it) }
        val heaviest = clusters.maxByOrNull { it.weight }
        val holding = ranked.firstOrNull() !== heaviest
        val hyps = ranked.take(MAX_HYPOTHESES).map { cl ->
            val e = graph.edges[cl.bestEdge]
            var ws = 0.0; var wsum = 0.0; var hs = 0.0; var hc = 0.0
            for (i in 0 until n) if (edge[i] == cl.bestEdge) {
                ws += w[i] * s[i]; wsum += w[i]
                val t = e.tangent(s[i], dir[i])
                hs += w[i] * sin(t); hc += w[i] * cos(t)
            }
            val sm = ws / wsum
            val x = e.x(sm); val y = e.y(sm)
            var v = 0.0; var vw = 0.0
            for (i in 0 until n) if (edgeCluster[edge[i]] === cl) {
                val ei = graph.edges[edge[i]]
                v += w[i] * (sq(ei.x(s[i]) - x) + sq(ei.y(s[i]) - y)); vw += w[i]
            }
            Hypothesis(x, y, cl.bestEdge, sm, atan2(hs, hc), cl.weight, sqrt(v / vw))
        }
        var km = 0.0; var ts = 0.0; var tc = 0.0; var bm = 0.0
        for (i in 0 until n) { km += w[i] * k[i]; ts += w[i] * sin(theta[i]); tc += w[i] * cos(theta[i]); bm += w[i] * bias[i] }
        var kv = 0.0
        for (i in 0 until n) kv += w[i] * sq(k[i] - km)
        val r = hypot(ts, tc).coerceIn(1e-12, 1.0)
        return Estimate(hyps, km, sqrt(kv), atan2(ts, tc), sqrt(-2 * ln(r)), bm, neff(), steps, distance, holding)
    }

    private fun sq(v: Double) = v * v

    /** One position hypothesis under construction: the particles on a group of nearby edges. */
    private class Cl(val x: Double, val y: Double) {
        var weight = 0.0
        var bestEdge = -1
        var bestEdgeW = 0.0
        val edges = HashSet<Int>()
    }

    companion object {
        private const val MAX_DEGREE = 32
        private const val MAX_HYPOTHESES = 4
        private const val U_TURN_ANGLE = 2.1 // ~120°
        private const val LOST_LIKELIHOOD = 0.12
        private const val LOST_STEPS = 12
        private const val STABLE_LIKELIHOOD = 0.4
        private const val STABLE_STEPS = 150
    }
}
