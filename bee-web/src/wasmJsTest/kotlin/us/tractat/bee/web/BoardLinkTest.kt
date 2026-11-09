package us.tractat.bee.web

import kotlinx.browser.document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.w3c.dom.HTMLElement
import us.tractat.bee.a2ui.A2uiMessage
import us.tractat.bee.a2ui.NOTIONS_CATALOG_ID
import us.tractat.bee.a2ui.SurfaceValidator
import us.tractat.kuilt.test.assertAll
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

private fun pwned(): Boolean = js("typeof window.pwned !== 'undefined'")

/**
 * A Board card's `href` (bee-notions/src/board.js). The renderer draws a link only for an http: or
 * https: URL, whatever the data holds: BoardDriver validates first, but `cardHref` is the check on
 * data that reaches the renderer any other way. Rendered straight through the renderer, past the
 * driver, as [BoardMoveMenuTest] does.
 */
class BoardLinkTest {
    private val root: HTMLElement = (document.createElement("div") as HTMLElement).also { document.body!!.appendChild(it) }

    @AfterTest
    fun detach() {
        root.remove()
    }

    @Test
    fun aJavascriptHrefDrawsNoLinkAndClickingTheLabelRunsNothing() = onEventLoop {
        A2uiRenderer(root).render(board("link"))
        val c1 = awaitCard("c1")
        val c2 = awaitCard("c2")
        val label = c1.querySelector(".label") as HTMLElement
        label.click()

        assertAll(
            { assertEquals(0, c1.querySelectorAll("a").length, "a javascript: href draws no link") },
            { assertEquals(1, c2.querySelectorAll("a").length, "an https: href draws one") },
            { assertEquals("example.test", c2.querySelector("a")?.getAttribute("title"), "the link's title names its host") },
            { assertFalse(pwned(), "clicking the label ran nothing") },
        )
    }

    private suspend fun awaitCard(id: String): HTMLElement {
        var found: HTMLElement? = null
        try {
            withTimeout(UNTIL) {
                while (found == null) {
                    found = root.querySelector("bee-board [data-card=\"$id\"]") as HTMLElement?
                    if (found == null) delay(10)
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for card $id to render", e)
        }
        return found!!
    }

    /** A Board surface whose `c1` carries a `javascript:` href, in mixed case, and `c2` an https: one. */
    private fun board(id: String): List<A2uiMessage> = listOf(
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
                putJsonObject("items") { putJsonObject("todo") { put("title", "To do") } }
                put("order", JsonArray(listOf(JsonPrimitive("todo"))))
            },
        ),
        A2uiMessage.UpdateDataModel(
            id,
            "/cards",
            buildJsonObject {
                putJsonObject("items") {
                    putJsonObject("c1") {
                        put("label", "Sneaky")
                        put("column", "todo")
                        put("href", "JaVaScRiPt:window.pwned=1")
                    }
                    putJsonObject("c2") {
                        put("label", "Honest")
                        put("column", "todo")
                        put("href", "https://example.test/plan")
                    }
                }
                put("order", JsonArray(listOf(JsonPrimitive("c1"), JsonPrimitive("c2"))))
            },
        ),
    )

    private companion object {
        const val UNTIL = 10_000L

        fun onEventLoop(body: suspend () -> Unit) = runTest { withContext(Dispatchers.Default) { body() } }
    }
}
