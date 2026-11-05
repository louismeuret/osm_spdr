package org.spdr.companion.core

import org.junit.Assert.assertEquals
import org.junit.Test
import org.spdr.companion.core.graph.GraphBuilder
import org.spdr.companion.core.io.GeoParsers

class GeoParsersTest {

    @Test
    fun gpxTracksRoutesAndWaypoints() {
        val gpx = """<?xml version="1.0"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <wpt lat="48.8340" lon="2.3320"><name>Salle Z</name></wpt>
              <trk><trkseg><trkpt lat="48.8330" lon="2.3320"/><trkpt lat="48.8350" lon="2.3320"/></trkseg></trk>
              <rte><rtept lat="48.8340" lon="2.3310"/><rtept lat="48.8340" lon="2.3330"/></rte>
            </gpx>""".toByteArray()
        val p = GeoParsers.parse("a.gpx", gpx)
        assertEquals(2, p.lines.size)
        assertEquals("Salle Z", p.landmarks.single().name)
        // the two lines cross; the landmark sits on them: one 4-way junction named "Salle Z"
        val g = GraphBuilder().build(p.lines, p.landmarks)
        assertEquals(4, g.edges.size)
        assertEquals("Salle Z", g.nodes.single { it.incident.size == 4 }.name)
    }

    @Test
    fun kmlLinesPolygonsAndPoints() {
        val kml = """<?xml version="1.0" encoding="UTF-8"?>
            <kml xmlns="http://www.opengis.net/kml/2.2"><Document>
              <Placemark><name>G1</name><LineString><coordinates>2.33,48.83,0 2.331,48.83,0</coordinates></LineString></Placemark>
              <Placemark><name>Room</name><Polygon><outerBoundaryIs><LinearRing><coordinates>
                2.34,48.84 2.341,48.84 2.341,48.841 2.34,48.84</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>
              <Placemark><name>Puits</name><Point><coordinates>2.3305,48.83</coordinates></Point></Placemark>
            </Document></kml>""".toByteArray()
        val p = GeoParsers.parse("a.kml", kml)
        assertEquals(2, p.lines.size)
        assertEquals(listOf("Puits"), p.landmarks.map { it.name })
    }

    @Test
    fun geoJsonAndOverpass() {
        val gj = """{"type":"FeatureCollection","features":[
            {"type":"Feature","properties":{},"geometry":{"type":"MultiLineString","coordinates":[[[2.33,48.83],[2.331,48.83]],[[2.331,48.83],[2.331,48.831]]]}},
            {"type":"Feature","properties":{"name":"Salle"},"geometry":{"type":"Point","coordinates":[2.3305,48.83]}},
            {"type":"Feature","properties":{"name":"Lost"},"geometry":null}]}""".toByteArray()
        val p = GeoParsers.parse("a.geojson", gj)
        assertEquals(2, p.lines.size)
        assertEquals(1, p.landmarks.size)

        val op = """{"elements":[
            {"type":"way","id":1,"nodes":[10,11,12],"tags":{"highway":"footway","tunnel":"yes"},
             "geometry":[{"lat":48.83,"lon":2.33},{"lat":48.8305,"lon":2.33},{"lat":48.831,"lon":2.33}]},
            {"type":"way","id":2,"nodes":[11,13],"tags":{"highway":"steps"},
             "geometry":[{"lat":48.8305,"lon":2.33},{"lat":48.8305,"lon":2.331}]},
            {"type":"way","id":3,"nodes":[20,21],"tags":{"railway":"subway","tunnel":"yes"},
             "geometry":[{"lat":48.83,"lon":2.34},{"lat":48.84,"lon":2.34}]},
            {"type":"way","id":4,"nodes":[30,31,32,30],"tags":{"indoor":"room","level":"-1"},
             "geometry":[{"lat":48.83,"lon":2.35},{"lat":48.83,"lon":2.351},{"lat":48.831,"lon":2.351},{"lat":48.83,"lon":2.35}]}]}"""
        val o = GeoParsers.parse("overpass.json", op.toByteArray())
        assertEquals("subway and room outline are dropped", 2, o.lines.size)
        val g = GraphBuilder().build(o.lines)
        // shared OSM node 11 makes a T junction
        assertEquals(3, g.nodes.maxOf { it.incident.size })
        assertEquals(setOf("10", "11", "12", "13"), g.nodes.map { it.id }.toSet())
    }
}
