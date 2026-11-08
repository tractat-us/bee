package us.tractat.bee.a2ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import us.tractat.kuilt.crdt.JsonCrdt
import us.tractat.kuilt.crdt.JsonNode
import us.tractat.kuilt.crdt.JsonValue
import us.tractat.kuilt.crdt.MVRegister
import us.tractat.kuilt.crdt.ORMap
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

class SurfaceProjectionTest {
    private val a = ReplicaId("web-a")
    private val b = ReplicaId("mcp-b")

    @Test
    fun concurrentScalarWritesProjectOneWinnerAndAConflict() {
        val base = surface(a)
        val onA = base.piece(writeAnswer(base, a, "x"))
        val onB = base.withReplica(b).let { it.piece(writeAnswer(it, b, "y")) }
        val viewA = onA.piece(onB).toSurfaceView("lunch")
        val viewB = onB.piece(onA).toSurfaceView("lunch")
        assertAll(
            { assertEquals(viewA, viewB) },
            { assertEquals(JsonPrimitive("y"), viewA.data["answer"]) },
            { assertEquals(mapOf("/answer" to listOf<JsonElement>(JsonPrimitive("x"), JsonPrimitive("y"))), viewA.conflicts) },
        )
    }

    @Test
    fun writeThatSawBothValuesClearsTheConflict() {
        val base = surface(a)
        val onA = base.piece(writeAnswer(base, a, "x"))
        val onB = base.withReplica(b).let { it.piece(writeAnswer(it, b, "y")) }
        val merged = onA.piece(onB)
        val resolved = merged.piece(writeAnswer(merged, a, "x"))
        val view = resolved.toSurfaceView("lunch")
        assertAll(
            { assertEquals(JsonPrimitive("x"), view.data["answer"]) },
            { assertEquals(emptyMap(), view.conflicts) },
            // Every peer that hears of the resolving write drops the conflict too.
            { assertEquals(view, onB.piece(resolved).toSurfaceView("lunch")) },
        )
    }

    @Test
    fun wholeArrayProjectsAsWritten() {
        // A whole array is one leaf, so a merge can't duplicate an item: repeats are the writer's.
        val base = surface(a)
        val scores = JsonArray(listOf(0, 0, 1).map(::JsonPrimitive))
        val doc = base.piece(write(base, a, "scores", SurfaceFormat.encodeLeaf(scores)))
        assertEquals(scores, doc.toSurfaceView("lunch").data["scores"])
    }

    /** Both kinds of array show as JSON arrays; only the append-only one is named in `appended` (bee#81). */
    @Test
    fun appendedNamesOnlyTheAppendOnlyArrays() {
        val base = surface(a)
        val whole = base.piece(write(base, a, "scores", SurfaceFormat.encodeLeaf(JsonArray(listOf(JsonPrimitive(1))))))
        val (_, op) = Rga.empty<JsonNode>().insertAt(a, 0, leaf(a, JsonValue.Str("Redis")))
        val data = (whole[SurfaceFormat.DATA] as JsonNode.Object).map
        val options = JsonNode.Array(Rga.empty<JsonNode>().apply(op))
        val doc = whole.piece(whole.set(SurfaceFormat.DATA, JsonNode.Object(data.put(a, "options", options).delta)))
        val view = doc.toSurfaceView("lunch")
        assertAll(
            { assertEquals(setOf("/options"), view.appended) },
            { assertEquals(JsonArray(listOf(JsonPrimitive("Redis"))), view.data["options"]) },
            { assertEquals(JsonArray(listOf(JsonPrimitive(1))), view.data["scores"], "control: the whole array shows too") },
        )
    }

    /** A surface document as bee-core lays it out: meta, one root component, empty data. */
    private fun surface(replica: ReplicaId): JsonCrdt {
        val meta = ORMap.empty<String, JsonNode>().piece {
            it.put(replica, SurfaceFormat.META_CATALOG_ID, leaf(replica, JsonValue.Str(NOTIONS_CATALOG_ID)))
        }
        val root = buildJsonObject {
            put("id", "root")
            put("component", "Text")
            put("text", "Lunch?")
        }
        val components = ORMap.empty<String, JsonNode>().piece {
            it.put(replica, "root", leaf(replica, SurfaceFormat.encodeLeaf(root)))
        }
        return JsonCrdt.empty(replica)
            .piece { it.set(SurfaceFormat.META, JsonNode.Object(meta)) }
            .piece { it.set(SurfaceFormat.COMPONENTS, JsonNode.Object(components)) }
            .piece { it.set(SurfaceFormat.DATA, JsonNode.Object(ORMap.empty())) }
    }

    private fun leaf(replica: ReplicaId, value: JsonValue) = JsonNode.Leaf(MVRegister.empty<JsonValue>().set(replica, value))

    private fun writeAnswer(doc: JsonCrdt, replica: ReplicaId, answer: String) =
        write(doc, replica, "answer", JsonValue.Str(answer))

    /** Writes data key [key] from the register just read, as bee-core does (A13). */
    private fun write(doc: JsonCrdt, replica: ReplicaId, key: String, value: JsonValue) = doc.withReplica(replica).let {
        val data = (it[SurfaceFormat.DATA] as JsonNode.Object).map
        val register = (data[key] as? JsonNode.Leaf)?.register ?: MVRegister.empty()
        it.set(SurfaceFormat.DATA, JsonNode.Object(data.put(replica, key, JsonNode.Leaf(register.set(replica, value))).delta))
    }
}
