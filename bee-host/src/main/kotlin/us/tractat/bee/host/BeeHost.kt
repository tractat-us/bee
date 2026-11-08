package us.tractat.bee.host

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.LivePresence
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.websocket.KtorConnectionSource
import us.tractat.kuilt.websocket.KtorRoomHost
import java.nio.file.Files
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val log = KotlinLogging.logger("us.tractat.bee.host.BeeHost")

/**
 * The always-on host (spec §5.4): it serves the page at `/`, relays the board at
 * [BoardRelay.PATH], and runs the claims voter at [CLAIMS_PATH]. Invariant: **the host writes
 * to the board only through its own replica**; it has no write route. Its writes are the claims
 * mirror and the departed list, from the voter's fold, and nothing else.
 *
 * The Netty server is never parented to a caller's coroutine (kuilt has hung on an
 * `embeddedServer` parented to `runBlocking`), so [close] stops it. Everything else runs on the
 * scope given to [start], which the caller cancels.
 */
class BeeHost private constructor(
    /** The bound port: pass `port = 0` for an ephemeral one. */
    val port: Int,
    private val relay: BoardRelay,
    /** The claims voter at [CLAIMS_PATH]. */
    val voter: ClaimsVoter,
    private val departures: Job,
    private val server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>,
) : AutoCloseable {
    /** The host's CRDT author, fresh per boot. A [mutate] transform names it. */
    val author: ReplicaId get() = relay.replica

    /** The host's merged board. */
    val state: StateFlow<BoardState> get() = relay.state

    /** Writes to the board through the host's replica, as [author]. Suspends for Task 9's store. */
    @Suppress("RedundantSuspendModifier")
    suspend fun mutate(f: (BoardState) -> Patch<BoardState>) = relay.mutate(f)

    override fun close() {
        runBlocking {
            departures.cancelAndJoin()
            voter.close()
        }
        relay.close()
        server.stop(gracePeriodMillis = 100, timeoutMillis = 2_000)
    }

    companion object {
        /** The claims voter's WebSocket route, its room's session name, and its server peer id. */
        const val CLAIMS_PATH: String = "/ws/claims"
        const val CLAIMS_SESSION: String = "claims"
        val CLAIMS_SERVER: PeerId = PeerId("bee-host")

        /**
         * Starts a host on [config]'s address. Background work runs on [scope]. [Departures]
         * reads a crash off the claims voter: a node it drops after the learner grace
         * ([claimsLinkLoss], bee#28).
         */
        @OptIn(ExperimentalUuidApi::class)
        suspend fun start(
            scope: CoroutineScope,
            config: HostConfig,
        ): BeeHost {
            // Task 9 keeps the board here. Until then the host only makes sure the directory exists.
            Files.createDirectories(config.dataDir)
            val clock = { Clock.System.now() }
            lateinit var source: KtorConnectionSource
            lateinit var claimsHost: KtorRoomHost
            val server = embeddedServer(Netty, port = config.port, host = config.bind) {
                source = KtorConnectionSource(this, BoardRelay.PATH)
                claimsHost = KtorRoomHost(this, CLAIMS_PATH, CLAIMS_SERVER, Pattern(CLAIMS_SESSION))
                routing { staticResources("/", "web") }
            }
            server.start(wait = false)
            try {
                val bound = server.engine.resolvedConnectors().first().port
                val relay = BoardRelay.hosted(
                    scope = scope,
                    source = source,
                    dispatcher = Dispatchers.Default,
                    replica = ReplicaId("host-${Uuid.random()}"),
                    clock = clock,
                )
                val voter = ClaimsVoter.start(
                    scope,
                    claimsHost,
                    config.dataDir.resolve("claims").toFile(),
                    RaftConfig(),
                    boardMirror(relay),
                    clock,
                )
                val roster = LivePresence(relay.state, clock, scope) { failure -> log.warn(failure) { "Presence tracking failed." } }
                val departures = Departures(claimsLinkLoss(voter.membership, clock), roster::live, voter::depart, clock = clock).start(scope)
                return BeeHost(bound, relay, voter, departures, server)
            } catch (failure: Throwable) {
                server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
                throw failure
            }
        }
    }
}

/** The voter's [ClaimMirror] onto [relay]'s board: the claims mirror and the departed list (A2). */
fun boardMirror(relay: BoardRelay): ClaimMirror = ClaimMirror { table -> relay.mutate { it.mirrorClaims(relay.replica, table) } }
