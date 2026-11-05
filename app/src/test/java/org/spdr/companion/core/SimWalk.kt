package org.spdr.companion.core

import org.spdr.companion.core.geo.LatLon
import org.spdr.companion.core.geo.LocalProjection
import org.spdr.companion.core.graph.Graph
import org.spdr.companion.core.graph.GraphBuilder
import org.spdr.companion.core.graph.Polyline
import org.spdr.companion.core.pdr.Step
import org.spdr.companion.core.filter.ParticleFilter
import java.util.Random
import kotlin.math.hypot

/** Synthetic walks on a graph with known sensor errors (spec §8). */
object SimWalk {
    private val proj = LocalProjection(48.83, 2.33)

    fun line(vararg xy: Double, flags: Set<String> = emptySet()) =
        Polyline(xy.toList().chunked(2).map { (x, y) -> LatLon(proj.toLat(y), proj.toLon(x)) }, flags = flags)

    fun build(lines: List<Polyline>, joinCrossings: Boolean = true): Graph =
        GraphBuilder(joinCrossings = joinCrossings).build(lines)

    /** Graph-frame coordinates of a local point of the test frame. */
    fun local(g: Graph, x: Double, y: Double): Pair<Double, Double> =
        g.projection.toX(proj.toLon(x)) to g.projection.toY(proj.toLat(y))

    /** Square grid of `n`×`n` nodes, `spacing` meters apart. */
    fun grid(n: Int, spacing: Double): Graph {
        val lines = ArrayList<Polyline>()
        val max = (n - 1) * spacing
        for (i in 0 until n) {
            lines.add(line(0.0, i * spacing, max, i * spacing))
            lines.add(line(i * spacing, 0.0, i * spacing, max))
        }
        return build(lines)
    }

    /** Jittered grid with ~25% of the links missing: no translation symmetry, dead ends. */
    fun irregular(seed: Long, n: Int = 7, spacing: Double = 40.0): Graph {
        val rng = Random(seed)
        val px = Array(n) { i -> DoubleArray(n) { j -> i * spacing + if (i == 0 && j == 0) 0.0 else rng.nextGaussian() * 8 } }
        val py = Array(n) { i -> DoubleArray(n) { j -> j * spacing + if (i == 0 && j == 0) 0.0 else rng.nextGaussian() * 8 } }
        val lines = ArrayList<Polyline>()
        for (i in 0 until n) for (j in 0 until n) {
            if (i + 1 < n && (rng.nextDouble() > 0.25 || j == 0)) lines.add(line(px[i][j], py[i][j], px[i + 1][j], py[i + 1][j]))
            if (j + 1 < n && (rng.nextDouble() > 0.25 || i == 0)) lines.add(line(px[i][j], py[i][j], px[i][j + 1], py[i][j + 1]))
        }
        return build(lines, joinCrossings = false)
    }

    class Truth(val x: Double, val y: Double)

    /**
     * Walks on the graph from (edge, s, dir), picking random branches, and feeds the filter
     * step by step. `onStep` gets the truth after each step.
     */
    class Walker(
        val g: Graph,
        var edge: Int,
        var s: Double,
        var dir: Int,
        val kTrue: Double,
        val thetaTrue: Double,
        val headingNoise: Double = 12 * DEG,
        seed: Long = 1,
    ) {
        val rng = Random(seed)
        var t = 0L

        fun pos(): Truth { val e = g.edges[edge]; return Truth(e.x(s), e.y(s)) }

        /** Advances one step and returns (step, phone heading). */
        fun step(pf: ParticleFilter?): Pair<Step, Double> {
            val stride = 0.7 + rng.nextGaussian() * 0.05
            var rem = stride
            while (true) {
                val e = g.edges[edge]
                val target = s + dir * rem
                if (target in 0.0..e.length) { s = target; break }
                rem = if (dir > 0) target - e.length else -target
                val node = if (dir > 0) e.to else e.from
                val opts = g.nodes[node].incident.flatMap { c ->
                    val ce = g.edges[c]
                    listOfNotNull(
                        if (ce.from == node && !(c == edge && dir < 0)) Triple(c, 1, 0.0) else null,
                        if (ce.to == node && !(c == edge && dir > 0)) Triple(c, -1, ce.length) else null,
                    )
                }
                if (opts.isEmpty()) { dir = -dir; s = if (dir < 0) e.length else 0.0; continue }
                val o = opts[rng.nextInt(opts.size)]
                edge = o.first; dir = o.second; s = o.third
            }
            t += 600_000_000L
            val trueHeading = g.edges[edge].tangent(s, dir)
            val psi = wrap(trueHeading - thetaTrue + rng.nextGaussian() * headingNoise)
            val step = Step(t, 2.0, stride / kTrue, stride / kTrue, 0.6)
            pf?.step(step, psi)
            return step to psi
        }

        fun error(pf: ParticleFilter): Double {
            val b = pf.estimate().best!!
            val p = pos()
            return hypot(b.x - p.x, b.y - p.y)
        }
    }
}
