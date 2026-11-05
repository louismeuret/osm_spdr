package org.spdr.companion

import android.Manifest
import android.content.pm.PackageManager
import org.json.JSONObject
import org.spdr.companion.core.link.LinkHandler
import org.spdr.companion.core.link.LinkServer
import org.spdr.companion.core.link.LinkTarget

/** Answers web apps such as Mega Maps from the engine; see [LinkHandler] for the protocol. */
class LinkBridge(private val app: SpdrApp) : LinkTarget {
    private companion object { const val SOURCE = "mega-maps.geojson" }

    fun listen() = LinkServer(LinkHandler(this)).start()

    override fun state(): JSONObject {
        val s = app.engine.snapshot()
        val best = s.estimate?.best
        val position = best?.let { b ->
            s.toLatLon(b.x, b.y)?.let { at ->
                JSONObject().put("lat", at.lat).put("lon", at.lon)
                    // The same accuracy and bearing OsmAnd is given.
                    .put("accuracy", 3 + 1.5 * b.spread + 30 * (1 - b.weight))
                    .put("heading", ((90 - Math.toDegrees(b.heading)) % 360 + 360) % 360)
                    .put("confidence", b.weight)
                    .put("relocalising", s.estimate?.holding == true)
            }
        }
        return JSONObject().put("app", "sPDR").put("protocol", 1)
            .put("running", s.sensorsOn).put("hasGraph", s.graph != null)
            .put("steps", s.stepsDetected).put("position", position ?: JSONObject.NULL)
    }

    /** Android refuses to start tracking from the background: the user has to open the app. */
    override fun start(): String? {
        if (app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return "open-app"
        return runCatching { SpdrService.start(app) }.fold({ null }, { "open-app" })
    }

    override fun stop() { SpdrService.stop(app) }

    /** Merged with the app's other sources, and any running estimate carries over. */
    override fun graph(geoJson: String): JSONObject = try {
        val g = app.store.replaceSource(SOURCE, geoJson.toByteArray())
        app.engine.setGraph(g)
        JSONObject().put("edges", g.edges.size).put("metres", g.totalLength)
    } catch (e: Exception) {
        JSONObject().put("error", "bad-graph")
    }

    override fun position(lat: Double, lon: Double, accuracy: Double): String? {
        val graph = app.engine.graph ?: return "no-graph"
        val x = graph.projection.toX(lon); val y = graph.projection.toY(lat)
        val near = graph.nearest(x, y, 100.0) ?: return "not-near"
        val edge = graph.edges[near.edge]
        app.engine.fixPosition(edge.x(near.s), edge.y(near.s), accuracy.coerceIn(3.0, 30.0), "link")
        return null
    }
}
