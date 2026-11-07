package us.tractat.bee.host

import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import us.tractat.bee.core.BoardState
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.websocket.KtorConnectionSource
import java.nio.file.Files
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The always-on host (spec §5.4): it serves the page at `/` and relays the board at
 * [BoardRelay.PATH]. Invariant: **the host writes to the board only through its own replica**;
 * it has no write route.
 *
 * The Netty server is never parented to a caller's coroutine (kuilt has hung on an
 * `embeddedServer` parented to `runBlocking`), so [close] stops it. Everything else runs on the
 * scope given to [start], which the caller cancels.
 */
class BeeHost private constructor(
    /** The bound port: pass `port = 0` for an ephemeral one. */
    val port: Int,
    private val relay: BoardRelay,
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
        relay.close()
        server.stop(gracePeriodMillis = 100, timeoutMillis = 2_000)
    }

    companion object {
        /** Starts a host on [config]'s address. Background work runs on [scope]. */
        @OptIn(ExperimentalUuidApi::class)
        suspend fun start(scope: CoroutineScope, config: HostConfig): BeeHost {
            // Task 9 keeps the board here. Until then the host only makes sure the directory exists.
            Files.createDirectories(config.dataDir)
            lateinit var source: KtorConnectionSource
            val server = embeddedServer(Netty, port = config.port, host = config.bind) {
                source = KtorConnectionSource(this, BoardRelay.PATH)
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
                    clock = { Clock.System.now() },
                )
                return BeeHost(bound, relay, server)
            } catch (failure: Throwable) {
                server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
                throw failure
            }
        }
    }
}
