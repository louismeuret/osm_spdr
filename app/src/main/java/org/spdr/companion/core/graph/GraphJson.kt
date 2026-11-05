package org.spdr.companion.core.graph

import org.json.JSONArray
import org.json.JSONObject
import org.spdr.companion.core.geo.LocalProjection

/**
 * Map file format of the spec (§4): local ENU meters, plus the lat/lon `origin` of that frame
 * so the graph can be placed on OsmAnd's map.
 */
object GraphJson {
    fun write(g: Graph): String {
        val root = JSONObject()
        root.put("id", "spdr_graph")
        root.put("source", g.source)
        root.put("origin", JSONArray().put(g.projection.lat0).put(g.projection.lon0))
        root.put("hasZ", g.hasZ)
        val nodes = JSONArray()
        for (n in g.nodes) {
            val o = JSONObject().put("id", n.id).put("x", r(n.x)).put("y", r(n.y)).put("z", r(n.z))
            if (n.name != null) o.put("name", n.name)
            if (n.landmark) o.put("landmark", true)
            nodes.put(o)
        }
        root.put("nodes", nodes)
        val edges = JSONArray()
        for (e in g.edges) {
            val poly = JSONArray()
            for (i in e.xs.indices) poly.put(JSONArray().put(r(e.xs[i])).put(r(e.ys[i])).put(r(e.zs[i])))
            val o = JSONObject().put("id", e.id).put("from", g.nodes[e.from].id).put("to", g.nodes[e.to].id).put("polyline", poly)
            if (e.flags.isNotEmpty()) o.put("flags", JSONArray(e.flags.toList()))
            edges.put(o)
        }
        root.put("edges", edges)
        return root.toString()
    }

    fun read(text: String): Graph {
        val root = JSONObject(text)
        val origin = root.getJSONArray("origin")
        val proj = LocalProjection(origin.getDouble(0), origin.getDouble(1))
        val nodesJ = root.getJSONArray("nodes")
        val nodes = ArrayList<Node>()
        val byId = HashMap<String, Int>()
        for (i in 0 until nodesJ.length()) {
            val o = nodesJ.getJSONObject(i)
            val n = Node(i, o.getString("id"), o.getDouble("x"), o.getDouble("y"), o.optDouble("z", 0.0),
                o.optString("name").ifEmpty { null }, o.optBoolean("landmark", false))
            nodes.add(n)
            byId[n.id] = i
        }
        val edgesJ = root.getJSONArray("edges")
        val edges = ArrayList<Edge>()
        for (i in 0 until edgesJ.length()) {
            val o = edgesJ.getJSONObject(i)
            val poly = o.getJSONArray("polyline")
            val xs = DoubleArray(poly.length()); val ys = DoubleArray(poly.length()); val zs = DoubleArray(poly.length())
            for (j in 0 until poly.length()) {
                val p = poly.getJSONArray(j)
                xs[j] = p.getDouble(0); ys[j] = p.getDouble(1); zs[j] = if (p.length() > 2) p.getDouble(2) else 0.0
            }
            val flags = o.optJSONArray("flags")?.let { f -> (0 until f.length()).map { f.getString(it) }.toSet() } ?: emptySet()
            edges.add(Edge(i, o.optString("id", "e$i"), byId.getValue(o.getString("from")), byId.getValue(o.getString("to")), xs, ys, zs, flags))
        }
        return Graph(nodes, edges, proj, root.optBoolean("hasZ", false), root.optString("source", ""))
    }

    private fun r(v: Double) = Math.round(v * 100) / 100.0
}
