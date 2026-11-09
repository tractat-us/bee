package us.tractat.bee.web

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import us.tractat.bee.a2ui.NOTIONS_CATALOG_ID
import us.tractat.bee.a2ui.SurfaceView
import us.tractat.bee.a2ui.toSurfaceView
import us.tractat.bee.core.ActionRecord
import us.tractat.bee.core.Basis
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.ClaimCommand
import us.tractat.bee.core.ClaimTable
import us.tractat.bee.core.ClaimTarget
import us.tractat.bee.core.ClaimToken
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.ProposalId
import us.tractat.bee.core.RecordId
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

/**
 * When a pressed Button stops reading sent (bee#213): [SentButtons.settle]'s rules, through a real
 * [BoardState], with [SentButtons]' `mark` recording what it would show.
 */
class SentButtonsTest {
    private val marks = mutableListOf<Pair<ButtonPress, Boolean>>()
    private val sent = SentButtons { press, isSent -> marks += press to isSent }

    @Test
    fun aPressReadsSentAndIsRefusedUntilItsRecordIsDone() {
        val state = board()
        sent.pressed(FILE, view(state), record(state), view(state))
        sent.settle(ASK, view(state), state)
        val stillWaiting = sent.waits(FILE)
        val held = state.withClaims(claim(state))
        sent.settle(ASK, view(held), held)
        val whileHeld = sent.waits(FILE)
        val done = held.withClaims(claim(state), complete(state))
        sent.settle(ASK, view(done), done)
        assertAll(
            { assertEquals(true, stillWaiting, "nothing changed and nobody holds it") },
            { assertEquals(true, whileHeld, "a held claim isn't handled") },
            { assertEquals(false, sent.waits(FILE), "done clears it") },
            { assertEquals(listOf(FILE to true, FILE to false), marks) },
        )
    }

    @Test
    fun aChangedSurfaceClearsAPressNobodyHolds() {
        val state = board()
        sent.pressed(FILE, view(state), record(state), view(state))
        val redrawn = state.noted("That code doesn't match.")
        sent.settle(ASK, view(redrawn), redrawn)
        assertAll(
            { assertEquals(false, sent.waits(FILE)) },
            { assertEquals(listOf(FILE to true, FILE to false), marks) },
        )
    }

    @Test
    fun aChangedSurfaceLeavesAPressAnAgentHoldsUntilItIsDoneOrReleased() {
        val state = board()
        sent.pressed(FILE, view(state), record(state), view(state))
        val working = state.withClaims(claim(state)).noted("now: filing the issue")
        sent.settle(ASK, view(working), working)
        val whileHeld = sent.waits(FILE)
        val released = working.withClaims(claim(state), release(state))
        sent.settle(ASK, view(released), released)
        assertAll(
            { assertEquals(true, whileHeld, "the agent's own write while it holds the record") },
            { assertEquals(false, sent.waits(FILE), "released, on a surface that changed since") },
        )
    }

    @Test
    fun aSurfaceNotShownClearsItsPressesEvenWhileHeld() {
        val state = board()
        sent.pressed(FILE, view(state), record(state), view(state))
        val held = state.withClaims(claim(state))
        // Null is what the driver settles with for a closed surface and for one shown as a placeholder.
        sent.settle(ASK, null, held)
        assertAll(
            { assertEquals(false, sent.waits(FILE)) },
            { assertEquals(listOf(FILE to true, FILE to false), marks) },
        )
    }

    @Test
    fun anotherSurfaceAndAnotherRowAreLeftAlone() {
        val state = board()
        sent.pressed(FILE, view(state), record(state), view(state))
        sent.settle("elsewhere", null, state)
        assertAll(
            { assertEquals(true, sent.waits(FILE)) },
            { assertEquals(false, sent.waits(FILE.copy(instance = "/items/1")), "a sibling row is its own button") },
            { assertEquals(listOf(FILE to true), marks) },
        )
    }

    @Test
    fun aPressOnAnythingButAButtonIsNotTracked() {
        val state = board()
        val note = ButtonPress(ASK, "note", "/")
        sent.pressed(note, view(state), record(state), view(state))
        assertAll(
            { assertEquals(false, sent.waits(note)) },
            { assertEquals(emptyList(), marks) },
        )
    }

    private companion object {
        val PAGE = ReplicaId("web-a")
        val HOST = ReplicaId("host")
        const val ASK = "ask"
        val FILE = ButtonPress(ASK, "file", "/")
        val alex = PeerIdentity.human("alex")
        val agent = PeerIdentity.agent("filer", Random(1))
        val T0: Instant = Instant.parse("2026-11-09T12:00:00Z")
        val P = ProposalId("node-a", 0, 1)

        /** An ask with a Button and a note, and alex's press of the Button recorded on it. */
        fun board(): BoardState {
            val opened = BoardState.EMPTY.piece {
                it.openSurface(PAGE, ASK, SurfaceMeta("File it?", NOTIONS_CATALOG_ID, agent, T0), components(), buildJsonObject { put("note", "waiting") })
            }
            val record = ActionRecord(ASK, "file", alex, "file", JsonObject(emptyMap()), Basis.capture(opened, ASK), T0)
            return opened.piece { it.appendRecord(PAGE, record) }
        }

        fun components(): List<JsonObject> = listOf(
            buildJsonObject {
                put("id", "root")
                put("component", "Column")
                put("children", JsonArray(listOf(JsonPrimitive("file"), JsonPrimitive("note"))))
            },
            buildJsonObject {
                put("id", "file")
                put("component", "Button")
                put("child", "file-label")
                putJsonObject("action") { putJsonObject("event") { put("name", "file") } }
            },
            buildJsonObject {
                put("id", "file-label")
                put("component", "Text")
                put("text", "Yes, file it")
            },
            buildJsonObject {
                put("id", "note")
                put("component", "Text")
                putJsonObject("text") { put("path", "/note") }
            },
        )

        fun view(state: BoardState): SurfaceView = requireNotNull(state.surfaceDoc(ASK)).toSurfaceView(ASK)

        fun record(state: BoardState): RecordId = state.records(ASK).single().first

        fun BoardState.noted(note: String): BoardState = piece { it.updateData(HOST, ASK, "/note", JsonPrimitive(note)) }

        fun target(state: BoardState): ClaimTarget = record(state).claimTarget

        fun claim(state: BoardState): ClaimCommand = ClaimCommand.Claim(target(state), agent, P)

        /** The holder's token: the claim's own entry, the first in the log. */
        fun complete(state: BoardState): ClaimCommand = ClaimCommand.Complete(target(state), agent, ClaimToken(term = 1, index = 1))

        fun release(state: BoardState): ClaimCommand = ClaimCommand.Release(target(state), agent, ClaimToken(term = 1, index = 1))

        /** This board with the claim table of [commands], committed in order, mirrored as the host does. */
        fun BoardState.withClaims(vararg commands: ClaimCommand): BoardState {
            val table = commands.toList().foldIndexed(ClaimTable.EMPTY) { i, table, command ->
                table.fold(Committed.Entry(LogEntry(index = i + 1L, term = 1L, command = ClaimCommand.encode(command))))
            }
            return piece { it.mirrorClaims(HOST, table) }
        }
    }
}
