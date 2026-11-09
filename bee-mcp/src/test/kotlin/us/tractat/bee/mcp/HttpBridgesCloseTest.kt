package us.tractat.bee.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.test.assertAll
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * [HttpBridges.close] ends every session even when a session's server fails to close. The SDK's
 * (0.16.0) transport close throws `NoSuchElementException` when a client's GET stream ends during
 * it (see `Session.end`), which a test can't time from outside the SDK; so here every server's close
 * throws that, on purpose. A close that let it out left the rest of the host's close undone: other
 * sessions, the voter, the board's last save (bee#185's merge-queue failures in `McpHttpTest`).
 */
class HttpBridgesCloseTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir = Files.createTempDirectory("bee-bridges-close-")
    private val http = HttpClient(CIO)

    @AfterTest
    fun stop() {
        http.close()
        scope.cancel()
        dir.toFile().deleteRecursively()
    }

    @Test
    fun aServerThatFailsToCloseStillDepartsAndTheRestStillClose() = runBlocking {
        val kept = KeptIdentities(KeptSessionFile(dir.resolve(KeptSessionFile.NAME)), { emptySet() }, { Clock.System.now() })
        var closes = 0
        val bridges = HttpBridges(
            scope,
            { Clock.System.now() },
            Random(185),
            kept,
            { PresentedKey.None },
            HttpBridges.IDLE_AFTER,
            HttpBridges.IDLE_AFTER,
            HttpBridges.MAX_SESSIONS,
        ) { closes++; throw NoSuchElementException("forced: the SDK's transport close lost a race") }
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") { bridges.mount(this) }.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val opened = listOf("ada", "bo").map { initialize("http://127.0.0.1:$port${HttpBridges.PATH}?name=$it") }
            withTimeout(TIMEOUT) { while (bridges.count != 2) delay(20) }
            val failure = runCatching { withTimeout(TIMEOUT) { bridges.close() } }.exceptionOrNull()
            assertAll(
                { assertEquals(listOf(HttpStatusCode.OK, HttpStatusCode.OK), opened, "rig: both sessions initialized") },
                { assertEquals(null, failure, "close ends every session, whatever one's server throws") },
                { assertEquals(2, closes, "rig: each session's server close ran, and threw") },
                { assertEquals(0, bridges.count, "each departed all the same") },
            )
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        }
    }

    private suspend fun initialize(url: String): HttpStatusCode = http.post(url) {
        contentType(ContentType.Application.Json)
        header(HttpHeaders.Accept, "application/json, text/event-stream")
        setBody(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
                """"capabilities":{},"clientInfo":{"name":"raw","version":"0"}}}""",
        )
    }.status

    private companion object {
        val TIMEOUT = 20.seconds
    }
}
