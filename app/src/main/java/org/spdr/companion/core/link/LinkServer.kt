package org.spdr.companion.core.link

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/** What the link can ask of the app. Failures are short codes the other side can act on. */
interface LinkTarget {
    fun state(): JSONObject
    /** Null when it worked, else a code such as `open-app`. */
    fun start(): String?
    fun stop()
    /** "I am here". Null when it worked, else `no-graph` or `not-near`. */
    fun position(lat: Double, lon: Double, accuracy: Double): String?
    /** Replaces the lines this link sent before. What was built, or `{"error": code}`. */
    fun graph(geoJson: String): JSONObject
}

class LinkRequest(val method: String, val path: String, val origin: String?, val host: String?, val body: String)
class LinkResponse(val status: Int, val body: String = "", val origin: String? = null)

/**
 * A web app can't listen for connections, so the small server lives here and the web app polls it.
 * It answers on the loopback address only, so nothing off the phone can reach it, and only to the
 * pages below: otherwise any website open in the browser could read the position or stop tracking.
 * Requiring both headers also defeats DNS rebinding. Other apps on the phone can reach the port
 * and can fake those headers, which is why it exposes nothing beyond the position and start/stop.
 *
 *   GET  /v1/state     {"app","protocol","running","hasGraph","steps","position":{...}|null}
 *   POST /v1/start     {"ok":true} or 409 {"error":"open-app"}
 *   POST /v1/stop      {"ok":true}
 *   POST /v1/position  {"lat","lon","accuracy"} -> {"ok":true} or 409 {"error":"no-graph"|"not-near"}
 *   POST /v1/graph     GeoJSON -> {"edges","metres"} or 409 {"error":"bad-graph"}
 */
class LinkHandler(private val target: LinkTarget, private val port: Int = PORT) {
    fun handle(request: LinkRequest): LinkResponse {
        val origin = request.origin
        if (origin == null || !allowedOrigin(origin) || !allowedHost(request.host)) return LinkResponse(403)
        fun ok() = LinkResponse(200, """{"ok":true}""", origin)
        fun failed(code: String) = LinkResponse(409, JSONObject().put("error", code).toString(), origin)
        return when ("${request.method} ${request.path}") {
            "GET /v1/state" -> LinkResponse(200, target.state().toString(), origin)
            "POST /v1/start" -> target.start()?.let(::failed) ?: ok()
            "POST /v1/stop" -> { target.stop(); ok() }
            "POST /v1/position" -> {
                val body = runCatching { JSONObject(request.body) }.getOrNull()
                    ?: return LinkResponse(400, origin = origin)
                val lat = body.optDouble("lat"); val lon = body.optDouble("lon")
                val accuracy = body.optDouble("accuracy", 10.0)
                if (!(lat in -90.0..90.0) || !(lon in -180.0..180.0) || !(accuracy > 0))
                    return LinkResponse(400, origin = origin)
                target.position(lat, lon, accuracy)?.let(::failed) ?: ok()
            }
            "POST /v1/graph" -> {
                val built = target.graph(request.body)
                if (built.has("error")) LinkResponse(409, built.toString(), origin) else LinkResponse(200, built.toString(), origin)
            }
            else -> if (request.method == "OPTIONS") LinkResponse(204, origin = origin) else LinkResponse(404, origin = origin)
        }
    }

    /** How much of a body to read, decided before reading it: nothing for a page that can't use it. */
    fun bodyLimit(request: LinkRequest): Int = when {
        request.origin == null || !allowedOrigin(request.origin) || !allowedHost(request.host) -> 0
        request.path == "/v1/graph" -> MAX_GRAPH
        else -> MAX_BODY
    }

    private fun allowedHost(host: String?) = host == "127.0.0.1:$port" || host == "localhost:$port"

    private fun allowedOrigin(origin: String) =
        origin in ORIGINS || LOCAL_DEV.matches(origin)

    companion object {
        const val PORT = 47823
        private const val MAX_BODY = 4 * 1024
        private const val MAX_GRAPH = 32 * 1024 * 1024
        private val ORIGINS = setOf("https://app.mega-maps.fr", "https://dev.mega-maps.fr")
        private val LOCAL_DEV = Regex("""http://(localhost|127\.0\.0\.1):\d+""")
    }
}

/** Blocking accept loop on its own thread. A few workers, so building a graph doesn't hold up the polls. */
class LinkServer(private val handler: LinkHandler, private val port: Int = LinkHandler.PORT) {
    @Volatile private var socket: ServerSocket? = null
    private val workers = Executors.newFixedThreadPool(WORKERS)

    fun start() {
        Thread({ run() }, "spdr-link").apply { isDaemon = true }.start()
    }

    private fun run() {
        while (true) {
            try {
                ServerSocket(port, 8, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { server ->
                    socket = server
                    while (true) {
                        val client = server.accept()
                        workers.execute { client.use { serve(it) } }
                    }
                }
            } catch (e: Exception) {
                // The port is taken, or the socket died: try again rather than lose the link.
                if (e is InterruptedException) return
                runCatching { Thread.sleep(RETRY_MS) }
            }
        }
    }

    private fun serve(client: Socket) {
        val response = try {
            client.soTimeout = TIMEOUT_MS
            read(client.getInputStream(), handler::bodyLimit)?.let(handler::handle) ?: LinkResponse(400)
        } catch (e: java.io.IOException) {
            return
        } catch (e: Exception) {
            LinkResponse(500)
        }
        runCatching { client.getOutputStream().apply { write(encode(response)); flush() } }
    }

    companion object {
        private const val RETRY_MS = 5000L
        private const val TIMEOUT_MS = 2000
        private const val MAX_HEAD = 8 * 1024
        private const val WORKERS = 4

        /** One HTTP/1.1 request, or null if it is malformed or its body is over `limit`. */
        fun read(input: InputStream, limit: (LinkRequest) -> Int): LinkRequest? {
            val head = ByteArrayOutputStream()
            var tail = 0
            while (tail != 0x0d0a0d0a) {
                val b = input.read()
                if (b < 0 || head.size() >= MAX_HEAD) return null
                head.write(b)
                tail = (tail shl 8) or b
            }
            val lines = head.toString("ISO-8859-1").split("\r\n")
            val first = lines[0].split(" ")
            if (first.size != 3) return null
            val headers = lines.drop(1).mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon < 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
            }.toMap()
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val bare = LinkRequest(first[0], first[1], headers["origin"], headers["host"], "")
            if (length < 0 || length > limit(bare)) return if (limit(bare) == 0) bare else null
            val body = ByteArray(length)
            var filled = 0
            while (filled < length) {
                val count = input.read(body, filled, length - filled)
                if (count < 0) return null
                filled += count
            }
            return LinkRequest(first[0], first[1], headers["origin"], headers["host"], String(body, Charsets.UTF_8))
        }

        fun encode(response: LinkResponse): ByteArray {
            val body = response.body.toByteArray(Charsets.UTF_8)
            val head = StringBuilder("HTTP/1.1 ${response.status} ${reason(response.status)}\r\n")
            response.origin?.let {
                head.append("Access-Control-Allow-Origin: $it\r\nVary: Origin\r\n")
                    .append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
                    .append("Access-Control-Allow-Headers: Content-Type\r\n")
                    // Chrome's older private-network check asks for this on the preflight.
                    .append("Access-Control-Allow-Private-Network: true\r\n")
            }
            head.append("Content-Type: application/json\r\nCache-Control: no-store\r\n")
                .append("Content-Length: ${body.size}\r\nConnection: close\r\n\r\n")
            return head.toString().toByteArray(Charsets.ISO_8859_1) + body
        }

        private fun reason(status: Int) = when (status) {
            200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 403 -> "Forbidden"
            404 -> "Not Found"; 409 -> "Conflict"; else -> "Internal Server Error"
        }
    }
}
