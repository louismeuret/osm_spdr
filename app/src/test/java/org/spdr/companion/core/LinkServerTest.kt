package org.spdr.companion.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.spdr.companion.core.link.LinkHandler
import org.spdr.companion.core.link.LinkRequest
import org.spdr.companion.core.link.LinkServer
import org.spdr.companion.core.link.LinkTarget
import java.io.ByteArrayInputStream

class LinkServerTest {
    private class Fake : LinkTarget {
        var stopped = false
        var fix: Triple<Double, Double, Double>? = null
        override fun state() = JSONObject().put("running", true)
        override fun start() = "open-app"
        override fun stop() { stopped = true }
        override fun position(lat: Double, lon: Double, accuracy: Double): String? {
            fix = Triple(lat, lon, accuracy); return null
        }
        override fun graph(geoJson: String) =
            if (geoJson == "bad") JSONObject().put("error", "bad-graph") else JSONObject().put("edges", 3)
    }
    private val fake = Fake()
    private val handler = LinkHandler(fake)
    private val host = "127.0.0.1:${LinkHandler.PORT}"
    private val app = "https://app.mega-maps.fr"
    private fun call(method: String, path: String, origin: String? = app, h: String = host, body: String = "") =
        handler.handle(LinkRequest(method, path, origin, h, body))

    @Test fun `other websites get nothing and change nothing`() {
        assertEquals(403, call("POST", "/v1/stop", "https://evil.example").status)
        assertEquals(403, call("POST", "/v1/stop", null).status)
        assertEquals(false, fake.stopped)
    }

    @Test fun `a rebound hostname is refused even from an allowed origin`() {
        assertEquals(403, call("GET", "/v1/state", h = "evil.example:47823").status)
    }

    @Test fun `the app reads the state with its origin echoed for CORS`() {
        val response = call("GET", "/v1/state")
        assertEquals(200, response.status)
        assertEquals(app, response.origin)
        assertEquals(true, JSONObject(response.body).getBoolean("running"))
    }

    @Test fun `a preflight is answered`() {
        assertEquals(204, call("OPTIONS", "/v1/position").status)
    }

    @Test fun `a position is passed on and nonsense refused`() {
        assertEquals(200, call("POST", "/v1/position", body = """{"lat":48.8,"lon":2.3,"accuracy":10}""").status)
        assertEquals(Triple(48.8, 2.3, 10.0), fake.fix)
        fake.fix = null
        assertEquals(400, call("POST", "/v1/position", body = """{"lat":148.8,"lon":2.3}""").status)
        assertEquals(400, call("POST", "/v1/position", body = "not json").status)
        assertNull(fake.fix)
    }

    @Test fun `a failure says what to do`() {
        val response = call("POST", "/v1/start")
        assertEquals(409, response.status)
        assertEquals("open-app", JSONObject(response.body).getString("error"))
    }

    @Test fun `requests parse off the wire, and oversized ones are dropped`() {
        val raw = "POST /v1/position HTTP/1.1\r\nHost: $host\r\nOrigin: $app\r\nContent-Length: 2\r\n\r\n{}"
        val request = LinkServer.read(ByteArrayInputStream(raw.toByteArray()), handler::bodyLimit)!!
        assertEquals("POST", request.method); assertEquals(app, request.origin)
        assertEquals(host, request.host); assertEquals("{}", request.body)
        val huge = "POST /v1/stop HTTP/1.1\r\nHost: $host\r\nOrigin: $app\r\nContent-Length: 99999\r\n\r\n"
        assertNull(LinkServer.read(ByteArrayInputStream(huge.toByteArray()), handler::bodyLimit))
    }

    @Test fun `lines can be large for the app, and a stranger's aren't even read`() {
        fun raw(origin: String, length: Int) = "POST /v1/graph HTTP/1.1\r\nHost: $host\r\nOrigin: $origin\r\nContent-Length: $length\r\n\r\n" + "x".repeat(length)
        val big = LinkServer.read(ByteArrayInputStream(raw(app, 100_000).toByteArray()), handler::bodyLimit)!!
        assertEquals(100_000, big.body.length)
        val stranger = LinkServer.read(ByteArrayInputStream(raw("https://evil.example", 100_000).toByteArray()), handler::bodyLimit)!!
        assertEquals("", stranger.body)
        assertEquals(403, handler.handle(stranger).status)
    }

    @Test fun `a graph answers what was built, or why not`() {
        assertEquals(3, JSONObject(call("POST", "/v1/graph", body = "{}").body).getInt("edges"))
        assertEquals(409, call("POST", "/v1/graph", body = "bad").status)
    }
}
