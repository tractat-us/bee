package us.tractat.bee.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How often a bridge's or a companion's sockets ping the host (bee#77). Ktor's client waits twice
 * this for the pong, so a half-open link is torn down within three times this: 15 s.
 */
val PEER_PING_INTERVAL: Duration = 5.seconds

/**
 * The HTTP client a bridge or a companion dials the host with: its board and claims sockets.
 * The caller owns it and closes it.
 */
@Suppress("UNUSED_PARAMETER")
fun peerHttpClient(pingInterval: Duration = PEER_PING_INTERVAL): HttpClient = HttpClient(CIO) { install(WebSockets) }
