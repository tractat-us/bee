package us.tractat.bee.host

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import us.tractat.bee.a2ui.NOTIONS_CATALOG_ID
import us.tractat.bee.a2ui.toSurfaceView
import us.tractat.bee.core.BoardClient
import us.tractat.bee.core.BoardPin
import us.tractat.bee.core.pinnedTo
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.SpokeLoom
import us.tractat.bee.core.SurfaceMeta
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.websocket.WebSocketAdvertisement
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * The relay over real sockets: a [BeeHost] on an ephemeral loopback port and two [BoardClient]s
 * dialling `/ws/board` through [SpokeLoom]. Runs only with `-Pbee.realsocket.tests=true`.
 */
class RelaySmokeTest {
    @Test
    fun twoClientsConvergeOverRealSockets() {
        assumeTrue(System.getProperty("bee.realsocket.tests") == "true")
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val host = BeeHost.start(scope, HostConfig(port = 0, dataDir = Files.createTempDirectory("bee-smoke-")))
            val http = HttpClient(CIO) { install(WebSockets) }
            try {
                val clock = { Clock.System.now() }
                val rendezvous = Rendezvous.Existing(
                    WebSocketAdvertisement("ws://127.0.0.1:${host.port}${BoardRelay.PATH}", PeerId("bee-host"), "board"),
                )
                fun client(author: ReplicaId) = BoardClient(
                    author,
                    SpokeLoom(http, Dispatchers.Default, scope, author.value, Random.Default, clock).pinnedTo(BoardPin.to(host.boardId)),
                    rendezvous,
                    scope,
                    clock,
                )
                val alex = client(ReplicaId("web-alex"))
                val sam = client(ReplicaId("web-sam"))
                alex.connect()
                sam.connect()
                withTimeout(10.seconds) {
                    alex.online.first { it }
                    sam.online.first { it }
                }
                val meta = SurfaceMeta("Lunch", NOTIONS_CATALOG_ID, PeerIdentity.human("iain"), Clock.System.now())
                val root = buildJsonObject {
                    put("id", "root")
                    put("component", "Text")
                    put("text", "Lunch?")
                }
                alex.mutate { it.openSurface(ReplicaId("web-alex"), "lunch", meta, listOf(root), JsonObject(emptyMap())) }
                withTimeout(10.seconds) { sam.state.first { it.surfaceDoc("lunch") != null } }
                sam.mutate { it.updateData(ReplicaId("web-sam"), "lunch", "/note", JsonPrimitive("from sam")) }
                fun BoardState.note() = surfaceDoc("lunch")?.toSurfaceView("lunch")?.data?.get("note")
                withTimeout(10.seconds) {
                    alex.state.first { it.note() == JsonPrimitive("from sam") }
                    host.state.first { it.note() == JsonPrimitive("from sam") }
                }
                val page = http.get("http://127.0.0.1:${host.port}/")
                assertAll(
                    { assertEquals(alex.state.value, sam.state.value) },
                    { assertEquals(alex.state.value, host.state.value) },
                    { assertEquals(HttpStatusCode.OK, page.status, "the host serves the page at /") },
                )
                alex.close()
                sam.close()
            } finally {
                http.close()
                host.close()
                scope.cancel()
            }
        }
    }
}
