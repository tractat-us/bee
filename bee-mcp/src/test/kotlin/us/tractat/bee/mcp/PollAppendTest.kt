package us.tractat.bee.mcp

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import us.tractat.bee.a2ui.NOTIONS_CATALOG_ID
import us.tractat.bee.a2ui.SurfaceFormat
import us.tractat.bee.a2ui.toSurfaceView
import us.tractat.bee.core.Basis
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.SurfaceMeta
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.JsonNode
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * An agent's Poll keeps its options basis (bee#81): the bridge appends a Poll's question and
 * options as `Rga` entries, so a vote's basis names the options the voter saw (spec §5.6, §6.1),
 * and it refuses a Poll whose question or options are written as data.
 */
class PollAppendTest {
    /** The demo's Poll, opened the way the cheat-sheet shows it: the browser's basis names every option. */
    @Test
    fun anAgentsPollNamesEachOptionInAVotesBasis() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        val browser = rig.browser()

        val opened = ada.call(BeeTools.OPEN, pollArgs("cache", append = """{"/question": ["$QUESTION"], "/options": $OPTIONS}"""))
        val seen = browser.state.first { "cache" in it.openSurfaces() }
        val basis = Basis.capture(seen, "cache")
        val options = entries(seen, "cache", "/options")
        assertAll(
            { assertEquals(null, opened.isError, opened.text()) },
            { assertEquals(listOf("Redis", "Memcached", "In-process"), shown(seen, "cache", "options"), "the options show in order") },
            { assertEquals(listOf(QUESTION), shown(seen, "cache", "question")) },
            { assertEquals(3, options.size, "each option is its own entry") },
            { assertTrue(options.isNotEmpty() && options.all { basis.saw("/options", it) }, "a vote's basis names every option: $basis") },
            { assertEquals(1, entries(seen, "cache", "/question").size, "the question is an appended revision") },
        )
        rig.close()
    }

    /** A Poll whose question or options are data is refused, naming the fix, and the board is unchanged. */
    @Test
    fun aPollWhoseQuestionOrOptionsAreDataIsRefused() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")

        val wholeOptions = ada.call(
            BeeTools.OPEN,
            pollArgs("a", data = """{"options": $OPTIONS, "answers": {}}""", append = """{"/question": ["$QUESTION"]}"""),
        )
        val scalarQuestion = ada.call(
            BeeTools.OPEN,
            pollArgs("b", data = """{"question": "$QUESTION", "answers": {}}""", append = """{"/options": $OPTIONS}"""),
        )
        val control = ada.call(BeeTools.OPEN, pollArgs("c", append = """{"/question": ["$QUESTION"], "/options": $OPTIONS}"""))
        assertAll(
            { assertTrue(wholeOptions.isError == true && "options" in wholeOptions.text() && "append" in wholeOptions.text(), wholeOptions.text()) },
            { assertTrue(scalarQuestion.isError == true && "question" in scalarQuestion.text() && "append" in scalarQuestion.text(), scalarQuestion.text()) },
            { assertEquals(null, control.isError, "control: the appended Poll opens: ${control.text()}") },
            { assertEquals(listOf("c"), ada.client.state.value.openSurfaces(), "only the appended Poll reached the board") },
        )
        rig.close()
    }

    /** `append` on an update adds an option as a new entry; the others keep their identity, and none is rewritten. */
    @Test
    fun updateAppendsAnOptionAndRewritesNone() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        ada.call(BeeTools.OPEN, pollArgs("cache", append = """{"/question": ["$QUESTION"], "/options": $OPTIONS}"""))
        val before = entries(ada.client.state.value, "cache", "/options")

        val appended = ada.call(BeeTools.UPDATE, mapOf("surfaceId" to "cache", "append" to Json.parseToJsonElement("""{"/options": ["SQLite"]}""")))
        val rewrite = ada.call(BeeTools.UPDATE, mapOf("surfaceId" to "cache", "data" to Json.parseToJsonElement("""{"/options": ["Redis"]}""")))
        val after = ada.client.state.value
        assertAll(
            { assertEquals(null, appended.isError, appended.text()) },
            { assertEquals(listOf("Redis", "Memcached", "In-process", "SQLite"), shown(after, "cache", "options")) },
            { assertTrue(entries(after, "cache", "/options").containsAll(before) && entries(after, "cache", "/options").size == 4, "the first three keep their dots") },
            { assertTrue(rewrite.isError == true, "options can't be rewritten in place: ${rewrite.text()}") },
        )
        rig.close()
    }

    /**
     * A Poll written with whole arrays before this fix still takes updates: the bridge refuses only
     * a write that leaves a surface with a binding it didn't have, so an older Poll isn't stuck.
     */
    @Test
    fun anOlderPollWithWholeArraysStillTakesUpdates() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        val browser = rig.browser()
        val meta = SurfaceMeta("Older poll", NOTIONS_CATALOG_ID, ada.bridge.self, kotlin.time.Instant.fromEpochMilliseconds(0))
        val older = Json.parseToJsonElement("""{"question": ["$QUESTION"], "options": $OPTIONS, "answers": {}}""") as JsonObject
        browser.mutate { it.openSurface(ReplicaId("web-iain"), "older", meta, listOf(poll()), older) }
        ada.client.state.first { "older" in it.openSurfaces() }

        val updated = ada.call(BeeTools.UPDATE, mapOf("surfaceId" to "older", "data" to Json.parseToJsonElement("""{"/answers/note": "kept"}""")))
        assertEquals(null, updated.isError, updated.text())
        rig.close()
    }

    private fun pollArgs(id: String, data: String = """{"answers": {}}""", append: String): Map<String, Any?> = mapOf(
        "surfaceId" to id,
        "title" to "Which cache?",
        "components" to JsonArray(listOf(poll())),
        "data" to Json.parseToJsonElement(data),
        "append" to Json.parseToJsonElement(append),
    )

    private fun poll(): JsonObject = buildJsonObject {
        put("id", "root")
        put("component", "Poll")
        listOf("question", "options", "answers").forEach { key -> put(key, buildJsonObject { put("path", "/$key") }) }
    }

    /** The dots of the append-only array at [pointer] in surface [id]'s data; empty if it isn't one. */
    private fun entries(state: BoardState, id: String, pointer: String): Set<Dot> {
        val doc = requireNotNull(state.surfaceDoc(id)) { "no surface $id" }
        val node = SurfaceFormat.segments(pointer).fold(doc[SurfaceFormat.DATA]) { at, key -> (at as? JsonNode.Object)?.map?.get(key) }
        return (node as? JsonNode.Array)?.rga?.causalDots().orEmpty()
    }

    /** The strings surface [id] shows at data key [key]. */
    private fun shown(state: BoardState, id: String, key: String): List<String> {
        val value: JsonElement? = requireNotNull(state.surfaceDoc(id)) { "no surface $id" }.toSurfaceView(id).data[key]
        return (value as? JsonArray).orEmpty().map { (it as JsonPrimitive).content }
    }

    private companion object {
        const val QUESTION = "Redis, Memcached or in-process?"
        const val OPTIONS = """["Redis", "Memcached", "In-process"]"""
    }
}
