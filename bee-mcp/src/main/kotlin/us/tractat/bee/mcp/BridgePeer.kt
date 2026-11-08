package us.tractat.bee.mcp

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import us.tractat.bee.core.BoardClient
import us.tractat.bee.core.BoardPin
import us.tractat.bee.core.ClaimsConnection
import us.tractat.bee.core.ClaimsLink
import us.tractat.bee.core.HostAddress
import us.tractat.bee.core.Presence
import us.tractat.bee.core.SpokeLoom
import us.tractat.bee.core.askBoardId
import us.tractat.bee.core.pinnedTo
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.websocket.WebSocketAdvertisement
import kotlin.random.Random
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// Lazy, as in Main.kt: stdio's main points System.out at stderr before any logger may start.
private val logger by lazy { KotlinLogging.logger {} }

/**
 * One bridge joined to a host: invariant — **every part of it runs on the one scope it was joined
 * on**, so cancelling that scope ends the whole peer. Its [bridge] holds a replica of the board
 * (`/ws/board`), a claims learner (`/ws/claims`), an identity and its presence, each built the way
 * a bridge has always built them (spec §5.2). stdio's `main` joins one per process; the host joins
 * one per MCP session over HTTP ([HttpBridges]). Either way the session is the same kind of peer.
 *
 * @param board the bridge's replica, already connecting
 * @param pin the board the replica belongs to, learned from the host on the first dial
 * @param identity the bridge's identity, which `/clear` retires (stdio's [ClearControl])
 */
class BridgePeer private constructor(val bridge: BeeBridge, val board: BoardClient, val pin: BoardPin, val identity: BridgeIdentity) {
    companion object {
        /**
         * Joins a bridge named [name] to [host], dialling with [http], its work on [scope]. A
         * retired identity's `Depart` goes through Raft. The replica starts connecting before this
         * returns; call [BeeBridge.announce] to post its presence at once.
         */
        @OptIn(ExperimentalUuidApi::class)
        @Suppress("LongParameterList")
        fun join(host: HostAddress, name: String, http: HttpClient, scope: CoroutineScope, random: Random, clock: () -> Instant): BridgePeer {
            val author = ReplicaId("mcp-${Uuid.random()}")
            // The bridge's replica starts empty, so it belongs to the first board the host names; both
            // its sockets name that board from then on, and a wiped host refuses them (bee#46).
            val pin = BoardPin.learnedFrom { http.askBoardId(host) }
            val board = BoardClient(
                author,
                SpokeLoom(http, Dispatchers.Default, scope, author.value, random, clock).pinnedTo(pin),
                Rendezvous.Existing(WebSocketAdvertisement(host.socket("/ws/board"), PeerId("bee-host"), "board")),
                scope,
                clock,
            )
            // TODO(kuilt#3110): one refused dial ends clusterClient's reconnect loop for good, so the
            // connection rebuilds a failed link, under a fresh node each time (kuilt keeps a NodeId unique).
            val claims = ClaimsConnection(
                scope = scope,
                nextNode = { NodeId("mcp-${Uuid.random()}") },
                open = { node, linkScope -> ClaimsLink.overWebSocket(node, http, host, pin, linkScope, clock, random) },
                // A dial's failure can name the URL it dialled, key and all: log it as the host describes it.
                onFailure = { failure -> logger.warn { "The claims link failed; rebuilding it. ${host.describe(failure)}" } },
                retryDelay = ClaimsConnection.jitteredBackoff(random),
            )
            val identity = BridgeIdentity(name, random) { old, reason ->
                val committed = claims.depart(old, reason)
                logger.info { "Retired $old ($reason); its Depart ${if (committed) "committed" else "is pending until claims are back"}." }
            }
            val presence = Presence(board, author, claims.link.filterNotNull().map { it.node }, clock, scope) { failure ->
                logger.warn(failure) { "Presence tracking failed." }
            }
            val bridge = BeeBridge(board, author, claims, identity, presence, clock, scope)
            board.connect()
            return BridgePeer(bridge, board, pin, identity)
        }
    }
}
