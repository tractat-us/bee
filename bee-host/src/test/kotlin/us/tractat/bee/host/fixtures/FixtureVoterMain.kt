package us.tractat.bee.host.fixtures

import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import us.tractat.bee.host.ClaimMirror
import us.tractat.bee.host.ClaimsVoter
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.websocket.KtorRoomHost
import java.io.File
import java.nio.file.Files
import kotlin.time.Clock

/**
 * The fixture Raft voter the browser claim test dials (Task 6). bee-web's build starts it
 * before `:bee-web:wasmJsBrowserTest` with `-Pbee.realsocket.tests=true`, reads its port
 * from a stdout line `BEE_FIXTURE_VOTER_PORT=<n>`, and stops it after the task.
 */
fun main(): Unit = runBlocking(Dispatchers.Default) {
    val voter = startFixtureVoter(this, Files.createTempDirectory("bee-fixture-voter").toFile())
    System.out.println("$PORT_LINE_PREFIX${voter.port}")
    System.out.flush()
    awaitCancellation()
}

const val PORT_LINE_PREFIX = "BEE_FIXTURE_VOTER_PORT="

/** A [ClaimsVoter] on a real socket: loopback, an ephemeral port, `/ws/claims`. */
class FixtureVoter(val voter: ClaimsVoter, val port: Int, private val server: EmbeddedServer<NettyApplicationEngine, *>) {
    val url: String get() = "ws://127.0.0.1:$port$CLAIMS_PATH"

    suspend fun stop() {
        voter.close()
        server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
    }

    companion object {
        const val CLAIMS_PATH = "/ws/claims"
        const val SESSION = "claims"
        val SERVER = PeerId("bee-host")
    }
}

suspend fun startFixtureVoter(
    scope: CoroutineScope,
    storeDir: File,
    mirror: ClaimMirror = ClaimMirror { },
): FixtureVoter {
    lateinit var host: KtorRoomHost
    val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
        host = KtorRoomHost(this, FixtureVoter.CLAIMS_PATH, FixtureVoter.SERVER, Pattern(FixtureVoter.SESSION))
    }
    server.start(wait = false)
    val port = server.engine.resolvedConnectors().first().port
    val voter = ClaimsVoter.start(scope, host, storeDir, RaftConfig(), mirror) { Clock.System.now() }
    return FixtureVoter(voter, port, server)
}
