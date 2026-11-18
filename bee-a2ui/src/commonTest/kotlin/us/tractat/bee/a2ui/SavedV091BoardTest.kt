package us.tractat.bee.a2ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import us.tractat.kuilt.crdt.JsonCrdt
import us.tractat.kuilt.crdt.JsonNode
import us.tractat.kuilt.crdt.JsonValue
import us.tractat.kuilt.crdt.MVRegister
import us.tractat.kuilt.crdt.ORMap
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Boards saved under A2UI v0.9.1, read back whole (bee#230): every binding shows in v1.0's
 * `{"@path"}` spelling ([A2uiBindings.upgrade]), so the validator passes them and Notions acts on
 * them. The fixtures are what agents were taught to write, not what bee writes now.
 */
class SavedV091BoardTest {
    private val replica = ReplicaId("mcp-old")

    /**
     * The checkin card exactly as the skill taught it before bee#248: hyphenated ids, an `h5`
     * heading, and a binding inside a Button's action context.
     */
    @Test
    fun theCheckinCardFromBeforeV10ValidatesAndReadsInV10() {
        val view = saved(Json.parseToJsonElement(CHECKIN_COMPONENTS).jsonArray, Json.parseToJsonElement(CHECKIN_DATA).jsonObject)
            .toSurfaceView("bee")
        val send = view.components.getValue("send")
        assertAll(
            { assertEquals(emptyList(), SurfaceValidator.errors(view)) },
            { assertEquals(emptyList(), legacyBindings(JsonArray(view.components.values.toList())), "no v0.9.1 binding is left") },
            { assertEquals(A2uiBindings.binding("/now"), view.components.getValue("now")["text"]) },
            { assertEquals(A2uiBindings.binding("/answer"), view.components.getValue("answer")["value"]) },
            {
                assertEquals(
                    A2uiBindings.binding("/answer"),
                    send["action"]?.jsonObject?.get("event")?.jsonObject?.get("context")?.jsonObject?.get("text"),
                    "the binding in the action context",
                )
            },
            { assertEquals(JsonPrimitive("h5"), view.components.getValue("ask")["variant"], "control: the heading is kept") },
            { assertEquals(JsonPrimitive("opt-1-label"), view.components.getValue("opt-1")["child"], "control: the ids are kept") },
        )
    }

    /** An answer on a Poll saved with `{"path"}` bindings writes the actor's own answer. */
    @Test
    fun anAnswerOnASavedV091PollWritesTheActorsAnswer() {
        val poll = Json.parseToJsonElement(
            """{"id":"root","component":"Poll","question":{"path":"/question"},"options":{"path":"/options"},"answers":{"path":"/answers"}}""",
        ).jsonObject
        val data = Json.parseToJsonElement("""{"question":["Which cache?"],"options":["Redis","Memcached"],"answers":{}}""").jsonObject
        val view = saved(JsonArray(listOf(poll)), data).toSurfaceView("cache")
        val answer = A2uiAction(
            name = NotionActions.ANSWER,
            surfaceId = "cache",
            sourceComponentId = "root",
            timestamp = "2026-11-08T12:00:00Z",
            context = JsonObject(mapOf("option" to JsonPrimitive("Redis"))),
        )
        assertAll(
            { assertEquals(emptyList(), SurfaceValidator.errors(view)) },
            {
                // A refusal is compared, not thrown: kuilt's assertAll rethrows it, hiding the validator's errors.
                assertEquals<Any?>(
                    NotionEdit.Write("/answers/human:alex", JsonPrimitive("Redis")),
                    runCatching { NotionActions.edit(view, answer, "human:alex", AT) }.getOrElse { it.toString() },
                )
            },
        )
    }

    /** A surface document holding [components] and [data] as a v0.9.1 writer left them: each a whole leaf. */
    private fun saved(components: JsonArray, data: JsonObject): JsonCrdt {
        val meta = ORMap.empty<String, JsonNode>().piece {
            it.put(replica, SurfaceFormat.META_CATALOG_ID, leaf(JsonValue.Str(NOTIONS_CATALOG_ID)))
        }
        val componentMap = components.map { it.jsonObject }.fold(ORMap.empty<String, JsonNode>()) { map, component ->
            map.piece { it.put(replica, component.getValue("id").jsonPrimitive.content, leaf(SurfaceFormat.encodeLeaf(component))) }
        }
        val dataMap = data.entries.fold(ORMap.empty<String, JsonNode>()) { map, (key, value) ->
            map.piece { it.put(replica, key, leaf(SurfaceFormat.encodeLeaf(value))) }
        }
        return JsonCrdt.empty(replica)
            .piece { it.set(SurfaceFormat.META, JsonNode.Object(meta)) }
            .piece { it.set(SurfaceFormat.COMPONENTS, JsonNode.Object(componentMap)) }
            .piece { it.set(SurfaceFormat.DATA, JsonNode.Object(dataMap)) }
    }

    private fun leaf(value: JsonValue) = JsonNode.Leaf(MVRegister.empty<JsonValue>().set(replica, value))

    /** Every v0.9.1 binding in [element]: an object whose only key is `path`. */
    private fun legacyBindings(element: JsonElement): List<JsonObject> = when (element) {
        is JsonObject -> if (element.keys == setOf("path")) listOf(element) else element.values.flatMap(::legacyBindings)
        is JsonArray -> element.flatMap(::legacyBindings)
        else -> emptyList()
    }

    private companion object {
        val AT: Instant = Instant.parse("2026-11-09T12:00:00Z")

        /** Step 2 of `plugins/bee/skills/checkin/SKILL.md` at `2461c2a0^`, verbatim. */
        const val CHECKIN_COMPONENTS =
            """[{"id":"root","component":"Column","children":["now","progress","decision","stop"]},{"id":"now","component":"Text","text":{"path":"/now"}},{"id":"progress","component":"Text","text":{"path":"/progress"}},{"id":"decision","component":"Column","children":["ask","options","answer","send"]},{"id":"ask","component":"Text","variant":"h5","text":"Squash the fix into one commit?"},{"id":"options","component":"Row","children":["opt-1","opt-2"]},{"id":"opt-1","component":"Button","child":"opt-1-label","action":{"event":{"name":"decide","context":{"choice":"squash"}}}},{"id":"opt-1-label","component":"Text","text":"Squash"},{"id":"opt-2","component":"Button","child":"opt-2-label","action":{"event":{"name":"decide","context":{"choice":"keep"}}}},{"id":"opt-2-label","component":"Text","text":"Keep the commits"},{"id":"answer","component":"TextField","label":"Or say something else","value":{"path":"/answer"}},{"id":"send","component":"Button","child":"send-label","action":{"event":{"name":"answer","context":{"text":{"path":"/answer"}}}}},{"id":"send-label","component":"Text","text":"Send"},{"id":"stop","component":"Row","children":["done","pause"]},{"id":"done","component":"Button","child":"done-label","action":{"event":{"name":"done","context":{}}}},{"id":"done-label","component":"Text","text":"Done"},{"id":"pause","component":"Button","child":"pause-label","action":{"event":{"name":"pause","context":{}}}},{"id":"pause-label","component":"Text","text":"Pause"}]"""

        /** The same step's data, verbatim. */
        const val CHECKIN_DATA =
            """{"now":"Fixing the flaky retry test","progress":"- done: found the race in RetryPolicy\n- next: fix it, run the suite","answer":""}"""
    }
}
