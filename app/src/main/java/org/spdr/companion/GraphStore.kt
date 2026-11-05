package org.spdr.companion

import android.content.Context
import org.spdr.companion.core.graph.Graph
import org.spdr.companion.core.graph.GraphBuilder
import org.spdr.companion.core.graph.GraphJson
import org.spdr.companion.core.io.GeoParsers
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Offline storage of the graph inputs. Every input (Overpass download, imported file) is kept
 * verbatim in `sources/`, and the graph is rebuilt from all of them whenever one is added, so
 * imported galleries get welded onto OSM ways and vice versa.
 */
class GraphStore(context: Context) {
    private val sourcesDir = File(context.filesDir, "sources").apply { mkdirs() }
    private val cache = File(context.filesDir, "graph.json")

    fun sources(): List<File> = sourcesDir.listFiles()?.sortedBy { it.name } ?: emptyList()

    fun loadCached(): Graph? = runCatching { if (cache.exists()) GraphJson.read(cache.readText()) else null }.getOrNull()

    fun addSource(name: String, bytes: ByteArray): Graph {
        GeoParsers.parse(name, bytes) // fail early on unreadable files
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        File(sourcesDir, "${System.currentTimeMillis()}_$safe").writeBytes(bytes)
        return rebuild()
    }

    /** Swaps the source of this name for new content, keeping the old one if the graph can't be built. */
    @Synchronized
    fun replaceSource(name: String, bytes: ByteArray): Graph {
        require(GeoParsers.parse(name, bytes).lines.isNotEmpty()) { "no lines" }
        val old = sources().filter { it.name.substringAfter('_') == name }
        val kept = old.map { it to it.readBytes() }
        old.forEach { it.delete() }
        val added = File(sourcesDir, "${System.currentTimeMillis()}_$name")
        return try {
            added.writeBytes(bytes)
            rebuild()
        } catch (e: Exception) {
            added.delete()
            kept.forEach { (file, content) -> file.writeBytes(content) }
            throw e
        }
    }

    fun clear() {
        sources().forEach { it.delete() }
        cache.delete()
    }

    fun rebuild(): Graph {
        val parsed = sources().map { f -> GeoParsers.parse(f.name, f.readBytes()) }
        val lines = parsed.flatMap { it.lines }
        val marks = parsed.flatMap { it.landmarks }
        val g = GraphBuilder().build(lines, marks, sources().joinToString(" + ") { it.name.substringAfter('_') })
        cache.writeText(GraphJson.write(g))
        return g
    }

    enum class OsmFilter(val label: String) {
        UNDERGROUND("Underground only (tunnel, layer<0, level<0, corridors)"),
        WALKABLE("All walkable ways"),
    }

    /** Downloads OSM ways around a point from Overpass (blocking; call off the main thread). */
    fun downloadOverpass(lat: Double, lon: Double, radius: Int, filter: OsmFilter): Graph {
        val around = String.format(Locale.US, "(around:%d,%.7f,%.7f)", radius, lat, lon)
        val body = when (filter) {
            OsmFilter.UNDERGROUND -> {
                val walk = "[\"highway\"~\"^($WALKABLE_HIGHWAYS)$\"]"
                """
                way$walk["tunnel"]["tunnel"!~"^(no|building_passage)$"]$around;
                way$walk["layer"~"^-"]$around;
                way$walk["level"~"^-"]$around;
                way$walk["location"="underground"]$around;
                way["highway"="corridor"]$around;
                way["indoor"="corridor"]$around;
                """
            }
            OsmFilter.WALKABLE -> """
                way["highway"]["highway"!~"^(motorway|motorway_link|trunk|trunk_link|construction|proposed|raceway)$"]["area"!="yes"]$around;
                way["tunnel"]["tunnel"!="no"]$around;
                way["indoor"="corridor"]$around;
            """
        }
        val query = "[out:json][timeout:90];($body);out body geom;"
        var last: Exception? = null
        for (endpoint in OVERPASS) {
            try {
                val conn = URL(endpoint).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 120000
                conn.requestMethod = "POST"
                conn.doOutput = true
                // overpass-api.de answers 406 to requests without a real User-Agent
                conn.setRequestProperty("User-Agent", "spdr-osmand-companion/${BuildConfig.VERSION_NAME} (Android)")
                conn.setRequestProperty("Accept", "application/json")
                conn.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
                if (conn.responseCode != 200) throw IllegalStateException("Overpass HTTP ${conn.responseCode}")
                val bytes = conn.inputStream.use { it.readBytes() }
                if (GeoParsers.parse("x.json", bytes).lines.isEmpty()) throw IllegalStateException("no matching ways within $radius m")
                return addSource(String.format(Locale.US, "overpass_%s_%.5f_%.5f_%dm.json", filter.name.lowercase(), lat, lon, radius), bytes)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("Overpass unreachable")
    }

    companion object {
        private const val WALKABLE_HIGHWAYS =
            "footway|path|steps|corridor|pedestrian|track|service|living_street|cycleway|bridleway|unclassified|residential"
        private val OVERPASS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
        )

        /** The graph as a GPX (one segment per edge, nodes with names as waypoints), for OsmAnd. */
        fun toGpx(g: Graph): String {
            val p = g.projection
            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            sb.append("<gpx version=\"1.1\" creator=\"sPDR\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            for (n in g.nodes) if (n.name != null) {
                sb.append(String.format(Locale.US, "<wpt lat=\"%.7f\" lon=\"%.7f\"><name>%s</name></wpt>\n",
                    p.toLat(n.y), p.toLon(n.x), esc(n.name)))
            }
            sb.append("<trk><name>sPDR graph</name>\n")
            for (e in g.edges) {
                sb.append("<trkseg>")
                for (i in e.xs.indices) sb.append(String.format(Locale.US, "<trkpt lat=\"%.7f\" lon=\"%.7f\"/>", p.toLat(e.ys[i]), p.toLon(e.xs[i])))
                sb.append("</trkseg>\n")
            }
            sb.append("</trk>\n</gpx>\n")
            return sb.toString()
        }

        private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
