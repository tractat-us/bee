@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.bee.mcp

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import us.tractat.bee.core.BoardState
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** `bee_surface_open`'s `replaces` (bee#273): a new surface closes the ones it supersedes as it opens. */
class SurfaceReplacesTest {
    /**
     * The new surface and the closes of the ones it replaces are one write. Every state the
     * writer's own replica passes through is collected, unconfined, so each write shows up as its
     * own state: two writes would leave one state with `v3` open beside `v1` or `v2`, and that
     * state is what a peer could be sent. The control is the same collector seeing `v1` and `v2`
     * open together before the replace.
     */
    @Test
    fun openWithReplacesClosesTheSupersededSurfacesInTheSameWrite() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        val browser = rig.browser()
        val seen = mutableListOf<BoardState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { ada.client.state.collect { seen += it } }
        ada.call(BeeTools.OPEN, openArgs("v1"))
        ada.call(BeeTools.OPEN, openArgs("v2"))

        val result = ada.call(BeeTools.OPEN, openArgs("v3") + ("replaces" to JsonArray(listOf(JsonPrimitive("v1"), JsonPrimitive("v2")))))
        val after = browser.state.first { "v3" in it.openSurfaces() }
        val overlapping = seen.filter { "v3" in it.openSurfaces() && (it.openSurfaces().toSet() intersect setOf("v1", "v2")).isNotEmpty() }
        assertAll(
            { assertEquals(null, result.isError, "open: ${result.text()}") },
            { assertTrue(seen.any { it.openSurfaces().containsAll(listOf("v1", "v2")) }, "control: the collector sees each write") },
            { assertEquals(emptyList(), overlapping.map { it.openSurfaces() }, "no state shows v3 beside a surface it replaces") },
            { assertEquals(listOf("v1", "v2"), result.closedIds(), "the result names what it closed") },
            { assertEquals(listOf("v3"), after.openSurfaces(), "the browser sees only the new surface open") },
            { assertTrue(after.surfaceDoc("v1") != null && after.surfaceDoc("v2") != null, "nothing was deleted") },
        )
        rig.close()
    }

    /**
     * An id in `replaces` that isn't on the board, or is closed already, is passed over: the open
     * still lands, the result leaves the id out, and a closed one keeps its first closing.
     */
    @Test
    fun replacesPassesOverAnUnknownOrClosedSurface() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        ada.call(BeeTools.OPEN, openArgs("old"))
        ada.call(BeeTools.OPEN, openArgs("live"))
        ada.call(BeeTools.CLOSE, mapOf("surfaceId" to "old"))
        val firstClose = ada.client.state.value.meta("old")!!.closedAt
        kotlinx.coroutines.delay(1.minutes)

        val names = listOf("ghost", "old", "live").map(::JsonPrimitive)
        val result = ada.call(BeeTools.OPEN, openArgs("new") + ("replaces" to JsonArray(names)))
        val after = ada.client.state.value
        assertAll(
            { assertEquals(null, result.isError, "open: ${result.text()}") },
            { assertEquals(listOf("live"), result.closedIds(), "only the open one is reported closed") },
            { assertEquals(listOf("new"), after.openSurfaces()) },
            { assertEquals(firstClose, after.meta("old")!!.closedAt, "the closed one keeps its first close") },
            { assertEquals(null, after.surfaceDoc("ghost"), "no surface is made for an unknown id") },
        )
        rig.close()
    }

    /** With no `replaces`, an open closes nothing and says so. */
    @Test
    fun openWithoutReplacesClosesNothing() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        ada.call(BeeTools.OPEN, openArgs("one"))

        val result = ada.call(BeeTools.OPEN, openArgs("two"))
        assertAll(
            { assertEquals(emptyList(), result.closedIds()) },
            { assertEquals(listOf("one", "two"), ada.client.state.value.openSurfaces()) },
        )
        rig.close()
    }

    /**
     * A surface can't replace itself: that is a redraw, so the open is refused, pointing at
     * `bee_surface_update`, and the board is unchanged, the other named surface still open. The
     * control is the same open without its own id, which lands.
     */
    @Test
    fun aSurfaceCannotReplaceItself() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        ada.call(BeeTools.OPEN, openArgs("draft"))

        val refused = ada.call(BeeTools.OPEN, openArgs("plan") + ("replaces" to JsonArray(listOf(JsonPrimitive("draft"), JsonPrimitive("plan")))))
        val unchanged = ada.client.state.value
        val control = ada.call(BeeTools.OPEN, openArgs("plan") + ("replaces" to JsonArray(listOf(JsonPrimitive("draft")))))
        assertAll(
            { assertTrue(refused.isError == true && BeeTools.UPDATE in refused.text(), "refused: ${refused.text()}") },
            { assertEquals(listOf("draft"), unchanged.openSurfaces(), "the refusal changed nothing") },
            { assertEquals(null, unchanged.surfaceDoc("plan"), "the refused surface wasn't opened") },
            { assertEquals(null, control.isError, "control: ${control.text()}") },
            { assertEquals(listOf("plan"), ada.client.state.value.openSurfaces(), "control: the replace lands") },
        )
        rig.close()
    }

    /** A `replaces` that isn't an array of strings is refused, naming the argument. */
    @Test
    fun replacesMustBeAnArrayOfStrings() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")

        val notArray = ada.call(BeeTools.OPEN, openArgs("a") + ("replaces" to "one"))
        val notStrings = ada.call(BeeTools.OPEN, openArgs("b") + ("replaces" to JsonArray(listOf(JsonPrimitive(1)))))
        assertAll(
            { assertTrue(notArray.isError == true && "replaces" in notArray.text(), "not an array: ${notArray.text()}") },
            { assertTrue(notStrings.isError == true && "replaces" in notStrings.text(), "not strings: ${notStrings.text()}") },
            { assertEquals(emptyList(), ada.client.state.value.openSurfaces(), "neither opened") },
        )
        rig.close()
    }

    private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolResult.closedIds(): List<String> =
        json().getValue("closed").jsonArray.map { it.jsonPrimitive.content }
}
