@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.bee.web

import kotlinx.browser.document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.w3c.dom.EventInit
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import us.tractat.bee.a2ui.NOTIONS_CATALOG_ID
import us.tractat.bee.a2ui.diff
import us.tractat.bee.a2ui.toSurfaceView
import us.tractat.bee.core.BoardClient
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.SurfaceMeta
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.test.assertAll
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * The dirty half of [LiveInputs]' hold, in virtual time: a path stays held while its debounced
 * write is outstanding, even after its field loses focus. The spike-2c Playwright suite couldn't
 * pin this, because a real browser can't land a remote change inside the window on demand.
 *
 * Each test drives the real renderer: a `TextField` bound to `/name`, typed into and focused
 * through DOM events, which reach [LiveInputs] through bee-notions' hooks. [LiveInputs] runs on
 * the test's virtual-time scope, so the window opens and closes only on [advanceTimeBy]. Nothing
 * re-renders here (no `BoardDriver`), so what's held, what's written and [LiveInputs.releases]
 * are read directly. The client stays offline: a write lands in its held board.
 */
class LiveInputsTest {
    private val root: HTMLElement = (document.createElement("div") as HTMLElement).also { document.body!!.appendChild(it) }

    @AfterTest
    fun detach() {
        root.remove()
    }

    @Test
    fun anEditIsHeldUntilTheWindowCloses() = runTest {
        val field = mount()
        field.focus()
        field.type("alpha")
        field.blur()
        advanceTimeBy(DEBOUNCE - 1.milliseconds)
        runCurrent()

        assertAll(
            { assertEquals(mapOf("/name" to JsonPrimitive("alpha")), field.held()) },
            { assertEquals(JsonPrimitive("draft"), field.written()) },
            { assertEquals(0, field.releases()) },
            { assertEquals("1", root.getAttribute("data-pending")) },
        )
    }

    @Test
    fun theHoldReleasesOnceTheWindowCloses() = runTest {
        val field = mount()
        field.focus()
        field.type("alpha")
        field.blur()
        advanceTimeBy(DEBOUNCE)
        runCurrent()

        assertAll(
            { assertEquals(emptyMap(), field.held()) },
            { assertEquals(JsonPrimitive("alpha"), field.written()) },
            { assertEquals(1, field.releases()) },
            { assertEquals(null, root.getAttribute("data-pending")) },
        )
    }

    @Test
    fun aFurtherKeystrokeExtendsTheHold() = runTest {
        val field = mount()
        field.focus()
        field.type("a")
        advanceTimeBy(100.milliseconds)
        field.type("ab")
        field.blur()
        // Past the first keystroke's window, inside the second's.
        advanceTimeBy(100.milliseconds)
        runCurrent()
        val extended = field.held() to field.written()
        advanceTimeBy(DEBOUNCE - 100.milliseconds)
        runCurrent()

        assertAll(
            { assertEquals(mapOf("/name" to JsonPrimitive("ab")) to JsonPrimitive("draft"), extended) },
            { assertEquals(emptyMap(), field.held()) },
            { assertEquals(JsonPrimitive("ab"), field.written()) },
            { assertEquals(1, field.releases()) },
        )
    }

    @Test
    fun blurReleasesTheHold() = runTest {
        val field = mount()
        field.focus()
        field.type("alpha")
        advanceTimeBy(DEBOUNCE)
        runCurrent()
        // Written, but the field still has focus, so the path stays held.
        val focused = field.held() to field.releases()
        field.blur()

        assertAll(
            { assertEquals(mapOf("/name" to JsonPrimitive("alpha")) to 0, focused) },
            { assertEquals(JsonPrimitive("alpha"), field.written()) },
            { assertEquals(emptyMap(), field.held()) },
            { assertEquals(1, field.releases()) },
        )
    }

    /** One surface's `TextField`, rendered and tracked, with [LiveInputs] on the test's virtual time. */
    private class Field(
        private val inputs: LiveInputs,
        private val client: BoardClient,
        private val input: HTMLInputElement,
    ) {
        fun focus() = input.dispatchEvent(Event("focusin", EventInit(bubbles = true)))

        fun blur() = input.dispatchEvent(Event("focusout", EventInit(bubbles = true)))

        /** What a keystroke does: the input's whole value changes, and it fires `input`. */
        fun type(value: String) {
            input.value = value
            input.dispatchEvent(Event("input", EventInit(bubbles = true)))
        }

        fun held(): Map<String, JsonElement?> = inputs.held(SURFACE)

        fun written(): JsonElement? = client.state.value.surfaceDoc(SURFACE)!!.toSurfaceView(SURFACE).data["name"]

        fun releases(): Int = inputs.releases.value
    }

    private suspend fun TestScope.mount(): Field {
        val board = BoardState.EMPTY.piece {
            it.openSurface(AUTHOR, SURFACE, META, listOf(TEXT_FIELD), buildJsonObject { put("name", "draft") })
        }
        val client = BoardClient(
            author = AUTHOR,
            loom = Offline,
            rendezvous = Rendezvous.New(Pattern("board")),
            scope = backgroundScope,
            clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) },
            initial = board,
        )
        val inputs = LiveInputs(root, client, AUTHOR, backgroundScope, { error("Unexpected problem: $it") }, DEBOUNCE)
        val view = board.surfaceDoc(SURFACE)!!.toSurfaceView(SURFACE)
        inputs.whileRendering { A2uiRenderer(root).render(diff(null, view)) }
        inputs.track(view)
        // Lit renders on the real event loop; virtual time doesn't move it.
        val input = withContext(Dispatchers.Default) {
            withTimeout(UNTIL) {
                var found: HTMLInputElement? = null
                while (found == null) {
                    found = deepQuery(root, "a2ui-basic-textfield input") as HTMLInputElement?
                    if (found == null) delay(10)
                }
                found
            }
        }
        return Field(inputs, client, input)
    }

    /** The client never connects in these tests; a dial would be a bug in the rig. */
    private object Offline : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam = error("These tests never dial.")
    }

    private companion object {
        val DEBOUNCE = 150.milliseconds
        const val UNTIL = 10_000L
        const val SURFACE = "lunch"
        val AUTHOR = ReplicaId("web-a")
        val META = SurfaceMeta("Lunch", NOTIONS_CATALOG_ID, PeerIdentity.human("iain"), Instant.parse("2026-11-07T12:00:00Z"))
        val TEXT_FIELD = buildJsonObject {
            put("id", "root")
            put("component", "TextField")
            put("label", "Name")
            putJsonObject("value") { put("path", "/name") }
        }
    }
}
