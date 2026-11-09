package us.tractat.bee.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.pingInterval
import us.tractat.bee.core.PEER_PING_INTERVAL
import kotlin.time.Duration

/**
 * The HTTP client a bridge or a companion dials the host with, for its board and claims sockets.
 * Each of its WebSockets pings every [pingInterval], so a link to a host that is gone, with no
 * FIN or RST to say so, closes and is redialled. The caller owns it and closes it.
 */
fun peerHttpClient(pingInterval: Duration = PEER_PING_INTERVAL): HttpClient =
    HttpClient(CIO) { install(WebSockets) { this.pingInterval = pingInterval } }
