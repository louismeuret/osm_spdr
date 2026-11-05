package org.spdr.companion.core.graph

import org.spdr.companion.core.geo.LatLon
import org.spdr.companion.core.geo.LocalProjection
import kotlin.math.floor
import kotlin.math.hypot

/** One input line (an OSM way, a GeoJSON LineString, a GPX track segment...). */
class Polyline(
    val pts: List<LatLon>,
    /** Stable vertex ids (OSM node ids). When present, junctions come from shared ids only. */
    val ids: List<Long>? = null,
    val zs: List<Double>? = null,
    val flags: Set<String> = emptySet(),
)

/** A named point (e.g. a room of the catacombs) to turn into a reset node on the nearest gallery. */
class Landmark(val pos: LatLon, val name: String)

/**
 * Turns loose polylines into a routable graph: nodes at line ends and junctions, edges in between.
 *
 * Hand-drawn lines rarely share exact vertices, so lines without ids are welded: vertices within
 * [weldTol] merge, a dangling end within [snapTol] of another line is attached to it (T junction),
 * and with [joinCrossings] two crossing segments get a common node (X junction). Leave
 * [joinCrossings] off for multi-level networks where galleries pass over each other.
 */
class GraphBuilder(
    private val weldTol: Double = 0.5,
    private val snapTol: Double = 3.0,
    private val joinCrossings: Boolean = true,
    private val landmarkTol: Double = 20.0,
) {
    private class V(var x: Double, var y: Double, var z: Double, var key: String)
    private class Insert(val line: Int, val seg: Int, val t: Double, val v: V)

    private var nextKey = 0
    private fun newKey() = "k${nextKey++}"

    fun build(input: List<Polyline>, landmarks: List<Landmark> = emptyList(), source: String = ""): Graph {
        val lines = input.filter { it.pts.size >= 2 }
        require(lines.isNotEmpty()) { "no lines to build a graph from" }
        val all = lines.flatMap { it.pts }
        val proj = LocalProjection(all.sumOf { it.lat } / all.size, all.sumOf { it.lon } / all.size)
        val hasZ = lines.any { it.zs != null }

        // 1. project and key vertices
        val weld = HashMap<Long, MutableList<V>>()
        fun weldKey(x: Double, y: Double): String {
            val cx = floor(x).toLong(); val cy = floor(y).toLong()
            for (dx in -1..1) for (dy in -1..1) {
                weld[(cx + dx) * 1_000_003L + (cy + dy)]?.forEach { if (hypot(it.x - x, it.y - y) <= weldTol) return it.key }
            }
            val v = V(x, y, 0.0, newKey())
            weld.getOrPut(cx * 1_000_003L + cy) { ArrayList() }.add(v)
            return v.key
        }
        val vl: MutableList<MutableList<V>> = lines.map { line ->
            val out = ArrayList<V>(line.pts.size)
            for (i in line.pts.indices) {
                val p = line.pts[i]
                val x = proj.toX(p.lon); val y = proj.toY(p.lat)
                val key = line.ids?.let { "o${it[i]}" } ?: weldKey(x, y)
                if (out.isEmpty() || out.last().key != key) out.add(V(x, y, line.zs?.get(i) ?: 0.0, key))
            }
            out
        }.toMutableList()
        val welded = lines.map { it.ids == null }

        // 2. T and X junctions + landmarks, computed on the original geometry then applied
        val segGrid = SegmentGrid(vl, 10.0)
        val inserts = ArrayList<Insert>()
        // new junction vertices that land on top of each other (T + X + landmark) share one key
        fun keyAt(x: Double, y: Double): String =
            inserts.firstOrNull { hypot(it.v.x - x, it.v.y - y) <= weldTol }?.v?.key ?: newKey()
        val occurrences = HashMap<String, Int>()
        for (l in vl) for (v in l) occurrences.merge(v.key, 1, Int::plus)

        for ((li, l) in vl.withIndex()) {
            if (!welded[li]) continue
            for (end in listOf(l.first(), l.last())) {
                if (occurrences[end.key]!! > 1) continue
                val hit = segGrid.nearest(end.x, end.y, snapTol) { lj, sj ->
                    !(lj == li && (sj == 0 || sj == l.size - 2) && (end === l.first() && sj == 0 || end === l.last() && sj == l.size - 2))
                } ?: continue
                val target = vl[hit.line]
                val a = target[hit.seg]; val b = target[hit.seg + 1]
                when {
                    hit.t <= 0.0 || hypot(a.x - hit.x, a.y - hit.y) <= weldTol -> end.key = a.key
                    hit.t >= 1.0 || hypot(b.x - hit.x, b.y - hit.y) <= weldTol -> end.key = b.key
                    else -> {
                        val v = V(hit.x, hit.y, a.z + hit.t * (b.z - a.z), keyAt(hit.x, hit.y))
                        inserts.add(Insert(hit.line, hit.seg, hit.t, v))
                        end.key = v.key
                    }
                }
                end.x = hit.x; end.y = hit.y
            }
        }
        if (joinCrossings) {
            for (c in segGrid.crossings()) {
                if (!welded[c.l1] && !welded[c.l2]) continue
                val v = V(c.x, c.y, 0.0, keyAt(c.x, c.y))
                inserts.add(Insert(c.l1, c.s1, c.t1, v))
                inserts.add(Insert(c.l2, c.s2, c.t2, V(c.x, c.y, 0.0, v.key)))
            }
        }
        val names = HashMap<String, String>()
        for (lm in landmarks) {
            val x = proj.toX(lm.pos.lon); val y = proj.toY(lm.pos.lat)
            val hit = segGrid.nearest(x, y, landmarkTol) { _, _ -> true } ?: continue
            val a = vl[hit.line][hit.seg]; val b = vl[hit.line][hit.seg + 1]
            val key = when {
                hypot(a.x - hit.x, a.y - hit.y) <= weldTol -> a.key
                hypot(b.x - hit.x, b.y - hit.y) <= weldTol -> b.key
                else -> {
                    val existing = inserts.firstOrNull { hypot(it.v.x - hit.x, it.v.y - hit.y) <= weldTol }
                    if (existing == null || existing.line != hit.line) {
                        inserts.add(Insert(hit.line, hit.seg, hit.t, V(hit.x, hit.y, 0.0, existing?.v?.key ?: newKey())))
                    }
                    inserts.last { hypot(it.v.x - hit.x, it.v.y - hit.y) <= weldTol }.v.key
                }
            }
            names[key] = lm.name
        }
        for ((li, ins) in inserts.groupBy { it.line }) {
            val l = vl[li]
            for (i in ins.sortedWith(compareByDescending<Insert> { it.seg }.thenByDescending { it.t })) {
                val a = l[i.seg]; val b = l[i.seg + 1]
                i.v.z = a.z + i.t * (b.z - a.z)
                l.add(i.seg + 1, i.v)
            }
        }
        for (l in vl) {
            var j = 1
            while (j < l.size) if (l[j].key == l[j - 1].key) l.removeAt(j) else j++
        }

        // 3. nodes = line ends + vertices used more than once
        occurrences.clear()
        for (l in vl) for (v in l) occurrences.merge(v.key, 1, Int::plus)
        val nodeKeys = HashSet<String>()
        for (l in vl) {
            nodeKeys.add(l.first().key); nodeKeys.add(l.last().key)
            for (v in l) if (occurrences[v.key]!! > 1 || names.containsKey(v.key)) nodeKeys.add(v.key)
        }

        // 4. split into edges
        val nodes = ArrayList<Node>()
        val nodeIdx = HashMap<String, Int>()
        fun nodeFor(v: V): Int = nodeIdx.getOrPut(v.key) {
            val n = Node(nodes.size, v.key.removePrefix("o"), v.x, v.y, v.z, names[v.key], names.containsKey(v.key))
            nodes.add(n)
            n.idx
        }
        val edges = ArrayList<Edge>()
        for ((li, l) in vl.withIndex()) {
            var start = 0
            for (i in 1 until l.size) {
                if (i != l.size - 1 && l[i].key !in nodeKeys) continue
                val part = l.subList(start, i + 1)
                val len = (1 until part.size).sumOf { hypot(part[it].x - part[it - 1].x, part[it].y - part[it - 1].y) }
                if (len > 0.05) {
                    edges.add(Edge(
                        edges.size, "e${edges.size}", nodeFor(part.first()), nodeFor(part.last()),
                        DoubleArray(part.size) { part[it].x }, DoubleArray(part.size) { part[it].y },
                        DoubleArray(part.size) { part[it].z }, lines[li].flags,
                    ))
                }
                start = i
            }
        }
        // drop nodes that ended up without edges (degenerate pieces)
        val used = BooleanArray(nodes.size)
        for (e in edges) { used[e.from] = true; used[e.to] = true }
        if (used.all { it }) return Graph(nodes, edges, proj, hasZ, source)
        val remap = IntArray(nodes.size) { -1 }
        val kept = ArrayList<Node>()
        for (n in nodes) if (used[n.idx]) {
            remap[n.idx] = kept.size
            kept.add(Node(kept.size, n.id, n.x, n.y, n.z, n.name, n.landmark))
        }
        val remapped = edges.map { Edge(it.idx, it.id, remap[it.from], remap[it.to], it.xs, it.ys, it.zs, it.flags) }
        return Graph(kept, remapped, proj, hasZ, source)
    }

    private class Hit(val line: Int, val seg: Int, val t: Double, val x: Double, val y: Double, val d: Double)
    private class Crossing(val l1: Int, val s1: Int, val t1: Double, val l2: Int, val s2: Int, val t2: Double, val x: Double, val y: Double)

    private class SegmentGrid(val lines: List<List<V>>, val cell: Double) {
        private val grid = HashMap<Long, MutableList<Long>>()

        init {
            for ((li, l) in lines.withIndex()) for (si in 0 until l.size - 1) {
                val a = l[si]; val b = l[si + 1]
                for (cx in c(minOf(a.x, b.x))..c(maxOf(a.x, b.x))) for (cy in c(minOf(a.y, b.y))..c(maxOf(a.y, b.y))) {
                    grid.getOrPut(k(cx, cy)) { ArrayList() }.add((li.toLong() shl 32) or si.toLong())
                }
            }
        }

        private fun c(v: Double) = floor(v / cell).toInt()
        private fun k(cx: Int, cy: Int) = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)

        fun nearest(x: Double, y: Double, r: Double, accept: (Int, Int) -> Boolean): Hit? {
            var best: Hit? = null
            val seen = HashSet<Long>()
            for (cx in c(x - r)..c(x + r)) for (cy in c(y - r)..c(y + r)) {
                for (id in grid[k(cx, cy)] ?: continue) {
                    if (!seen.add(id)) continue
                    val li = (id ushr 32).toInt(); val si = id.toInt()
                    if (!accept(li, si)) continue
                    val a = lines[li][si]; val b = lines[li][si + 1]
                    val dx = b.x - a.x; val dy = b.y - a.y
                    val l2 = dx * dx + dy * dy
                    val t = if (l2 > 0) (((x - a.x) * dx + (y - a.y) * dy) / l2).coerceIn(0.0, 1.0) else 0.0
                    val px = a.x + t * dx; val py = a.y + t * dy
                    val d = hypot(px - x, py - y)
                    if (d <= r && (best == null || d < best.d)) best = Hit(li, si, t, px, py, d)
                }
            }
            return best
        }

        /** Proper crossings (strictly inside both segments) between segments. */
        fun crossings(): List<Crossing> {
            val out = ArrayList<Crossing>()
            val done = HashSet<Pair<Long, Long>>()
            for (ids in grid.values) for (i in ids.indices) for (j in i + 1 until ids.size) {
                val p = minOf(ids[i], ids[j]) to maxOf(ids[i], ids[j])
                if (!done.add(p)) continue
                val l1 = (p.first ushr 32).toInt(); val s1 = p.first.toInt()
                val l2 = (p.second ushr 32).toInt(); val s2 = p.second.toInt()
                if (l1 == l2 && kotlin.math.abs(s1 - s2) <= 1) continue
                val a = lines[l1][s1]; val b = lines[l1][s1 + 1]
                val c = lines[l2][s2]; val d = lines[l2][s2 + 1]
                if (a.key == c.key || a.key == d.key || b.key == c.key || b.key == d.key) continue
                val rx = b.x - a.x; val ry = b.y - a.y
                val sx = d.x - c.x; val sy = d.y - c.y
                val den = rx * sy - ry * sx
                if (kotlin.math.abs(den) < 1e-9) continue
                val t = ((c.x - a.x) * sy - (c.y - a.y) * sx) / den
                val u = ((c.x - a.x) * ry - (c.y - a.y) * rx) / den
                if (t <= 1e-6 || t >= 1 - 1e-6 || u <= 1e-6 || u >= 1 - 1e-6) continue
                out.add(Crossing(l1, s1, t, l2, s2, u, a.x + t * rx, a.y + t * ry))
            }
            return out
        }
    }
}
