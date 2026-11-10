package us.tractat.bee.host

import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64

/**
 * Raw HTTP to a real host on 127.0.0.1:[port], so a test writes each header a browser would,
 * `Host` and `Origin` included, and reads back only the status code: `101` for an upgraded
 * WebSocket, `403` for a gate's refusal.
 */
internal class Handshakes(private val port: Int) {
    /** The status of a WebSocket handshake for [path], with [host], if any, [origin], and these other [headers], each `name: value`. */
    fun upgrade(path: String, host: String = "127.0.0.1:$port", origin: String? = null, vararg headers: String): String {
        val key = Base64.getEncoder().encodeToString(ByteArray(16).also(SecureRandom()::nextBytes))
        return exchange(
            "GET $path HTTP/1.1\r\n" +
                "Host: $host\r\n" +
                (origin?.let { "Origin: $it\r\n" } ?: "") +
                headers.joinToString("") { "$it\r\n" } +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n",
        )
    }

    /** The status of a plain `GET` of [path], with [host] and these other [headers], each `name: value`. */
    fun get(path: String, host: String = "127.0.0.1:$port", vararg headers: String): String =
        exchange("GET $path HTTP/1.1\r\nHost: $host\r\n" + headers.joinToString("") { "$it\r\n" } + "Connection: close\r\n\r\n")

    /** The status and body of a plain `GET` of [path], with [host] and these other [headers], each `name: value`. */
    fun fetch(path: String, host: String, vararg headers: String): Pair<String, String> = Socket().use { socket ->
        send(socket, "GET $path HTTP/1.1\r\nHost: $host\r\n" + headers.joinToString("") { "$it\r\n" } + "Connection: close\r\n\r\n")
        val response = socket.getInputStream().readAllBytes().decodeToString()
        response.substringBefore("\r\n").split(' ')[1] to response.substringAfter("\r\n\r\n")
    }

    /** The status and body of a `POST` of [body] to [path], with [host] and these other [headers], each `name: value`. */
    fun post(path: String, host: String, body: String, vararg headers: String): Pair<String, String> = Socket().use { socket ->
        val bytes = body.encodeToByteArray()
        send(socket, "POST $path HTTP/1.1\r\nHost: $host\r\n" + headers.joinToString("") { "$it\r\n" } + "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n$body")
        val response = socket.getInputStream().readAllBytes().decodeToString()
        response.substringBefore("\r\n").split(' ')[1] to response.substringAfter("\r\n\r\n")
    }

    /** The status and body of a chunked `POST` of [chunks] to [path], with no `Content-Length`, with [host] and these other [headers]. */
    fun postChunked(path: String, host: String, chunks: List<String>, vararg headers: String): Pair<String, String> = Socket().use { socket ->
        val body = chunks.joinToString("") { "${it.encodeToByteArray().size.toString(16)}\r\n$it\r\n" } + "0\r\n\r\n"
        send(socket, "POST $path HTTP/1.1\r\nHost: $host\r\n" + headers.joinToString("") { "$it\r\n" } + "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n$body")
        val response = socket.getInputStream().readAllBytes().decodeToString()
        response.substringBefore("\r\n").split(' ')[1] to response.substringAfter("\r\n\r\n")
    }

    /**
     * The status of a chunked `POST` to [path] that sends [chunks] and then holds its body open,
     * never sending the last chunk, with [host] and these other [headers]: a status arrives only if
     * the host answers before the body ends. If none comes within [waitMs], the host was still
     * reading, and this returns null.
     */
    fun postChunkedHeldOpen(path: String, host: String, chunks: List<String>, waitMs: Int, vararg headers: String): String? = Socket().use { socket ->
        val body = chunks.joinToString("") { "${it.encodeToByteArray().size.toString(16)}\r\n$it\r\n" }
        send(socket, "POST $path HTTP/1.1\r\nHost: $host\r\n" + headers.joinToString("") { "$it\r\n" } + "Transfer-Encoding: chunked\r\n\r\n$body")
        socket.soTimeout = waitMs
        try {
            socket.getInputStream().statusCode()
        } catch (_: java.net.SocketTimeoutException) {
            null
        }
    }

    private fun send(socket: Socket, request: String) {
        socket.connect(InetSocketAddress("127.0.0.1", port), TIMEOUT_MS)
        socket.soTimeout = TIMEOUT_MS
        socket.getOutputStream().apply {
            write(request.encodeToByteArray())
            flush()
        }
    }

    private fun exchange(request: String): String = Socket().use { socket ->
        socket.connect(InetSocketAddress("127.0.0.1", port), TIMEOUT_MS)
        socket.soTimeout = TIMEOUT_MS
        socket.getOutputStream().apply {
            write(request.encodeToByteArray())
            flush()
        }
        socket.getInputStream().statusCode()
    }

    companion object {
        const val SWITCHING = "101"
        const val OK = "200"
        const val FORBIDDEN = "403"
        const val NOT_FOUND = "404"

        private const val TIMEOUT_MS = 10_000

        /** The status code of the response's first line, `HTTP/1.1 <code> <reason>`. */
        private fun InputStream.statusCode(): String {
            val line = StringBuilder()
            while (!line.endsWith("\r\n")) line.append(read().also { check(it >= 0) { "the host hung up before a status: $line" } }.toChar())
            return line.split(' ')[1]
        }
    }
}
