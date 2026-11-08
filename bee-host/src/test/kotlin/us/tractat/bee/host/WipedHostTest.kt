package us.tractat.bee.host

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import us.tractat.bee.a2ui.NOTIONS_CATALOG_ID
import us.tractat.bee.core.BoardClient
import us.tractat.bee.core.BoardId
import us.tractat.bee.core.ClaimCommand
import us.tractat.bee.core.ClaimTable
import us.tractat.bee.core.ClaimTarget
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.ProposalId
import us.tractat.bee.core.SpokeLoom
import us.tractat.bee.core.SurfaceMeta
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.websocket.WebSocketAdvertisement
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A tab left open across a host wipe (bee#46), with real hosts on one port: the tab joins the first
 * host's board, the host stops, and a host with a wiped data dir starts in its place. The tab
 * redials on its own, naming the board it holds.
 *
 * The tab holds what would hurt the new board: a surface, and a claim view mirrored at log index
 * [OLD_INDEX], which outranks the new log's views until that log passes it. Either one reaching
 * the new host is the bug. A refusal is a handshake the new host answered 403, which only a gate
 * gives: before the new host is up, the tab's dials fail to connect at all.
 */
class WipedHostTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dirs = List(2) { Files.createTempDirectory("w45-wiped-host-") }
    private val http = HttpClient(CIO) { install(WebSockets) }
    private val clock = { Clock.System.now() }

    @AfterTest
    fun stop() {
        http.close()
        scope.cancel()
        dirs.forEach { it.toFile().deleteRecursively() }
    }

    @Test
    fun aTabOpenAcrossAWipeNeverPushesItsOldBoardIntoTheNewHost() = runBlocking {
        val first = BeeHost.start(scope, HostConfig(port = 0, dataDir = dirs[0]))
        val port = first.port
        val author = ReplicaId("web-tab")
        val tab = BoardClient(
            author,
            SpokeLoom(http, Dispatchers.Default, scope, author.value, Random.Default, clock),
            Rendezvous.Existing(WebSocketAdvertisement("ws://127.0.0.1:$port${BoardRelay.PATH}?${BoardId.PARAM}=${first.boardId}", PeerId("bee-host"), "board")),
            scope,
            clock,
        )
        tab.connect()
        tab.mutate { it.openSurface(author, OLD_SURFACE, meta(), listOf(root), JsonObject(emptyMap())) }
        first.mutate { it.mirrorClaims(first.author, heldAt(OLD_INDEX)) }
        withTimeout(15.seconds) { tab.state.first { it.surfaceDoc(OLD_SURFACE) != null && it.claimView(target) != null } }
        first.close()

        val second = BeeHost.start(scope, HostConfig(port = port, dataDir = dirs[1]))
        val outcome = withTimeout(30.seconds) {
            merge(
                second.state.filter { it.surfaceDoc(OLD_SURFACE) != null || it.claimView(target) != null }.map { "merged" },
                tab.lastFailure.filterNotNull().filter { "403" in it.message.orEmpty() }.map { "refused" },
            ).first()
        }
        val board = second.state.value
        tab.close()
        second.close()
        assertAll(
            { assertEquals("refused", outcome) },
            { assertNull(board.surfaceDoc(OLD_SURFACE), "the old surface") },
            { assertNull(board.claimView(target), "the old claim view") },
        )
    }

    /**
     * An operator moves `claims/` aside (a corrupt Raft log) and keeps `board/`. The board's own
     * snapshot holds the old log's claim views, which a new log, restarting at index 1, can't
     * outrank: so the host starts a new board instead.
     */
    @Test
    fun aHostWhoseClaimsLogWasMovedAsideStartsANewBoard() = runBlocking {
        val first = BeeHost.start(scope, HostConfig(port = 0, dataDir = dirs[0]))
        first.mutate { it.openSurface(first.author, OLD_SURFACE, meta(), listOf(root), JsonObject(emptyMap())) }
        first.mutate { it.mirrorClaims(first.author, heldAt(OLD_INDEX)) }
        val old = first.boardId
        first.close()
        val again = BeeHost.start(scope, HostConfig(port = 0, dataDir = dirs[0]))
        val kept = again.boardId
        val keptSurface = again.state.value.surfaceDoc(OLD_SURFACE)
        again.close()

        Files.move(dirs[0].resolve("claims"), dirs[0].resolve("claims.aside"))
        val moved = BeeHost.start(scope, HostConfig(port = 0, dataDir = dirs[0]))
        val board = moved.state.value
        moved.close()
        assertAll(
            { assertEquals(old, kept, "rig: a plain restart keeps the board") },
            { assertNotNull(keptSurface, "rig: and its snapshot") },
            { assertNotEquals(old, moved.boardId, "a new board") },
            { assertNull(board.surfaceDoc(OLD_SURFACE), "the old board's surface") },
            { assertNull(board.claimView(target), "the old log's claim view") },
        )
    }

    private companion object {
        const val OLD_SURFACE = "from-the-old-board"
        const val OLD_INDEX = 50L
        val target = ClaimTarget("record:old:1")

        val root: JsonObject = buildJsonObject {
            put("id", "root")
            put("component", "Text")
            put("text", "Before the wipe")
        }

        fun meta() = SurfaceMeta("Old", NOTIONS_CATALOG_ID, PeerIdentity.human("iain"), Instant.parse("2026-11-07T12:00:00Z"))

        /** A claims table holding [target] as of log index [index]. */
        fun heldAt(index: Long): ClaimTable {
            val claim = ClaimCommand.Claim(target, PeerIdentity.agent("ada", Random(1)), ProposalId("old", incarnation = 1, serial = 1))
            return ClaimTable.EMPTY.fold(Committed.Entry(LogEntry(index = index, term = 1, command = ClaimCommand.encode(claim))))
        }
    }
}
