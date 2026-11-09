package us.tractat.bee.web

import kotlinx.browser.document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.EventInit
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import us.tractat.bee.a2ui.A2uiMessage
import us.tractat.bee.a2ui.NOTIONS_CATALOG_ID
import us.tractat.bee.a2ui.SurfaceValidator
import us.tractat.kuilt.test.assertAll
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Board's move menu (bee-notions/src/board.js): a tap, click or Enter on a card offers the other
 * columns, and choosing one is the same `drop` a drag dispatches. Rendered through the real
 * renderer, as [RendererSeamTest] does, with events dispatched on the card.
 */
class BoardMoveMenuTest {
    private val root: HTMLElement = (document.createElement("div") as HTMLElement).also { document.body!!.appendChild(it) }

    @AfterTest
    fun detach() {
        root.remove()
    }

    @Test
    fun menuOffersEveryColumnButTheCardsOwn() = onEventLoop {
        A2uiRenderer(root).render(board("menu"))
        val c1 = card("c1")
        c1.click()

        val moves = awaitMoves()
        assertAll(
            { assertEquals(listOf("doing", "done"), moves.map { it.getAttribute("data-move") }) },
            { assertEquals(listOf("Move to Doing", "Move to Done"), moves.map { it.textContent }) },
            { assertEquals("true", c1.getAttribute("aria-expanded")) },
        )
    }

    @Test
    fun choosingAColumnDispatchesDrop() = onEventLoop {
        val renderer = A2uiRenderer(root)
        val actions = renderer.actions { }
        renderer.render(board("choose"))
        card("c1").click()
        val doing = awaitMoves().single { it.getAttribute("data-move") == "doing" }
        awaitTrue("the menu arms") { !doing.disabled }
        doing.click()

        val action = withTimeout(UNTIL) { actions.first().action }
        assertAll(
            { assertEquals("drop", action.name) },
            { assertEquals("c1", action.context["card"]?.jsonPrimitive?.content) },
            { assertEquals("doing", action.context["column"]?.jsonPrimitive?.content) },
        )
        awaitTrue("the menu closes once a column is chosen") { root.querySelector(".move") == null }
    }

    @Test
    fun menuButtonsStartDisabled() = onEventLoop {
        // A double tap's second tap must not land on a button that just appeared under it.
        A2uiRenderer(root).render(board("armed"))
        card("c1").click()

        assertTrue(awaitMoves().all { it.disabled }, "the buttons are enabled the moment the menu opens")
    }

    @Test
    fun aDragOpensNoMenu() = onEventLoop {
        A2uiRenderer(root).render(board("drag"))
        val c1 = card("c1")
        c1.dispatchEvent(Event("dragstart", EventInit(bubbles = true)))
        c1.click()
        c1.dispatchEvent(Event("dragend", EventInit(bubbles = true)))
        c1.click()
        delay(100)

        assertAll(
            { assertNull(root.querySelector(".move"), "a click during or just after a drag opened the menu") },
            { assertEquals("false", c1.getAttribute("aria-expanded")) },
        )
    }

    @Test
    fun enterOpensTheMenuAndEscapeClosesIt() = onEventLoop {
        A2uiRenderer(root).render(board("keys"))
        val c1 = card("c1")
        assertEquals("0", c1.getAttribute("tabindex"))
        c1.focus()
        c1.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Enter", bubbles = true, cancelable = true)))
        val first = awaitMoves().first()
        awaitTrue("the first move takes focus once armed") { document.activeElement == first }

        first.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", bubbles = true, cancelable = true)))
        awaitTrue("Escape closes the menu") { root.querySelector(".move") == null }
        assertEquals(card("c1"), document.activeElement)
    }

    @Test
    fun aPressOutsideTheCardClosesTheMenu() = onEventLoop {
        A2uiRenderer(root).render(board("outside"))
        card("c1").click()
        awaitMoves()
        card("c2").dispatchEvent(Event("pointerdown", EventInit(bubbles = true, composed = true)))

        awaitTrue("a press on another card closes the menu") { root.querySelector(".move") == null }
    }

    private suspend fun card(id: String): HTMLElement = awaitElement { root.querySelector("bee-board [data-card=\"$id\"]") as HTMLElement? }

    private suspend fun awaitMoves(): List<HTMLButtonElement> {
        awaitTrue("the move menu opens") { root.querySelectorAll(".move button").length > 0 }
        val all = root.querySelectorAll(".move button")
        return (0 until all.length).map { all.item(it) as HTMLButtonElement }
    }

    /** A Board surface: To do, Doing and Done, with `c1` and `c2` in To do. */
    private fun board(id: String): List<A2uiMessage> {
        val columns = listOf("todo" to "To do", "doing" to "Doing", "done" to "Done")
        return listOf(
            A2uiMessage.CreateSurface(id, NOTIONS_CATALOG_ID),
            A2uiMessage.UpdateComponents(
                id,
                listOf(
                    buildJsonObject {
                        put("id", "root")
                        put("component", SurfaceValidator.BOARD)
                        putJsonObject("columns") { put("path", "/columns") }
                        putJsonObject("cards") { put("path", "/cards") }
                    },
                ),
            ),
            A2uiMessage.UpdateDataModel(
                id,
                "/columns",
                buildJsonObject {
                    putJsonObject("items") { columns.forEach { (column, title) -> putJsonObject(column) { put("title", title) } } }
                    put("order", JsonArray(columns.map { JsonPrimitive(it.first) }))
                },
            ),
            A2uiMessage.UpdateDataModel(
                id,
                "/cards",
                buildJsonObject {
                    putJsonObject("items") {
                        putJsonObject("c1") {
                            put("label", "Write the spec")
                            put("column", "todo")
                        }
                        putJsonObject("c2") {
                            put("label", "Review it")
                            put("column", "todo")
                        }
                    }
                    put("order", JsonArray(listOf(JsonPrimitive("c1"), JsonPrimitive("c2"))))
                },
            ),
        )
    }

    private companion object {
        const val UNTIL = 10_000L

        fun onEventLoop(body: suspend () -> Unit) = runTest { withContext(Dispatchers.Default) { body() } }

        suspend fun awaitTrue(what: String, check: () -> Boolean) {
            try {
                withTimeout(UNTIL) { while (!check()) delay(10) }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError("Timed out waiting until $what", e)
            }
        }

        suspend fun awaitElement(find: () -> HTMLElement?): HTMLElement {
            var found: HTMLElement? = null
            awaitTrue("the element renders") { find().also { found = it } != null }
            return found!!
        }
    }
}
