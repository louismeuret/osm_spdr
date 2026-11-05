package org.spdr.companion.core.graph

import org.spdr.companion.core.geo.LocalProjection
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class Node(
    val idx: Int,
    val id: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val name: String? = null,
    val landmark: Boolean = false,
) {
    /** Indices of incident edges (a self-loop appears twice). */
    val incident = ArrayList<Int>(4)
}

/**
 * Bidirectional gallery/road segment between two nodes. Positions on it are given as
 * `s`, the distance in meters from the `from` node along the polyline.
 */
class Edge(
    val idx: Int,
    val id: String,
    val from: Int,
    val to: Int,
    val xs: DoubleArray,
    val ys: DoubleArray,
    val zs: DoubleArray,
    val flags: Set<String> = emptySet(),
) {
    /** cum[i] = distance from the first polyline point to point i. */
    val cum = DoubleArray(xs.size)
    val length: Double

    init {
        for (i in 1 until xs.size) cum[i] = cum[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        length = max(cum.last(), 1e-3)
    }

    /** Heading (math convention, rad, CCW from east) leaving the `from` node, looking ahead a few meters. */
    val startHeading: Double = headingBetween(0.0, min(LOOKAHEAD, length))

    /** Heading leaving the `to` node into this edge (i.e. walking towards `from`). */
    val endHeading: Double = headingBetween(length, max(0.0, length - LOOKAHEAD))

    private fun segIndex(s: Double): Int {
        if (s <= 0) return 0
        if (s >= length) return xs.size - 2
        var lo = 0
        var hi = xs.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (cum[mid] <= s) lo = mid else hi = mid
        }
        return min(lo, xs.size - 2)
    }

    fun x(s: Double): Double {
        val i = segIndex(s)
        val seg = cum[i + 1] - cum[i]
        val t = if (seg > 0) ((s - cum[i]) / seg).coerceIn(0.0, 1.0) else 0.0
        return xs[i] + t * (xs[i + 1] - xs[i])
    }

    fun y(s: Double): Double {
        val i = segIndex(s)
        val seg = cum[i + 1] - cum[i]
        val t = if (seg > 0) ((s - cum[i]) / seg).coerceIn(0.0, 1.0) else 0.0
        return ys[i] + t * (ys[i + 1] - ys[i])
    }

    fun z(s: Double): Double {
        val i = segIndex(s)
        val seg = cum[i + 1] - cum[i]
        val t = if (seg > 0) ((s - cum[i]) / seg).coerceIn(0.0, 1.0) else 0.0
        return zs[i] + t * (zs[i + 1] - zs[i])
    }

    /** Tangent heading at s when walking towards `to` (dir = +1). */
    fun tangent(s: Double): Double {
        val i = segIndex(s)
        return atan2(ys[i + 1] - ys[i], xs[i + 1] - xs[i])
    }

    fun tangent(s: Double, dir: Int): Double = if (dir > 0) tangent(s) else tangent(s) + PI

    private fun headingBetween(s0: Double, s1: Double) = atan2(y(s1) - y(s0), x(s1) - x(s0))

    companion object {
        const val LOOKAHEAD = 5.0
    }
}

/** Result of a projection onto the graph. */
data class EdgePos(val edge: Int, val s: Double, val dist: Double)

class Graph(
    val nodes: List<Node>,
    val edges: List<Edge>,
    val projection: LocalProjection,
    /** True when node/edge z values come from real data (enables the barometer update). */
    val hasZ: Boolean = false,
    val source: String = "",
) {
    private val cell = 25.0
    private val grid = HashMap<Long, IntArray>()

    init {
        for (e in edges) {
            nodes[e.from].incident.add(e.idx)
            nodes[e.to].incident.add(e.idx)
        }
        val tmp = HashMap<Long, MutableSet<Int>>()
        for (e in edges) {
            for (i in 0 until e.xs.size - 1) {
                val x0 = min(e.xs[i], e.xs[i + 1]); val x1 = max(e.xs[i], e.xs[i + 1])
                val y0 = min(e.ys[i], e.ys[i + 1]); val y1 = max(e.ys[i], e.ys[i + 1])
                for (cx in cellOf(x0)..cellOf(x1)) for (cy in cellOf(y0)..cellOf(y1)) {
                    tmp.getOrPut(key(cx, cy)) { HashSet() }.add(e.idx)
                }
            }
        }
        for ((k, v) in tmp) grid[k] = v.toIntArray()
    }

    val totalLength: Double = edges.sumOf { it.length }

    private fun cellOf(v: Double) = floor(v / cell).toInt()
    private fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)

    /** Candidate edges whose segments may lie within r of (x, y). */
    fun edgesNear(x: Double, y: Double, r: Double): Set<Int> {
        val out = HashSet<Int>()
        for (cx in cellOf(x - r)..cellOf(x + r)) for (cy in cellOf(y - r)..cellOf(y + r)) {
            grid[key(cx, cy)]?.let { for (e in it) out.add(e) }
        }
        return out
    }

    /** Closest point of one edge to (x, y). */
    fun project(edge: Int, x: Double, y: Double): EdgePos {
        val e = edges[edge]
        var best = Double.MAX_VALUE
        var bestS = 0.0
        for (i in 0 until e.xs.size - 1) {
            val ax = e.xs[i]; val ay = e.ys[i]
            val dx = e.xs[i + 1] - ax; val dy = e.ys[i + 1] - ay
            val l2 = dx * dx + dy * dy
            val t = if (l2 > 0) (((x - ax) * dx + (y - ay) * dy) / l2).coerceIn(0.0, 1.0) else 0.0
            val d = hypot(ax + t * dx - x, ay + t * dy - y)
            if (d < best) {
                best = d
                bestS = e.cum[i] + t * (e.cum[i + 1] - e.cum[i])
            }
        }
        return EdgePos(edge, bestS, best)
    }

    /** Nearest graph position to (x, y) within maxDist, or null. */
    fun nearest(x: Double, y: Double, maxDist: Double = 200.0): EdgePos? {
        var r = minOf(cell, maxDist)
        while (true) {
            var best: EdgePos? = null
            for (e in edgesNear(x, y, r)) {
                val p = project(e, x, y)
                if (p.dist <= maxDist && (best == null || p.dist < best.dist)) best = p
            }
            if (best != null && best.dist <= r) return best
            if (r >= maxDist) return best
            r = minOf(r * 2, maxDist)
        }
    }

    /** Projections onto every edge passing within r of (x, y), closest first. */
    fun positionsWithin(x: Double, y: Double, r: Double): List<EdgePos> =
        edgesNear(x, y, r).map { project(it, x, y) }.filter { it.dist <= r }.sortedBy { it.dist }

    fun nearestNode(x: Double, y: Double): Node? = nodes.minByOrNull { hypot(it.x - x, it.y - y) }

    val bounds: DoubleArray by lazy {
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE
        var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (e in edges) for (i in e.xs.indices) {
            x0 = min(x0, e.xs[i]); x1 = max(x1, e.xs[i])
            y0 = min(y0, e.ys[i]); y1 = max(y1, e.ys[i])
        }
        doubleArrayOf(x0, y0, x1, y1)
    }
}
