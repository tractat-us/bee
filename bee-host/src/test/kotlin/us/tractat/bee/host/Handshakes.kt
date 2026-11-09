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

    /**
     * Opens a server-sent event stream at [path], with [host] and these other [headers], each
     * `name: value`, and stays on it until the returned stream is closed: a device on its pairing channel.
     */
    fun events(path: String, host: String, vararg headers: String): Events {
        val socket = Socket()
        send(socket, "GET $path HTTP/1.1\r\nHost: $host\r\nAccept: text/event-stream\r\n" + headers.joinToString("") { "$it\r\n" } + "\r\n")
        return Events(socket)
    }

    /** An open event stream: [next] reads the next event's `data`, whatever framing the body has. */
    class Events(private val socket: Socket) : AutoCloseable {
        private val lines = socket.getInputStream().bufferedReader()

        fun next(): String = generateSequence { lines.readLine() }.first { it.startsWith("data:") }.removePrefix("data:").trim()

        override fun close() = socket.close()
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

        private const val TIMEOUT_MS = 10_000

        /** The status code of the response's first line, `HTTP/1.1 <code> <reason>`. */
        private fun InputStream.statusCode(): String {
            val line = StringBuilder()
            while (!line.endsWith("\r\n")) line.append(read().also { check(it >= 0) { "the host hung up before a status: $line" } }.toChar())
            return line.split(' ')[1]
        }
    }
}
