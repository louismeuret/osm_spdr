package org.spdr.companion.core.io

import org.json.JSONArray
import org.json.JSONObject
import org.spdr.companion.core.geo.LatLon
import org.spdr.companion.core.graph.Landmark
import org.spdr.companion.core.graph.Polyline
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Lines and named points extracted from one input file. */
class ParsedSource(val lines: List<Polyline>, val landmarks: List<Landmark>)

/**
 * Readers for the formats a user is likely to have: GeoJSON, GPX, KML and Overpass JSON.
 * Polygons are read as their outer ring (a closed gallery loop).
 */
object GeoParsers {

    fun parse(name: String, bytes: ByteArray): ParsedSource {
        val lower = name.lowercase()
        val head = String(bytes, 0, minOf(bytes.size, 512)).trimStart()
        return when {
            lower.endsWith(".gpx") -> gpx(bytes)
            lower.endsWith(".kml") -> kml(bytes)
            head.startsWith("<") && head.contains("<gpx") -> gpx(bytes)
            head.startsWith("<") -> kml(bytes)
            else -> {
                val o = JSONObject(String(bytes))
                if (o.has("elements")) overpass(o) else geoJson(o)
            }
        }
    }

    // ---- GeoJSON ---------------------------------------------------------------------------

    fun geoJson(root: JSONObject): ParsedSource {
        val lines = ArrayList<Polyline>()
        val marks = ArrayList<Landmark>()
        val features = when (root.optString("type")) {
            "FeatureCollection" -> root.getJSONArray("features").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            "Feature" -> listOf(root)
            else -> listOf(JSONObject().put("type", "Feature").put("geometry", root))
        }
        for (f in features) {
            val geom = f.optJSONObject("geometry") ?: continue
            val props = f.optJSONObject("properties")
            geometry(geom, props, lines, marks)
        }
        return ParsedSource(lines, marks)
    }

    private fun geometry(g: JSONObject, props: JSONObject?, lines: MutableList<Polyline>, marks: MutableList<Landmark>) {
        val c = g.optJSONArray("coordinates")
        when (g.optString("type")) {
            "LineString" -> lines.add(coords(c!!))
            "MultiLineString" -> for (i in 0 until c!!.length()) lines.add(coords(c.getJSONArray(i)))
            "Polygon" -> lines.add(coords(c!!.getJSONArray(0)))
            "MultiPolygon" -> for (i in 0 until c!!.length()) lines.add(coords(c.getJSONArray(i).getJSONArray(0)))
            "Point" -> {
                val name = props?.let { p -> listOf("name", "nom", "title").firstNotNullOfOrNull { p.optString(it).ifEmpty { null } } }
                if (name != null) marks.add(Landmark(LatLon(c!!.getDouble(1), c.getDouble(0)), name))
            }
            "GeometryCollection" -> {
                val gs = g.getJSONArray("geometries")
                for (i in 0 until gs.length()) geometry(gs.getJSONObject(i), props, lines, marks)
            }
        }
    }

    private fun coords(a: JSONArray): Polyline {
        val pts = (0 until a.length()).map { i -> a.getJSONArray(i).let { LatLon(it.getDouble(1), it.getDouble(0)) } }
        return Polyline(pts)
    }

    // ---- Overpass --------------------------------------------------------------------------

    /** Overpass `out body geom` answer: ways with node ids, so junctions are exact. */
    fun overpass(root: JSONObject): ParsedSource {
        val lines = ArrayList<Polyline>()
        val els = root.getJSONArray("elements")
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            if (e.optString("type") != "way") continue
            val geom = e.optJSONArray("geometry") ?: continue
            val ids = e.optJSONArray("nodes") ?: continue
            if (geom.length() != ids.length() || geom.length() < 2) continue
            val pts = ArrayList<LatLon>(geom.length())
            val idl = ArrayList<Long>(geom.length())
            var ok = true
            for (j in 0 until geom.length()) {
                val p = geom.optJSONObject(j)
                if (p == null) { ok = false; break } // node outside the query bbox
                pts.add(LatLon(p.getDouble("lat"), p.getDouble("lon")))
                idl.add(ids.getLong(j))
            }
            if (!ok) continue
            val tags = e.optJSONObject("tags")
            if (!walkable(tags, idl.first() == idl.last())) continue
            val flags = HashSet<String>()
            if (tags != null) {
                if (tags.optString("highway") == "steps") flags.add("steps")
                if (tags.has("tunnel") || tags.optString("layer").startsWith("-") || tags.optString("location") == "underground") flags.add("underground")
            }
            lines.add(Polyline(pts, idl, flags = flags))
        }
        return ParsedSource(lines, emptyList())
    }

    /** Keeps ways a pedestrian walks along; drops rails, pipes and outlines of rooms or columns. */
    private fun walkable(tags: JSONObject?, closed: Boolean): Boolean {
        if (tags == null) return false
        if (tags.optString("area") == "yes") return false
        val indoor = tags.optString("indoor")
        if (tags.has("highway")) return !(closed && indoor.isNotEmpty())
        return indoor == "corridor" && !closed
    }

    // ---- GPX / KML -------------------------------------------------------------------------

    private fun xml(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    }.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement

    private fun Element.children(tag: String): List<Element> {
        val nl = getElementsByTagName(tag)
        return (0 until nl.length).map { nl.item(it) as Element }
    }

    private fun Element.childText(tag: String): String? {
        val nl = childNodes
        for (i in 0 until nl.length) {
            val n = nl.item(i)
            if (n is Element && n.tagName.substringAfter(':') == tag) return n.textContent.trim()
        }
        return null
    }

    fun gpx(bytes: ByteArray): ParsedSource {
        val root = xml(bytes)
        val lines = ArrayList<Polyline>()
        fun pts(parent: Element, tag: String) = parent.children(tag).map {
            LatLon(it.getAttribute("lat").toDouble(), it.getAttribute("lon").toDouble())
        }
        for (seg in root.children("trkseg")) lines.add(Polyline(pts(seg, "trkpt")))
        for (rte in root.children("rte")) lines.add(Polyline(pts(rte, "rtept")))
        val marks = root.children("wpt").mapNotNull { w ->
            val name = w.childText("name") ?: return@mapNotNull null
            Landmark(LatLon(w.getAttribute("lat").toDouble(), w.getAttribute("lon").toDouble()), name)
        }
        return ParsedSource(lines.filter { it.pts.size >= 2 }, marks)
    }

    fun kml(bytes: ByteArray): ParsedSource {
        val root = xml(bytes)
        val lines = ArrayList<Polyline>()
        val marks = ArrayList<Landmark>()
        fun parseCoords(text: String) = text.trim().split(Regex("\\s+")).mapNotNull { t ->
            val p = t.split(',')
            if (p.size < 2) null else LatLon(p[1].toDouble(), p[0].toDouble())
        }
        for (pm in root.children("Placemark")) {
            for (ls in pm.children("LineString")) ls.childText("coordinates")?.let { lines.add(Polyline(parseCoords(it))) }
            for (pg in pm.children("outerBoundaryIs")) for (lr in pg.children("LinearRing")) {
                lr.childText("coordinates")?.let { lines.add(Polyline(parseCoords(it))) }
            }
            val name = pm.childText("name")
            if (name != null) for (pt in pm.children("Point")) {
                pt.childText("coordinates")?.let { parseCoords(it).firstOrNull() }?.let { marks.add(Landmark(it, name)) }
            }
        }
        return ParsedSource(lines.filter { it.pts.size >= 2 }, marks)
    }
}
