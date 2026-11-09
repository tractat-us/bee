package us.tractat.bee.host

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import us.tractat.bee.a2ui.NOTIONS_CATALOG_ID
import us.tractat.bee.core.ActionRecord
import us.tractat.bee.core.Basis
import us.tractat.bee.core.BoardId
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.PairingWire
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.PresenceEntry
import us.tractat.bee.core.SurfaceMeta
import us.tractat.bee.host.Handshakes.Companion.FORBIDDEN
import us.tractat.bee.host.Handshakes.Companion.OK
import us.tractat.bee.host.Handshakes.Companion.SWITCHING
import us.tractat.bee.telemetry.Attr
import us.tractat.bee.telemetry.BeeTelemetry
import us.tractat.bee.telemetry.ProcessTelemetry
import us.tractat.bee.telemetry.Role
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.VersionVector
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.WarpTelemetry
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.FileChannelDurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.assertAll
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The host records its board's changes and the calls it refuses, as itself, and never their keys. */
class HostTelemetryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val root: Path = Files.createTempDirectory("bee-host-telemetry-")
    private val on: (String) -> String? = { null }
    private val resource = mapOf(Attr.SERVICE to "bee-host", Attr.ROLE to Role.HOST)

    @AfterTest
    fun stop() {
        scope.cancel()
        root.toFile().deleteRecursively()
    }

    @Test
    fun theHostRecordsASurfaceOpenedAndClosedOnItsBoard() = runBlocking {
        val seen = Seen()
        val host = BeeHost.start(scope, HostConfig(port = 0, dataDir = root.resolve("data"))) { ProcessTelemetry.of(seen) }
        try {
            host.mutate { it.openSurface(host.author, "lunch", meta(), listOf(root()), JsonObject(emptyMap())) }
            seen.await("surface.open")
            host.mutate { it.closeSurface(host.author, "lunch", Clock.System.now()) }
            val closed = seen.await("surface.close")
            assertAll(
                { assertEquals("lunch", closed[Attr.SURFACE]) },
                { assertEquals(NOTIONS_CATALOG_ID, closed["bee.catalog"]) },
                { assertTrue(seen.names().first() == "board.restored", "the boot board comes first: ${seen.names()}") },
            )
        } finally {
            host.close()
        }
    }

    /**
     * A device off this machine dials twice: once with a key the host never minted, refused, and
     * once with the join link's key, admitted. Both are kept as this host's, stamped with its board
     * and this boot's replica though nothing set them by hand, with whether a key came and neither key.
     */
    @Test
    fun aRefusedAndAnAdmittedSocketAreKeptWithoutTheirKeys() {
        val dir = root.resolve("data")
        val forged = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(Random(3).nextBytes(32))
        val host = runBlocking {
            BeeHost.start(scope, HostConfig(port = 0, dataDir = dir), peers = { ANOTHER_MACHINE }) {
                ProcessTelemetry.open(listOf(it.resolve("telemetry")), resource, env = on, captureLogs = false)
            }
        }
        val joinKey = host.pairing.joinKey.value
        val calls = Handshakes(host.port)
        val refused = calls.upgrade("${BoardRelay.PATH}?${BoardId.PARAM}=${host.boardId}&${PairingWire.KEY_PARAM}=$forged")
        val admitted = calls.upgrade("${BoardRelay.PATH}?${BoardId.PARAM}=${host.boardId}&${PairingWire.KEY_PARAM}=$joinKey")
        host.close()

        val events = stored(dir.resolve("telemetry"))
        val refusal = events.single { it.attributes[Attr.EVENT] == "gate.refused" }.attributes
        val dials = events.filter { it.attributes[Attr.EVENT] == "socket.dial" }.map { it.attributes }
        val bytes = Files.walk(dir.resolve("telemetry")).use { files -> files.filter { it.isRegularFile() }.map { it.readBytes().decodeToString() }.toList() }
        assertAll(
            { assertEquals(FORBIDDEN, refused) },
            { assertEquals(SWITCHING, admitted) },
            { assertEquals(BoardRelay.PATH, refusal[Attr.PATH]) },
            { assertEquals("false", refusal["bee.loopback"]) },
            { assertEquals("true", refusal["bee.key.presented"]) },
            { assertEquals("true", refusal["bee.board.named"]) },
            { assertEquals(Role.HOST, refusal[Attr.ROLE], "kept as the host's") },
            { assertEquals(host.boardId.value, refusal[Attr.BOARD], "stamped with the board") },
            { assertEquals(host.author.value, refusal[BeeHost.REPLICA_ATTRIBUTE], "stamped with this boot's replica") },
            { assertEquals(host.boardId.value, events.first { it.attributes[Attr.EVENT] == "board.restored" }.attributes[Attr.BOARD], "from the first record") },
            { assertEquals(listOf("true", "true"), dials.map { it["bee.key.presented"] }, "both dials") },
            { assertTrue(bytes.none { forged in it }, "the forged key is in a stored file") },
            { assertTrue(bytes.none { joinKey in it }, "the join key is in a stored file") },
        )
    }

    /** Telemetry whose store refuses every read and write: the host boots, serves and writes its board. */
    @Test
    fun aFailingTelemetryStoreDoesNotFailTheHost() = runBlocking {
        val refusing = object : DurableStore {
            override suspend fun read(key: StoreKey): ByteArray? = throw IOException("disk gone")

            override suspend fun write(key: StoreKey, bytes: ByteArray) = throw IOException("disk gone")

            override suspend fun delete(key: StoreKey) = throw IOException("disk gone")
        }
        val host = BeeHost.start(scope, HostConfig(port = 0, dataDir = root.resolve("data"))) {
            ProcessTelemetry.open(listOf(it.resolve("telemetry")), resource, env = on, store = { refusing })
        }
        try {
            host.mutate { it.openSurface(host.author, "lunch", meta(), listOf(root()), JsonObject(emptyMap())) }
            withTimeout(10.seconds) { host.state.first { "lunch" in it.openSurfaces() } }
            assertEquals(OK, Handshakes(host.port).get(BoardId.PATH, "127.0.0.1:${host.port}", "Connection: close"))
        } finally {
            host.close()
        }
    }

    @Test
    fun boardChangesNameIdsAndNeverContent() {
        val author = ReplicaId("host-1")
        val ada = PeerIdentity.human("ada")
        val empty = BoardState.EMPTY
        val opened = empty.piece(empty.openSurface(author, "poll", meta(), listOf(root()), buildJsonObject { put("secret", "board text") }))
        val updated = opened.piece(opened.updateData(author, "poll", "/secret", JsonPrimitive("more board text")))
        val record = ActionRecord("poll", "yes", ada, "vote", buildJsonObject { put("choice", "board text") }, Basis(emptyMap(), emptyMap(), emptySet(), VersionVector.EMPTY), Instant.fromEpochSeconds(1))
        val acted = updated.piece(updated.appendRecord(author, record))
        val attached = acted.piece(acted.postPresence(author, PresenceEntry(ada, null, null, Instant.fromEpochSeconds(1), waiting = false), clock = 1))
        val changes = listOf(empty to opened, opened to updated, updated to acted, acted to attached).flatMap { (before, after) -> boardChanges(before, after) }
        assertAll(
            { assertEquals(listOf("surface.open", "surface.update", "action.record", "presence.attach"), changes.map { it.name }) },
            { assertEquals(mapOf(Attr.SURFACE to "poll", "bee.component" to "yes", "bee.action" to "vote", "bee.actor" to ada.toString()), changes[2].attributes) },
            { assertEquals(ada.toString(), changes[3].attributes[Attr.SUBJECT]) },
            { assertTrue(changes.none { change -> change.attributes.values.any { "board text" in it } }, "content leaked: $changes") },
        )
    }

    private fun meta() = SurfaceMeta("Lunch", NOTIONS_CATALOG_ID, PeerIdentity.human("ada"), Instant.fromEpochSeconds(0))

    private fun root(): JsonObject = buildJsonObject {
        put("id", "root")
        put("component", "Text")
        put("text", "hi")
    }

    private fun stored(dir: Path): List<LogRecord> {
        val warp = WarpTelemetry(ReplicaId("reader"), FileChannelDurableStore(dir.resolve("buffer").toFile()))
        runBlocking { warp.recover() }
        return warp.logs.snapshot().toList()
    }

    /** A [BeeTelemetry] a test can wait on. */
    private class Seen : BeeTelemetry {
        private val events = MutableStateFlow(emptyList<Pair<String, Map<String, String>>>())

        override fun event(name: String, attributes: Map<String, String>) = events.update { it + (name to attributes) }

        override fun span(name: String, start: Instant, end: Instant, attributes: Map<String, String>, error: String?) = Unit

        fun names() = events.value.map { it.first }

        suspend fun await(name: String): Map<String, String> =
            withTimeout(10.seconds) { events.first { seen -> seen.any { it.first == name } } }.last { it.first == name }.second
    }

    private companion object {
        const val ANOTHER_MACHINE = "192.168.1.20"
    }
}
