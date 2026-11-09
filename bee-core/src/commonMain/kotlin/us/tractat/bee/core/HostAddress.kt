package us.tractat.bee.core

import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLQueryComponent
import io.ktor.http.encodedPath
import io.ktor.http.parseUrl
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.websocket.WebSocketAdvertisement

/**
 * Where a bee host is, for a peer that dials it from a process (the bridge, a companion): its
 * root, and the key its sockets carry if it has one. Invariant: **every socket dialled through
 * this address carries its key, and nothing prints the key.** [toString] says only that there is
 * one, and a failure from one of its sockets, which can name the URL it dialled, is logged as
 * [describe] renders it, with the key [redact]ed.
 *
 * A key is what lets a peer off the host's machine past the socket gate: the host refuses a
 * non-loopback socket without one in its `?k=`. A `--lan` host prints a join link that holds one,
 * and [parse] takes that link as it is pasted.
 */
class HostAddress private constructor(private val root: Url, private val key: String?) {
    /** The host's root over HTTP or HTTPS, `http://host:port`: where [askBoardId] asks, and where the page is. */
    val http: String get() = root.toString()

    /**
     * The WebSocket at [path] (`/ws/board`) on this host: `ws://` under `http://`, `wss://` under
     * `https://`, with the key in its `?k=` if this address has one.
     */
    fun socket(path: String): String = URLBuilder(root).apply {
        protocol = if (root.protocol == URLProtocol.HTTPS) URLProtocol.WSS else URLProtocol.WS
        encodedPath = path
        key?.let { parameters.append(PairingWire.KEY_PARAM, it) }
    }.buildString()

    /**
     * Where a client dials the board: the host's `/ws/board` socket, key included, as the one
     * existing rendezvous. The bridge, the companion, the page and the app all dial the board
     * through this, so the socket's path and its key change in one place.
     */
    fun boardRendezvous(): Rendezvous =
        Rendezvous.Existing(WebSocketAdvertisement(socket(BOARD_PATH), PeerId(BOARD_HOST_PEER), BOARD_SESSION))

    /** [text] with this address's key, as given or URL-encoded, replaced by `…` wherever it appears. */
    fun redact(text: String): String =
        key?.let { listOf(it.encodeURLParameter(), it.encodeURLQueryComponent(), it).distinct() }.orEmpty()
            .fold(text) { redacted, written -> redacted.replace(written, REDACTED) }

    /**
     * [failure] as a log line may show it: each throwable in its cause chain, its class and its
     * [redact]ed message. A dial's failure can name the URL it dialled, `?k=` and all (Ktor CIO's
     * connect timeout does), so a failure from this address's sockets is logged through this,
     * never as the raw throwable.
     */
    fun describe(failure: Throwable): String =
        generateSequence(failure) { it.cause.takeUnless { cause -> cause === it } }
            .take(MAX_CAUSES)
            .joinToString("; caused by ") { "${it::class.simpleName}: ${redact(it.message.orEmpty())}" }

    override fun toString(): String = if (key == null) http else "$http, with a join key"

    companion object {
        /** The board relay's WebSocket route. */
        const val BOARD_PATH: String = "/ws/board"

        /** The host's peer id on the board socket. */
        private const val BOARD_HOST_PEER = "bee-host"

        /** The session name the board socket's advertisement carries. */
        private const val BOARD_SESSION = "board"

        /** What [redact] puts where the key was. */
        private const val REDACTED = "…"

        /** How much of a cause chain [describe] shows: a cycle can't make it endless. */
        private const val MAX_CAUSES = 16

        private val SCHEMES = mapOf(
            "ws" to URLProtocol.HTTP,
            "http" to URLProtocol.HTTP,
            "wss" to URLProtocol.HTTPS,
            "https" to URLProtocol.HTTPS,
        )

        /**
         * The host [text] names: `ws://host:port`, `wss://host:port`, `http://host:port/`,
         * `https://host:port/`, or a join link, `http://host:port/?k=<key>`. A trailing `/` is
         * optional. A WebSocket scheme names the same host over HTTP: `ws` is `http`, `wss` is
         * `https`.
         *
         * @throws IllegalArgumentException if [text] is none of those: another scheme, no host, a
         *   path, a fragment, a query other than one non-empty `k`. The message never holds the key.
         */
        fun parse(text: String): HostAddress {
            val trimmed = text.trim()
            val scheme = trimmed.substringBefore("://", missingDelimiterValue = "").lowercase()
            val protocol = requireNotNull(SCHEMES[scheme]) {
                "\"${shown(trimmed)}\" isn't a bee host: give ws://, wss://, http:// or https:// and the host's name or address, or the join link the host prints."
            }
            val url = requireNotNull(parseUrl(trimmed)) { "\"${shown(trimmed)}\" isn't a URL." }
            require(url.host.isNotEmpty()) { "\"${shown(trimmed)}\" names no host." }
            require(url.encodedPath.isEmpty() || url.encodedPath == "/") {
                "\"${shown(trimmed)}\" names a path, ${url.encodedPath}: give the host's root, or its join link."
            }
            require(url.fragment.isEmpty()) { "\"${shown(trimmed)}\" has a fragment: give the host's root, or its join link." }
            val others = url.parameters.names() - PairingWire.KEY_PARAM
            require(others.isEmpty()) { "\"${shown(trimmed)}\" carries ${others.sorted().joinToString()}: a join link carries only its key, ${PairingWire.KEY_PARAM}." }
            val keys = url.parameters.getAll(PairingWire.KEY_PARAM).orEmpty()
            require(keys.size <= 1) { "\"${shown(trimmed)}\" carries more than one key." }
            require(keys.all { it.isNotBlank() }) { "\"${shown(trimmed)}\" carries an empty key: copy the whole join link." }
            // A link cut off at `?k` or `?` parses with no key at all, and would meet the gate's 403 on every dial.
            require('?' !in trimmed || keys.size == 1) { "\"${shown(trimmed)}\" ends where its key should be: copy the whole join link." }
            val root = URLBuilder(protocol = protocol, host = url.host, port = url.specifiedPort).build()
            return HostAddress(root, keys.singleOrNull())
        }

        /** [text] as a message may show it: without its query or fragment, so never with a key. */
        private fun shown(text: String): String = text.substringBefore('?').substringBefore('#')
    }
}
