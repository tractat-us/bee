@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.bee.core

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import us.tractat.bee.a2ui.A2uiMessage
import us.tractat.bee.a2ui.NOTIONS_CATALOG_ID
import us.tractat.bee.a2ui.SurfaceFormat
import us.tractat.bee.a2ui.diff
import us.tractat.bee.a2ui.toSurfaceView
import us.tractat.bee.core.BoardFixtures.A
import us.tractat.bee.core.BoardFixtures.B
import us.tractat.bee.core.BoardFixtures.SURFACE
import us.tractat.bee.core.BoardFixtures.addCard
import us.tractat.bee.core.BoardFixtures.card
import us.tractat.bee.core.BoardFixtures.cardOrder
import us.tractat.bee.core.BoardFixtures.opened
import us.tractat.bee.core.BoardFixtures.str
import us.tractat.bee.core.BoardFixtures.values
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.JsonNode
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.quilter.QuiltMessage
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
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
        // A JSON array written as data is stored whole, as one leaf: concurrent reorders conflict
        // as values, and each value is one writer's whole permutation. A card order is an
        // append-only Rga instead (ReplaceSemanticsTest.theOrderShowsEachKeyedItemOnce).
        val base = opened(buildJsonObject { put("order", ids("a", "b", "c")) })
        val onA = base.piece { it.updateData(A, SURFACE, "/order", ids("c", "a", "b")) }
        val onB = base.piece { it.updateData(B, SURFACE, "/order", ids("b", "c", "a")) }
        val merged = onA.piece(onB)
        val orders = values(merged, "/order")
        assertAll(
            { assertIs<JsonNode.Leaf>(BoardFixtures.node(merged, "/order")) },
            { assertEquals(setOf(ids("c", "a", "b"), ids("b", "c", "a")), orders) },
            // The projection shows one whole permutation, the greater encoded value.
            { assertEquals(ids("c", "a", "b"), merged.surfaceDoc(SURFACE)!!.toSurfaceView(SURFACE).data["order"]) },
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

    @Test
    fun deltasJoinInAnyOrderWithRepeats() {
        // Three replicas run every mutator; their six deltas, delivered in all 720 orders with the
        // first delivered twice, reach one state and one projection (the lattice laws).
        // A's replace of /poll races B's add to it, B's append to the array it clears, B's write to
        // a leaf it clears and B's write inside an object it clears. A then rewrites two cleared
        // paths, which must still conflict with or sit beside B's writes. A and C each add a card
        // to one order (#14).
        val base = addCard(
            opened(
                buildJsonObject {
                    put("order", ids("a", "b"))
                    put(
                        "poll",
                        buildJsonObject {
                            put("question", "Lunch?")
                            put("votes", 3)
                            put("meta", buildJsonObject { put("by", "a"); put("at", "noon") })
                        },
                    )
                    put("board", buildJsonObject { put("items", JsonObject(emptyMap())) })
                },
            ),
            A,
            "c0",
        ).piece { it.appendEntry(A, SURFACE, "/poll/options", str("Pizza")) }
        val c = ReplicaId("host-c")
        val second = SurfaceMeta("Second", NOTIONS_CATALOG_ID, PeerIdentity.human("iain"), BoardFixtures.OPENED_AT)
        val cards = buildJsonObject { put("c1", buildJsonObject { put("label", "Order") }) }
        // Each replica's second delta is made on its state after its first, as a live peer's is.
        val deltas = deltasOf(
            base,
            listOf(
                { it.updateData(A, SURFACE, "/answer", str("x")) },
                { it.appendEntry(A, SURFACE, "/options", str("Pizza")) },
                { it.updateData(A, SURFACE, "/poll", buildJsonObject { put("question", "Dinner?") }) },
            ),
            listOf(
                { it.appendRecord(A, BoardFixtures.record(it)) },
                { it.updateData(A, SURFACE, "/cards", cards) },
                { it.updateData(A, SURFACE, "/board/items/c1", card("c1")) },
                { it.appendEntry(A, SURFACE, "/board/order", str("c1")) },
                { it.updateData(A, SURFACE, "/poll/votes", JsonPrimitive(5)) },
                { it.updateData(A, SURFACE, "/poll/meta/at", str("one")) },
            ),
        ) + deltasOf(
            base,
            listOf(
                { it.updateComponents(B, SURFACE, listOf(BoardFixtures.root, button)) },
                { it.updateData(B, SURFACE, "/answer", str("y")) },
                { it.updateData(B, SURFACE, "/poll/comment", str("hi")) },
                { it.appendEntry(B, SURFACE, "/poll/options", str("Tacos")) },
                { it.updateData(B, SURFACE, "/poll/votes", JsonPrimitive(4)) },
                { it.updateData(B, SURFACE, "/poll/meta/by", str("b")) },
            ),
            listOf({ it.closeSurface(B, SURFACE, Instant.parse("2026-11-06T13:00:00Z")) }),
        ) + deltasOf(
            base,
            listOf(
                { it.openSurface(c, "second", second, listOf(BoardFixtures.root), JsonObject(emptyMap())) },
                { it.appendEntry(c, SURFACE, "/options", str("Tacos")) },
            ),
            listOf(
                { it.reopenSurface(c, SURFACE) },
                { it.updateData(c, SURFACE, "/order", ids("b", "a")) },
                { it.updateData(c, SURFACE, "/board/items/c2", card("c2")) },
                { it.appendEntry(c, SURFACE, "/board/order", str("c2")) },
            ),
        )
        val expected = deltas.fold(base) { state, delta -> state.piece(delta) }
        val outcomes = permutations(deltas).map { order -> (order + order.first()).fold(base) { state, d -> state.piece(d) } }
        fun views(state: BoardState) = state.surfaces.keys.associateWith { state.surfaceDoc(it)!!.toSurfaceView(it) }
        assertAll(
            { assertEquals(720, outcomes.size) },
            { assertTrue(outcomes.all { it == expected }) },
            { assertTrue(outcomes.all { views(it) == views(expected) }) },
            // The race between B's close and C's reopen resolves to closed.
            { assertEquals(listOf(SURFACE), expected.closedSurfaces()) },
            { assertEquals(listOf("x", "y").map(::str), views(expected).getValue(SURFACE).conflicts["/answer"]) },
            {
                assertEquals(
                    buildJsonObject {
                        put("comment", "hi")
                        put("meta", buildJsonObject { put("at", "one"); put("by", "b") })
                        put("options", ids("Tacos"))
                        put("question", "Dinner?")
                        put("votes", 5)
                    },
                    views(expected).getValue(SURFACE).data["poll"],
                )
            },
            { assertEquals(listOf(4, 5).map(::JsonPrimitive), views(expected).getValue(SURFACE).conflicts["/poll/votes"]) },
            { assertEquals(setOf("c0", "c1", "c2"), cardOrder(expected).toSet()) },
            { assertEquals(3, cardOrder(expected).size) },
        )
    }

    @Test
    fun closeAndReopenDiffToDeleteThenCreate() {
        // The driver shows a closed surface as nothing, so closing diffs to a delete, and
        // reopening to a fresh create of the surface as it now is.
        val board = opened(buildJsonObject { put("answer", "pizza") })
        val closed = board.piece { it.closeSurface(B, SURFACE, Instant.parse("2026-11-06T13:00:00Z")) }
            .piece { it.updateData(B, SURFACE, "/answer", str("tacos")) }
        val reopened = closed.piece { it.reopenSurface(A, SURFACE) }
        fun shown(state: BoardState) =
            state.surfaceDoc(SURFACE)!!.takeIf { state.meta(SURFACE)!!.closedAt == null }?.toSurfaceView(SURFACE)
        assertAll(
            { assertEquals(listOf(A2uiMessage.DeleteSurface(SURFACE)), diff(shown(board), shown(closed))) },
            {
                assertEquals(
                    listOf(
                        A2uiMessage.CreateSurface(SURFACE, NOTIONS_CATALOG_ID),
                        A2uiMessage.UpdateComponents(SURFACE, listOf(BoardFixtures.root)),
                        A2uiMessage.UpdateDataModel(SURFACE, "/", buildJsonObject { put("answer", "tacos") }),
                    ),
                    diff(shown(closed), shown(reopened)),
                )
            },
        )
    }

    @Test
    fun slashIsTheDataModelRoot() {
        val state = opened().piece { it.updateData(A, SURFACE, "/", buildJsonObject { put("answer", "pizza") }) }
        assertAll(
            { assertEquals(setOf("answer"), (BoardFixtures.node(state, "") as JsonNode.Object).map.keys) },
            { assertFailsWith<IllegalArgumentException> { state.updateData(A, SURFACE, "/a//b", str("x")) } },
            { assertFailsWith<IllegalArgumentException> { state.updateData(A, SURFACE, "/answer/", str("x")) } },
            { assertFailsWith<IllegalArgumentException> { state.updateData(A, SURFACE, "/", buildJsonObject { put("", "x") }) } },
            { assertFailsWith<IllegalArgumentException> { state.updateData(ReplicaId(""), SURFACE, "/answer", str("x")) } },
        )
    }

    @Test
    fun titleWithTheReservedPrefixIsSafe() {
        val title = SurfaceFormat.STRUCTURED_PREFIX + "[not json"
        val meta = SurfaceMeta(title, NOTIONS_CATALOG_ID, PeerIdentity.human("iain"), BoardFixtures.OPENED_AT)
        val state = BoardState.EMPTY.piece { it.openSurface(A, "odd", meta, listOf(BoardFixtures.root), JsonObject(emptyMap())) }
        assertAll(
            { assertEquals(title, state.meta("odd")!!.title) },
            { assertEquals(listOf("odd"), state.openSurfaces()) },
            { assertEquals(NOTIONS_CATALOG_ID, state.surfaceDoc("odd")!!.toSurfaceView("odd").catalogId) },
        )
    }

    /**
     * One replica's deltas, one per group of writes. Each write runs on the state the one before it
     * left, across groups too, so the replica never mints a dot twice.
     */
    private fun deltasOf(state: BoardState, vararg groups: List<(BoardState) -> Patch<BoardState>>): List<BoardState> {
        var current = state
        return groups.map { writes ->
            writes.fold(BoardState.EMPTY) { delta, write ->
                val patch = write(current)
                current = current.piece(patch)
                delta.piece(patch.delta)
            }
        }
    }

    /**
     * A populated board through the Quilter's own wire, CBOR by default: a replica seeded with it
     * hands it to an empty one in its first-contact `FullState`. bee-core's compile classpath has
     * no CBOR (kuilt-quilter keeps it `implementation`), so the Quilter is the codec, which is
     * also the codec the board really travels in. It covers a record with a basis, every
     * `JsonValue` kind (the null is a reopened surface's `closedAt`) and a closed surface's meta.
     */
    @Test
    fun populatedBoardRoundTripsThroughTheQuilterWire() = runTest(StandardTestDispatcher()) {
        val meta = SurfaceMeta("Later", NOTIONS_CATALOG_ID, PeerIdentity.human("iain"), BoardFixtures.OPENED_AT)
        val closedAt = Instant.parse("2026-11-06T13:00:00Z")
        val data = buildJsonObject {
            put("flag", true)
            put("count", 2)
            put("ratio", 0.5)
            put("answer", "pizza")
            put("nested", buildJsonObject { put("a", "b") })
            put("list", ids("x", "y"))
        }
        val board = opened(data)
            .piece { it.appendEntry(A, SURFACE, "/options", str("Pizza")) }
            .let { it.piece { s -> s.appendRecord(A, BoardFixtures.record(s)) } }
            .piece { it.openSurface(B, "closed", meta, listOf(BoardFixtures.root), JsonObject(emptyMap())) }
            .piece { it.closeSurface(B, "closed", closedAt) }
            .piece { it.openSurface(B, "reopened", meta, listOf(BoardFixtures.root), JsonObject(emptyMap())) }
            .piece { it.closeSurface(B, "reopened", closedAt) }
            .piece { it.reopenSurface(B, "reopened") }
        val loom = InMemoryLoom()
        val config = QuilterConfig(expectVirtualTime = true)
        val wire = QuiltMessage.serializer(BoardState.serializer())
        Quilter(ReplicaId("q-a"), loom.weave(Rendezvous.New(Pattern("board"))), board, wire, backgroundScope, config)
        val receiver = Quilter(ReplicaId("q-b"), loom.weave(Rendezvous.Existing(InMemoryTag("board"))), BoardState.EMPTY, wire, backgroundScope, config)
        advanceTimeBy(100.milliseconds)
        runCurrent()
        val got = receiver.state.value
        assertAll(
            { assertEquals(1, board.records(SURFACE).size, "precondition: the board holds a record") },
            { assertEquals(board, got) },
            { assertEquals(board.records(SURFACE), got.records(SURFACE), "records, with their bases") },
            { assertEquals(meta.copy(closedAt = closedAt), got.meta("closed")) },
            { assertEquals(meta, got.meta("reopened")) },
            { assertEquals(listOf(SURFACE, "reopened"), got.openSurfaces()) },
            { assertEquals(setOf(JsonPrimitive(0.5)), values(got, "/ratio")) },
            { assertEquals(setOf(JsonPrimitive(true)), values(got, "/flag")) },
            { assertEquals(setOf<JsonElement>(ids("x", "y")), values(got, "/list")) },
        )
    }

    private fun <T> permutations(items: List<T>): List<List<T>> =
        if (items.size <= 1) {
            listOf(items)
        } else {
            items.indices.flatMap { i -> permutations(items.take(i) + items.drop(i + 1)).map { listOf(items[i]) + it } }
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

    /** [SURFACE] as the driver shows it. */
    fun view(state: BoardState) = state.surfaceDoc(SURFACE)!!.toSurfaceView(SURFACE)

    fun card(label: String) = buildJsonObject { put("label", label); put("column", "todo") }

    /** A board whose `/board` is an ordered collection holding [ids], in that order, all added on [A]. */
    fun board(vararg ids: String): BoardState {
        val empty = opened(buildJsonObject { put("board", buildJsonObject { put("items", JsonObject(emptyMap())) }) })
        return ids.fold(empty) { state, id -> addCard(state, A, id) }
    }

    /** [replica] adds card [id] the way a Board does: the keyed item, then its id appended to the order. */
    fun addCard(state: BoardState, replica: ReplicaId, id: String): BoardState = state
        .piece { it.updateData(replica, SURFACE, "/board/items/$id", card(id)) }
        .piece { it.appendEntry(replica, SURFACE, "/board/order", str(id)) }

    /** The ids `/board/order` shows. */
    fun cardOrder(state: BoardState): List<String> =
        ((view(state).data.getValue("board") as JsonObject).getValue("order") as JsonArray)
            .map { (it as JsonPrimitive).content }
}
