package us.tractat.bee.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import us.tractat.bee.core.BoardFixtures.A
import us.tractat.bee.core.BoardFixtures.B
import us.tractat.bee.core.BoardFixtures.SURFACE
import us.tractat.bee.core.BoardFixtures.opened
import us.tractat.bee.core.BoardFixtures.str
import us.tractat.bee.core.BoardFixtures.values
import us.tractat.bee.a2ui.NOTIONS_CATALOG_ID
import us.tractat.bee.a2ui.SurfaceFormat
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.JsonNode
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class BoardStateTest {
    @Test
    fun concurrentEditsToOneSurfaceConverge() {
        val base = opened()
        val onA = base.piece { it.updateData(A, SURFACE, "/note", str("from a")) }
            .piece { it.appendEntry(A, SURFACE, "/options", str("Pizza")) }
        val onB = base.piece { it.updateData(B, SURFACE, "/count", JsonPrimitive(2)) }
            .piece { it.updateComponents(B, SURFACE, listOf(BoardFixtures.root, button)) }
            .piece { it.appendEntry(B, SURFACE, "/options", str("Tacos")) }

        val ab = onA.piece(onB)
        val ba = onB.piece(onA)
        assertAll(
            { assertEquals(ab, ba) },
            { assertEquals(setOf(str("from a")), values(ab, "/note")) },
            { assertEquals(setOf(JsonPrimitive(2)), values(ab, "/count")) },
            { assertEquals(2, BoardFixtures.entryDots(ab, "/options").size) },
            { assertEquals(setOf("root", "go"), (ab.surfaceDoc(SURFACE)!!["components"] as JsonNode.Object).map.keys) },
        )
    }

    @Test
    fun closeKeepsTheSurfaceAndItsRecords() {
        val voted = opened(buildJsonObject { put("answer", "pizza") })
            .let { it.piece { s -> s.appendRecord(A, BoardFixtures.record(s)) } }
        val closedAt = Instant.parse("2026-11-06T13:00:00Z")
        val closed = voted.piece { it.closeSurface(B, SURFACE, closedAt) }
        val reopened = closed.piece { it.reopenSurface(A, SURFACE) }
        assertAll(
            { assertEquals(emptyList(), closed.openSurfaces()) },
            { assertEquals(listOf(SURFACE), closed.closedSurfaces()) },
            { assertEquals(closedAt, closed.meta(SURFACE)!!.closedAt) },
            { assertEquals(1, closed.records(SURFACE).size) },
            { assertEquals(setOf(str("pizza")), values(closed, "/answer")) },
            { assertEquals(listOf(SURFACE), reopened.openSurfaces()) },
            { assertEquals(1, reopened.records(SURFACE).size) },
        )
    }

    @Test
    fun serializedStateRoundTripsAfterWithReplica() {
        val state = opened(buildJsonObject { put("answer", "pizza") })
            .piece { it.appendEntry(A, SURFACE, "/options", str("Pizza")) }
            .let { it.piece { s -> s.appendRecord(A, BoardFixtures.record(s)) } }
        val json = Json { allowStructuredMapKeys = true }
        val decoded = json.decodeFromString(BoardState.serializer(), json.encodeToString(BoardState.serializer(), state))
            .withReplica(B)
        val edited = decoded.piece { it.updateData(B, SURFACE, "/answer", str("tacos")) }
            .piece { it.appendEntry(B, SURFACE, "/options", str("Tacos")) }
        assertAll(
            { assertEquals(state, decoded) },
            { assertEquals(state.records(SURFACE), decoded.records(SURFACE)) },
            { assertEquals(setOf(str("tacos")), values(edited, "/answer")) },
            { assertEquals(listOf(Dot(A, 1), Dot(B, 1)), BoardFixtures.entryDots(edited, "/options")) },
        )
    }

    @Test
    fun rewriteOfAReadLeafLeavesNoPhantomConflict() {
        // A13: each write starts from the register just read, so it supersedes what it read.
        val once = opened().piece { it.updateData(A, SURFACE, "/answer", str("x")) }
        val twice = once.piece { it.updateData(A, SURFACE, "/answer", str("y")) }
        val seenByB = BoardState.EMPTY.piece(twice)
        val rewrittenByB = seenByB.piece { it.updateData(B, SURFACE, "/answer", str("z")) }
        assertAll(
            { assertEquals(setOf(str("y")), values(twice, "/answer")) },
            { assertEquals(setOf(str("z")), values(rewrittenByB.piece(twice), "/answer")) },
        )
    }

    @Test
    fun concurrentEditsOfOneKeyedItemDoNotDuplicateIt() {
        // A12: editable state is an object keyed by a stable id, never an Rga element.
        val base = opened(
            buildJsonObject {
                put("cards", buildJsonObject { put("c1", buildJsonObject { put("label", "Order"); put("column", "todo") }) })
            },
        )
        val onA = base.piece { it.updateData(A, SURFACE, "/cards/c1/label", str("Order food")) }
        val onB = base.piece { it.updateData(B, SURFACE, "/cards/c1/column", str("done")) }
        val merged = onA.piece(onB)
        assertAll(
            { assertEquals(setOf("c1"), (BoardFixtures.node(merged, "/cards") as JsonNode.Object).map.keys) },
            { assertEquals(setOf(str("Order food")), values(merged, "/cards/c1/label")) },
            { assertEquals(setOf(str("done")), values(merged, "/cards/c1/column")) },
        )
    }

    @Test
    fun reorderingCannotDuplicateAnItem() {
        // An order array is stored whole, as one leaf: concurrent reorders conflict as values,
        // and each value is one writer's whole permutation.
        val base = opened(buildJsonObject { put("order", ids("a", "b", "c")) })
        val onA = base.piece { it.updateData(A, SURFACE, "/order", ids("c", "a", "b")) }
        val onB = base.piece { it.updateData(B, SURFACE, "/order", ids("b", "c", "a")) }
        val merged = onA.piece(onB)
        val orders = values(merged, "/order")
        assertAll(
            { assertIs<JsonNode.Leaf>(BoardFixtures.node(merged, "/order")) },
            { assertEquals(setOf(ids("c", "a", "b"), ids("b", "c", "a")), orders) },
            { assertTrue(orders.all { (it as JsonArray).distinct().size == 3 }) },
        )
    }

    @Test
    fun pathTypeChangeIsRefused() {
        val state = opened(buildJsonObject { put("answer", "pizza"); put("order", ids("a")); put("cards", JsonObject(emptyMap())) })
            .piece { it.appendEntry(A, SURFACE, "/options", str("Pizza")) }
        assertAll(
            { assertFailsWith<IllegalArgumentException> { state.updateData(B, SURFACE, "/answer", JsonObject(emptyMap())) } },
            { assertFailsWith<IllegalArgumentException> { state.updateData(B, SURFACE, "/answer", ids("a")) } },
            { assertFailsWith<IllegalArgumentException> { state.updateData(B, SURFACE, "/order", str("a")) } },
            { assertFailsWith<IllegalArgumentException> { state.appendEntry(B, SURFACE, "/order", str("b")) } },
            { assertFailsWith<IllegalArgumentException> { state.updateData(B, SURFACE, "/options", ids("Tacos")) } },
            { assertFailsWith<IllegalArgumentException> { state.updateData(B, SURFACE, "/cards", str("none")) } },
            { assertFailsWith<IllegalArgumentException> { state.updateData(B, SURFACE, "/answer/inner", str("x")) } },
        )
    }

    private val button = buildJsonObject {
        put("id", "go")
        put("component", "Button")
        put("child", "root")
    }

    private fun ids(vararg ids: String): JsonArray = JsonArray(ids.map(::JsonPrimitive))
}

/** Two peers' CRDT authors and a surface to share, for the in-memory board tests. */
internal object BoardFixtures {
    val A = ReplicaId("web-a")
    val B = ReplicaId("mcp-b")
    const val SURFACE = "lunch"
    val OPENED_AT: Instant = Instant.parse("2026-11-06T12:00:00Z")

    val root: JsonObject = buildJsonObject {
        put("id", "root")
        put("component", "Text")
        put("text", "Lunch?")
    }

    /** A board holding one open surface, opened on [A]. */
    fun opened(data: JsonObject = JsonObject(emptyMap())): BoardState {
        val meta = SurfaceMeta("Lunch", NOTIONS_CATALOG_ID, PeerIdentity.human("iain"), OPENED_AT)
        return BoardState.EMPTY.piece { it.openSurface(A, SURFACE, meta, listOf(root), data) }
    }

    /** A vote on [SURFACE], its basis captured from [state]. */
    fun record(state: BoardState): ActionRecord = ActionRecord(
        surfaceId = SURFACE,
        componentId = "root",
        actor = PeerIdentity.human("iain"),
        name = "vote",
        context = JsonObject(emptyMap()),
        basis = Basis.capture(state, SURFACE),
        at = OPENED_AT,
    )

    /** The data node at [pointer] in [SURFACE]. */
    fun node(state: BoardState, pointer: String): JsonNode? =
        SurfaceFormat.segments(pointer).fold(state.surfaceDoc(SURFACE)!![SurfaceFormat.DATA]) { node, key ->
            (node as JsonNode.Object).map[key]
        }

    /** Every value the leaf at [pointer] holds, decoded. */
    fun values(state: BoardState, pointer: String): Set<JsonElement> =
        (node(state, pointer) as JsonNode.Leaf).register.values.map(SurfaceFormat::decodeLeaf).toSet()

    /** The dots of the entries of the append-only array at [pointer], in sequence order. */
    fun entryDots(state: BoardState, pointer: String): List<Dot> =
        (node(state, pointer) as JsonNode.Array).rga.entries().map { it.first.dot }

    fun str(s: String) = JsonPrimitive(s)
}
