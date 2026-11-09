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
import us.tractat.bee.core.ClaimToken
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
        val view = BoardView.of(board(), attached(entry(alice, "naming the CLI"), entry(iain, null)))
        val lunch = view.cards.single { it.surfaceId == LUNCH }
        val review = view.cards.single { it.surfaceId == REVIEW }
        assertAll(
            { assertEquals("Lunch", lunch.title) },
            { assertEquals(alice, lunch.openedBy) },
            { assertEquals(OpenerState.Working("naming the CLI"), lunch.opener) },
            { assertEquals(OpenerState.Away, review.opener) },
        )
    }

    /** An agent in `bee_wait_action` posts `waiting`: its card is heard now. */
    @Test
    fun anAgentOpenerIsListeningWhileItWaitsAndWorkingOtherwise() {
        fun lunch(waiting: Boolean) =
            BoardView.of(board(), attached(entry(alice, "naming the CLI", waiting))).cards.single { it.surfaceId == LUNCH }
        assertAll(
            { assertEquals(OpenerState.Listening("naming the CLI"), lunch(waiting = true).opener) },
            { assertEquals(OpenerState.Working("naming the CLI"), lunch(waiting = false).opener) },
            { assertEquals(false, lunch(waiting = true).unheard) },
            { assertEquals(false, lunch(waiting = false).unheard) },
        )
    }

    /** `waiting` means a session in `bee_wait_action`; a person's or the host's card has no such state. */
    @Test
    fun aPersonsOrTheHostsCardShowsItsOpenerAttachedNeverListening() {
        val host = PeerIdentity.host("bee-host")
        val state = board()
            .piece { it.openSurface(A, NOTES, SurfaceMeta("Notes", NOTIONS_CATALOG_ID, iain, T0), listOf(root), JsonObject(emptyMap())) }
            .piece { it.openSurface(HOST, ORPHAN, SurfaceMeta("Orphaned", NOTIONS_CATALOG_ID, host, T0), listOf(root), JsonObject(emptyMap())) }
        val view = BoardView.of(state, attached(entry(iain, "taking notes", waiting = true), entry(host, null, waiting = true)))
        assertAll(
            { assertEquals(OpenerState.Attached("taking notes"), view.cards.single { it.surfaceId == NOTES }.opener) },
            { assertEquals(OpenerState.Attached(null), view.cards.single { it.surfaceId == ORPHAN }.opener) },
            { assertEquals(OpenerState.Away, BoardView.of(state, attached = emptyMap()).cards.single { it.surfaceId == ORPHAN }.opener) },
        )
    }

    /**
     * A session that left, crashed or went quiet takes no more answers, so its card is unheard. One
     * that `/clear`ed still handles the surfaces it opened before (spec §5.2), so its card is not.
     */
    @Test
    fun aCardIsUnheardOnlyWhenItsOpenerLeftCrashedOrWentQuiet() {
        fun unheard(opener: OpenerState) = CardView(LUNCH, "Lunch", alice, opener, emptyList()).unheard
        assertAll(
            { assertEquals(true, unheard(OpenerState.Departed(DepartReason.Left))) },
            { assertEquals(true, unheard(OpenerState.Departed(DepartReason.Crashed))) },
            { assertEquals(true, unheard(OpenerState.Departed(DepartReason.Quiet))) },
            { assertEquals(false, unheard(OpenerState.Departed(DepartReason.Cleared))) },
            { assertEquals(false, unheard(OpenerState.Listening(null))) },
            { assertEquals(false, unheard(OpenerState.Working(null))) },
            { assertEquals(false, unheard(OpenerState.Attached(null))) },
            { assertEquals(false, unheard(OpenerState.Away)) },
        )
    }

    /** Through the board: the departure the host mirrors is what makes the card unheard. */
    @Test
    fun aCardWhoseOpenerDepartedOnTheBoardIsUnheard() {
        val state = board().let { it.piece { s -> s.mirrorClaims(HOST, claims(it, departBob = true)) } }
        val view = BoardView.of(state, attached(entry(alice, null)))
        assertAll(
            { assertEquals(true, view.cards.single { it.surfaceId == REVIEW }.unheard) },
            { assertEquals(false, view.cards.single { it.surfaceId == LUNCH }.unheard) },
        )
    }

    /** A card an agent draws mid-meeting shows on top, where the room sees it appear. */
    @Test
    fun cardsShowNewestOpenedFirstAndAnUpdateLeavesACardWhereItIs() {
        val bye = buildJsonObject {
            put("id", "root")
            put("component", "Text")
            put("text", "Bye")
        }
        val updated = board().piece { it.updateComponents(A, LUNCH, listOf(bye)) }
        assertAll(
            { assertEquals(listOf(REVIEW, LUNCH), BoardView.of(board(), attached = emptyMap()).cards.map { it.surfaceId }) },
            { assertEquals(listOf(REVIEW, LUNCH), BoardView.of(updated, attached = emptyMap()).cards.map { it.surfaceId }) },
        )
    }

    @Test
    fun theStripShowsPeopleThenAgentsWithWhatTheyAreDoing() {
        val view = BoardView.of(board(), attached(entry(alice, "naming the CLI"), entry(carol, null, waiting = true), entry(iain, null)))
        assertEquals(
            listOf(
                PeerView(iain, PeerState.Here, null),
                PeerView(alice, PeerState.Acting, "naming the CLI"),
                PeerView(carol, PeerState.Waiting, null),
            ),
            view.peers,
        )
    }

    @Test
    fun aDepartedAgentFadesAndItsClaimShowsOrphanedOnItsCard() {
        val lastSeen = board().piece { it.postPresence(B, entry(bob, "reviewing"), 1) }
        val state = lastSeen.piece { it.mirrorClaims(HOST, claims(lastSeen, departBob = true)) }
        val view = BoardView.of(state, attached(entry(alice, "naming the CLI")))
        val review = view.cards.single { it.surfaceId == REVIEW }
        assertAll(
            { assertEquals(PeerView(bob, PeerState.Departed(DepartReason.Crashed), "reviewing"), view.peers.last()) },
            { assertEquals(OpenerState.Departed(DepartReason.Crashed), review.opener) },
            { assertEquals(listOf(ClaimBadge(recordTarget(lastSeen), bob, orphaned = true)), review.claims) },
            { assertEquals(listOf(ClaimBadge("card:$LUNCH/tacos", alice, orphaned = false)), view.cards.single { it.surfaceId == LUNCH }.claims) },
        )
    }

    /**
     * alice is departed `Quiet` while her entry is still live; her finished claim is done; and four
     * agents departed after their last post, more than the strip keeps.
     */
    @Test
    fun aLiveDepartedAgentShowsDepartedItsDoneClaimNoBadgeAndOnlyTheNewestDeparturesStay() {
        val gone = listOf(bob, dave, erin, frank)
        val lastSeen = gone.fold(board()) { state, who -> state.piece { it.postPresence(ReplicaId("mcp-${who.name}"), entry(who, "was ${who.name}"), 1) } }
        val tacos = ClaimTarget("card:$LUNCH/tacos")
        val commands = listOf(
            ClaimCommand.Claim(tacos, alice, P),
            ClaimCommand.Complete(tacos, alice, ClaimToken(term = 1, index = 1)),
            ClaimCommand.Depart(alice, DepartReason.Quiet),
        ) + gone.map { ClaimCommand.Depart(it, DepartReason.Crashed) }
        // Mirrored after each commit, as the host does, so each departure has its own log index.
        val state = commands.indices.fold(lastSeen) { board, i -> board.piece { it.mirrorClaims(HOST, table(commands.take(i + 1))) } }
        val view = BoardView.of(state, attached(entry(alice, "naming the CLI")))
        val lunch = view.cards.single { it.surfaceId == LUNCH }
        assertAll(
            { assertEquals(PeerView(alice, PeerState.Departed(DepartReason.Quiet), "naming the CLI"), view.peers.first()) },
            { assertEquals(OpenerState.Departed(DepartReason.Quiet), lunch.opener) },
            { assertEquals(emptyList(), lunch.claims) },
            { assertEquals(listOf(frank, erin, dave), view.peers.drop(1).map { it.identity }) },
        )
    }

    @Test
    fun aHeldClaimShowsOnTheCardItNames() {
        val state = board().let { it.piece { s -> s.mirrorClaims(HOST, claims(it, departBob = false)) } }
        val view = BoardView.of(state, attached = emptyMap())
        assertAll(
            { assertEquals(listOf(ClaimBadge("card:$LUNCH/tacos", alice, orphaned = false)), view.cards.single { it.surfaceId == LUNCH }.claims) },
            { assertEquals(listOf(ClaimBadge(recordTarget(state), bob, orphaned = false)), view.cards.single { it.surfaceId == REVIEW }.claims) },
            { assertEquals(emptyList(), view.peers) },
        )
    }

    private companion object {
        val A = ReplicaId("web-a")
        val B = ReplicaId("mcp-b")
        val HOST = ReplicaId("host")
        const val LUNCH = "lunch"
        const val REVIEW = "review"
        const val NOTES = "notes"
        const val ORPHAN = "orphan-x"
        val alice = PeerIdentity.agent("alice", Random(1))
        val bob = PeerIdentity.agent("bob", Random(2))
        val carol = PeerIdentity.agent("carol", Random(3))
        val dave = PeerIdentity.agent("dave", Random(4))
        val erin = PeerIdentity.agent("erin", Random(5))
        val frank = PeerIdentity.agent("frank", Random(6))
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
        fun claims(state: BoardState, departBob: Boolean): ClaimTable = table(
            listOfNotNull(
                ClaimCommand.Claim(ClaimTarget("card:$LUNCH/tacos"), alice, P),
                ClaimCommand.Claim(ClaimTarget(recordTarget(state)), bob, P),
                if (departBob) ClaimCommand.Depart(bob, DepartReason.Crashed) else null,
            ),
        )

        /** [commands] committed in order, from log index 1 in term 1. */
        fun table(commands: List<ClaimCommand>): ClaimTable = commands.foldIndexed(ClaimTable.EMPTY) { i, table, command ->
            table.fold(Committed.Entry(LogEntry(index = i + 1L, term = 1L, command = ClaimCommand.encode(command))))
        }

        /** The attached peers, as [us.tractat.bee.core.LivePresence.peers] gives them. */
        fun attached(vararg entries: PresenceEntry): Map<PeerIdentity, PresenceEntry> = entries.associateBy { it.identity }

        fun entry(who: PeerIdentity, status: String?, waiting: Boolean = false) =
            PresenceEntry(who, claimsNode = null, status = status, lastActedAt = Instant.fromEpochSeconds(1), waiting = waiting)
    }
}
