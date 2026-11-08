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
    /** The status of a WebSocket handshake for [path], with [host] and, if any, [origin]. */
    fun upgrade(path: String, host: String = "127.0.0.1:$port", origin: String? = null): String {
        val key = Base64.getEncoder().encodeToString(ByteArray(16).also(SecureRandom()::nextBytes))
        return exchange(
            "GET $path HTTP/1.1\r\n" +
                "Host: $host\r\n" +
                (origin?.let { "Origin: $it\r\n" } ?: "") +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n",
        )
    }

    /** The status of a plain `GET` of [path], with [host] and these other [headers], each `name: value`. */
    fun get(path: String, host: String = "127.0.0.1:$port", vararg headers: String): String =
        exchange("GET $path HTTP/1.1\r\nHost: $host\r\n" + headers.joinToString("") { "$it\r\n" } + "Connection: close\r\n\r\n")

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

        private const val TIMEOUT_MS = 10_000

        /** The status code of the response's first line, `HTTP/1.1 <code> <reason>`. */
        private fun InputStream.statusCode(): String {
            val line = StringBuilder()
            while (!line.endsWith("\r\n")) line.append(read().also { check(it >= 0) { "the host hung up before a status: $line" } }.toChar())
            return line.split(' ')[1]
        }
    }
}
