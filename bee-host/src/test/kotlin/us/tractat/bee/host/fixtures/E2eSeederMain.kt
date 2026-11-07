package us.tractat.bee.host.fixtures

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import us.tractat.bee.a2ui.NOTIONS_CATALOG_ID
import us.tractat.bee.a2ui.toSurfaceView
import us.tractat.bee.core.BoardClient
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.SpokeLoom
import us.tractat.bee.core.SurfaceMeta
import us.tractat.bee.host.BoardRelay
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.websocket.WebSocketAdvertisement
import java.net.URI
import kotlin.random.Random
import kotlin.system.exitProcess
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The Playwright fixture surfaces (e2e/two-browsers.spec.mjs), one per test so no test sees
 * another's edits. Each holds a `TextField` bound to `/note`, a `Text` echoing `/note` (variant
 * `h5`), a caption bound to `/note`'s conflict set, a Press button, and one appended `/options`
 * entry for a basis to name.
 */
val E2E_FIXTURES: List<String> = listOf("typing", "conflict", "records", "hold")

/**
 * Writes the Playwright fixture surfaces onto a running host through a JVM [BoardClient], as any
 * peer would: the host has no write route. Run as `:bee-host:e2eSeed -Pbee.e2e.host=<url>` from
 * Playwright's globalSetup, which passes `--host <url>`. It seeds every fixture by default, and
 * `--fixture <name>` narrows it to one.
 *
 * It returns once a second, fresh client reads every seeded surface back from the host, so a test
 * never starts before its fixture is on the board.
 */
fun main(args: Array<String>) {
    val flags = args.toList().chunked(2).associate { it.first() to it.getOrNull(1) }
    val host = requireNotNull(flags["--host"]) { "E2eSeederMain needs --host <url>" }
    val fixtures = flags["--fixture"]?.let { name ->
        require(name in E2E_FIXTURES) { "No fixture $name; there are ${E2E_FIXTURES.joinToString()}" }
        listOf(name)
    } ?: E2E_FIXTURES
    runBlocking { seed(URI(host), fixtures) }
    // Ktor's CIO engine leaves non-daemon threads behind.
    exitProcess(0)
}

@OptIn(ExperimentalUuidApi::class)
private suspend fun seed(host: URI, fixtures: List<String>) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val http = HttpClient(CIO) { install(WebSockets) }
    val clock = { Clock.System.now() }
    val rendezvous = Rendezvous.Existing(
        WebSocketAdvertisement("ws://${host.host}:${host.port}${BoardRelay.PATH}", PeerId("bee-host"), "board"),
    )
    fun client(author: ReplicaId) =
        BoardClient(author, SpokeLoom(http, Dispatchers.Default, scope, author.value, Random.Default, clock), rendezvous, scope, clock)
    try {
        val author = ReplicaId("e2e-seeder-${Uuid.random()}")
        val writer = client(author).apply { connect() }
        withTimeout(30.seconds) { writer.online.first { it } }
        for (id in fixtures) {
            writer.mutateOrSkip { state ->
                if (state.surfaceDoc(id) != null) return@mutateOrSkip null
                val meta = SurfaceMeta("Fixture $id", NOTIONS_CATALOG_ID, PeerIdentity.human("e2e"), clock())
                state.openSurface(author, id, meta, components(), buildJsonObject { put("note", "") })
            }
            writer.mutate { it.appendEntry(author, id, "/options", JsonPrimitive("lunch")) }
        }
        val reader = client(ReplicaId("e2e-reader-${Uuid.random()}")).apply { connect() }
        withTimeout(30.seconds) {
            reader.state.first { state ->
                fixtures.all { id -> state.surfaceDoc(id)?.toSurfaceView(id)?.data?.get("options") != null }
            }
        }
        println("Seeded ${fixtures.joinToString()} on $host")
        reader.close()
        writer.close()
    } finally {
        http.close()
        scope.cancel()
    }
}

private fun components(): List<JsonObject> = listOf(
    buildJsonObject {
        put("id", "root")
        put("component", "Column")
        put("children", JsonArray(listOf("note-field", "note-echo", "note-conflicts", "press-button").map(::JsonPrimitive)))
    },
    buildJsonObject {
        put("id", "note-field")
        put("component", "TextField")
        put("label", "Note")
        putJsonObject("value") { put("path", "/note") }
    },
    buildJsonObject {
        put("id", "note-echo")
        put("component", "Text")
        put("variant", "h5")
        putJsonObject("text") { put("path", "/note") }
    },
    buildJsonObject {
        put("id", "note-conflicts")
        put("component", "Text")
        put("variant", "caption")
        // The conflict set for /note, published by the driver under its raw pointer, escaped once.
        putJsonObject("text") { put("path", "/_bee/conflicts/~1note") }
    },
    buildJsonObject {
        put("id", "press-label")
        put("component", "Text")
        put("text", "Press")
    },
    buildJsonObject {
        put("id", "press-button")
        put("component", "Button")
        put("child", "press-label")
        putJsonObject("action") { putJsonObject("event") { put("name", "press") } }
    },
)
