package us.tractat.bee.web

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import us.tractat.bee.a2ui.NOTIONS_CATALOG_ID
import us.tractat.bee.core.ActionRecord
import us.tractat.bee.core.Basis
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.ClaimCommand
import us.tractat.bee.core.ClaimTable
import us.tractat.bee.core.ClaimTarget
import us.tractat.bee.core.DepartReason
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.PresenceEntry
import us.tractat.bee.core.ProposalId
import us.tractat.bee.core.SurfaceMeta
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class BoardViewTest {
    @Test
    fun aCardNamesItsTitleItsOpenerAndWhatTheOpenerIsWorkingOn() {
        val view = BoardView.of(board(), live = listOf(entry(alice, "naming the CLI"), entry(iain, null)))
        val lunch = view.cards.single { it.surfaceId == LUNCH }
        assertAll(
            { assertEquals(listOf(LUNCH, REVIEW), view.cards.map { it.surfaceId }) },
            { assertEquals("Lunch", lunch.title) },
            { assertEquals(alice, lunch.openedBy) },
            { assertEquals("naming the CLI", lunch.openerStatus) },
            { assertEquals(null, lunch.openerDeparted) },
        )
    }

    @Test
    fun theStripShowsPeopleThenAgentsEachOnceWithWhatTheyAreDoing() {
        val view = BoardView.of(
            board(),
            live = listOf(entry(alice, "naming the CLI"), entry(carol, null, waiting = true), entry(iain, null), entry(iain, null, at = 2)),
        )
        assertEquals(
            listOf(
                PeerView(iain, PeerState.Here, null, null),
                PeerView(alice, PeerState.Acting, "naming the CLI", null),
                PeerView(carol, PeerState.Waiting, null, null),
            ),
            view.peers,
        )
    }

    @Test
    fun aDepartedAgentFadesAndItsClaimShowsOrphanedOnItsCard() {
        val lastSeen = board().piece { it.postPresence(B, entry(bob, "reviewing"), 1) }
        val state = lastSeen.piece { it.mirrorClaims(HOST, claims(lastSeen, departBob = true)) }
        val view = BoardView.of(state, live = listOf(entry(alice, "naming the CLI")))
        val review = view.cards.single { it.surfaceId == REVIEW }
        assertAll(
            { assertEquals(PeerView(bob, PeerState.Departed, "reviewing", DepartReason.Crashed), view.peers.last()) },
            { assertEquals(DepartReason.Crashed, review.openerDeparted) },
            { assertEquals(null, review.openerStatus) },
            { assertEquals(listOf(ClaimBadge(recordTarget(lastSeen), bob.toString(), orphaned = true)), review.claims) },
            { assertEquals(listOf(ClaimBadge("card:$LUNCH/tacos", alice.toString(), orphaned = false)), view.cards.first().claims) },
        )
    }

    @Test
    fun aHeldClaimShowsOnTheCardItNames() {
        val state = board().let { it.piece { s -> s.mirrorClaims(HOST, claims(it, departBob = false)) } }
        val view = BoardView.of(state, live = emptyList())
        assertAll(
            { assertEquals(listOf(ClaimBadge("card:$LUNCH/tacos", alice.toString(), orphaned = false)), view.cards[0].claims) },
            { assertEquals(listOf(ClaimBadge(recordTarget(state), bob.toString(), orphaned = false)), view.cards[1].claims) },
            { assertEquals(emptyList(), view.peers) },
        )
    }

    private companion object {
        val A = ReplicaId("web-a")
        val B = ReplicaId("mcp-b")
        val HOST = ReplicaId("host")
        const val LUNCH = "lunch"
        const val REVIEW = "review"
        val alice = PeerIdentity.agent("alice", Random(1))
        val bob = PeerIdentity.agent("bob", Random(2))
        val carol = PeerIdentity.agent("carol", Random(3))
        val iain = PeerIdentity.human("iain")
        val T0: Instant = Instant.parse("2026-11-08T12:00:00Z")
        val P = ProposalId("node-a", 0, 1)
        val root: JsonObject = buildJsonObject {
            put("id", "root")
            put("component", "Text")
            put("text", "Hi")
        }

        /** Lunch opened by alice, then review by bob, with one record of iain's on review. */
        fun board(): BoardState {
            val opened = BoardState.EMPTY
                .piece { it.openSurface(A, LUNCH, SurfaceMeta("Lunch", NOTIONS_CATALOG_ID, alice, T0), listOf(root), JsonObject(emptyMap())) }
                .piece { it.openSurface(B, REVIEW, SurfaceMeta("Review", NOTIONS_CATALOG_ID, bob, Instant.parse("2026-11-08T12:01:00Z")), listOf(root), JsonObject(emptyMap())) }
            val record = ActionRecord(REVIEW, "root", iain, "press", JsonObject(emptyMap()), Basis.capture(opened, REVIEW), T0)
            return opened.piece { it.appendRecord(A, record) }
        }

        fun recordTarget(state: BoardState): String = state.records(REVIEW).single().first.claimTarget.value

        /** alice holds a card on lunch, bob the record on review; [departBob] retires bob. */
        fun claims(state: BoardState, departBob: Boolean): ClaimTable {
            val commands = listOfNotNull(
                ClaimCommand.Claim(ClaimTarget("card:$LUNCH/tacos"), alice, P),
                ClaimCommand.Claim(ClaimTarget(recordTarget(state)), bob, P),
                if (departBob) ClaimCommand.Depart(bob, DepartReason.Crashed) else null,
            )
            return commands.foldIndexed(ClaimTable.EMPTY) { i, table, command ->
                table.fold(Committed.Entry(LogEntry(index = i + 1L, term = 1L, command = ClaimCommand.encode(command))))
            }
        }

        fun entry(who: PeerIdentity, status: String?, waiting: Boolean = false, at: Long = 1) =
            PresenceEntry(who, claimsNode = null, status = status, lastActedAt = Instant.fromEpochSeconds(at), waiting = waiting)
    }
}
