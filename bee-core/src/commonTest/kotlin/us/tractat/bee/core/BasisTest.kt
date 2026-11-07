package us.tractat.bee.core

import us.tractat.bee.core.BoardFixtures.A
import us.tractat.bee.core.BoardFixtures.B
import us.tractat.bee.core.BoardFixtures.SURFACE
import us.tractat.bee.core.BoardFixtures.entryDots
import us.tractat.bee.core.BoardFixtures.opened
import us.tractat.bee.core.BoardFixtures.str
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.JsonNode
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.VersionVector
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BasisTest {
    private val base = opened().piece { it.appendEntry(A, SURFACE, "/options", str("Pizza")) }

    @Test
    fun basisNamesOnlyDotsTheActorsReplicaDelivered() {
        val onB = base.piece { it.appendEntry(B, SURFACE, "/options", str("Tacos")) }
        val optionDot = entryDots(onB, "/options").single { it.replica == B }
        assertAll(
            { assertFalse(Basis.capture(base, SURFACE).saw("/options", optionDot)) },
            { assertTrue(Basis.capture(base.piece(onB), SURFACE).saw("/options", optionDot)) },
        )
    }

    @Test
    fun dotsOfTwoArraysNeverAlias() {
        val withQuestion = base.piece { it.appendEntry(A, SURFACE, "/question", str("Lunch?")) }
        val optionDot = entryDots(base, "/options").single()
        val questionDot = entryDots(withQuestion, "/question").single()
        val basis = Basis.capture(base, SURFACE)
        assertAll(
            // Each array's Rga mints its own seqs, so the two entries share one dot.
            { assertEquals(Dot(A, 1), optionDot) },
            { assertEquals(optionDot, questionDot) },
            { assertTrue(basis.saw("/options", optionDot)) },
            { assertFalse(basis.saw("/question", questionDot)) },
            { assertTrue(Basis.capture(withQuestion, SURFACE).saw("/question", questionDot)) },
        )
    }

    @Test
    fun flooredDotIsStillSeen() {
        val optionDot = entryDots(base, "/options").single()
        val floored = base.piece(dropOptionsWindow(base))
        val rga = (BoardFixtures.node(floored, "/options") as JsonNode.Array).rga
        assertAll(
            // The precondition: the dot left causalDots() and sits only under the floor.
            { assertFalse(optionDot in rga.causalDots()) },
            { assertTrue(rga.causalFloor().contains(optionDot)) },
            { assertTrue(Basis.capture(floored, SURFACE).saw("/options", optionDot)) },
        )
    }

    @Test
    fun basisCapturedAtActionTimeIgnoresLaterMerges() {
        val voted = base.piece { it.appendRecord(A, BoardFixtures.record(it)) }
        val onB = base.piece { it.appendEntry(B, SURFACE, "/options", str("Tacos")) }
        val tacos = entryDots(onB, "/options").single { it.replica == B }
        val later = voted.piece(onB)
        val (_, record) = later.records(SURFACE).single()
        assertAll(
            { assertFalse(record.basis.saw("/options", tacos)) },
            { assertTrue(Basis.capture(later, SURFACE).saw("/options", tacos)) },
        )
    }

    @Test
    fun recordCannotClaimAnEntryItsReplicaLacked() {
        val onB = base.piece { it.appendEntry(B, SURFACE, "/options", str("Tacos")) }
        val tacos = entryDots(onB, "/options").single { it.replica == B }
        val captured = Basis.capture(base, SURFACE)
        val claimed = captured.copy(surfaceDots = captured.surfaceDots + ("/options" to setOf(tacos)))
        val claimedRecord = captured.copy(recordDots = setOf(Dot(B, 1)))
        assertAll(
            { assertFalse(captured.saw("/options", tacos)) },
            { assertTrue(captured.completeIn(base, SURFACE)) },
            { assertFalse(claimed.completeIn(base, SURFACE)) },
            { assertTrue(claimed.completeIn(base.piece(onB), SURFACE)) },
            { assertFalse(claimedRecord.completeIn(base, SURFACE)) },
            // A replica that hasn't received the surface yet hasn't delivered the basis either.
            { assertFalse(captured.completeIn(BoardState.EMPTY, SURFACE)) },
        )
    }

    @Test
    fun floorIsCoveredAuthorByAuthor() {
        val three = base
            .piece { it.appendEntry(A, SURFACE, "/options", str("Tacos")) }
            .piece { it.appendEntry(A, SURFACE, "/options", str("Ramen")) }
        val captured = Basis.capture(three, SURFACE)
        fun floored(vararg high: Pair<ReplicaId, Long>) =
            captured.copy(surfaceDots = emptyMap(), surfaceFloors = mapOf("/options" to VersionVector.of(mapOf(*high))))
        assertAll(
            // Capture folds A's gap-free run 1..3 into the floor.
            { assertEquals(VersionVector.of(mapOf(A to 3L)), captured.surfaceFloors["/options"]) },
            { assertTrue(floored(A to 3L).completeIn(three, SURFACE)) },
            { assertFalse(floored(A to 3L).completeIn(base, SURFACE)) },
            { assertFalse(floored(A to 4L).completeIn(three, SURFACE)) },
            { assertFalse(floored(A to 1L, B to 1L).completeIn(three, SURFACE)) },
        )
    }

    @Test
    fun questionRevisionIsAnAppendedEntry() {
        val asked = base.piece { it.appendEntry(A, SURFACE, "/question", str("Lunch?")) }
        val beforeRevision = Basis.capture(asked, SURFACE)
        val revised = asked.piece { it.appendEntry(B, SURFACE, "/question", str("Lunch at noon?")) }
        val (first, second) = entryDots(revised, "/question")
        assertAll(
            { assertEquals(2, entryDots(revised, "/question").size) },
            { assertTrue(beforeRevision.saw("/question", first)) },
            { assertFalse(beforeRevision.saw("/question", second)) },
            { assertTrue(Basis.capture(revised, SURFACE).saw("/question", second)) },
        )
    }

    @Test
    fun recordsAreSeenApartFromSurfaceEntries() {
        val voted = base.piece { it.appendRecord(A, BoardFixtures.record(it)) }
        val (recordId, _) = voted.records(SURFACE).single()
        val basis = Basis.capture(voted, SURFACE)
        assertAll(
            // The record's dot and the option's dot are the same Dot(A, 1), in different sequences.
            { assertEquals(entryDots(voted, "/options").single(), recordId.dot) },
            { assertTrue(basis.sawRecord(recordId.dot)) },
            { assertFalse(Basis.capture(base, SURFACE).sawRecord(recordId.dot)) },
            { assertEquals(VersionVector.of(mapOf(A to 1L)), basis.recordFloor) },
        )
    }

    /** Drops every option of [state] through `Rga.dropWindow`, so A's dot folds into the floor. */
    private fun dropOptionsWindow(state: BoardState): Patch<BoardState> {
        val doc = state.surfaceDoc(SURFACE)!!.withReplica(A)
        val data = doc["data"] as JsonNode.Object
        val options = data.map["options"] as JsonNode.Array
        val (_, dropped) = options.rga.dropWindow(A, options.rga.entries().map { it.first }.toSet())!!
        val docPatch = doc.set("data", JsonNode.Object(data.map.put(A, "options", JsonNode.Array(dropped.delta)).delta))
        return Patch(BoardState.EMPTY.copy(surfaces = state.surfaces.put(A, SURFACE, docPatch.delta).delta))
    }
}
