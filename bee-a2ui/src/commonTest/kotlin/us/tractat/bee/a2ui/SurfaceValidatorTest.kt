package us.tractat.bee.a2ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

class SurfaceValidatorTest {
    @Test
    fun missingRootIsAnError() {
        assertEquals(
            listOf("Surface s has no \"root\" component."),
            SurfaceValidator.errors(view(component("title", "Text"))),
        )
    }

    @Test
    fun danglingChildIsNamed() {
        val root = component("root", "Column") { put("children", JsonArray(listOf(JsonPrimitive("title"), JsonPrimitive("ghost")))) }
        val card = component("card", "Card") { put("child", "phantom") }
        assertEquals(
            listOf(
                "Component \"card\" names child \"phantom\", which no component has as its id.",
                "Component \"root\" names child \"ghost\", which no component has as its id.",
            ),
            SurfaceValidator.errors(view(root, card, component("title", "Text"))),
        )
    }

    @Test
    fun unknownTypeIsNamed() {
        assertEquals(
            listOf("Component \"root\" has unknown type \"Marquee\"."),
            SurfaceValidator.errors(view(component("root", "Marquee"))),
        )
    }

    @Test
    fun validSurfaceHasNoErrors() {
        val root = component("root", "Column") { put("children", JsonArray(listOf(JsonPrimitive("tabs")))) }
        val tabs = component("tabs", "Tabs") {
            put("tabs", JsonArray(listOf(buildJsonObject { put("title", "One"); put("child", "go") })))
        }
        val go = component("go", "Button") { put("child", "label") }
        assertEquals(emptyList(), SurfaceValidator.errors(view(root, tabs, go, component("label", "Text"))))
    }

    /** `Sketch` is in the Notions catalog (Task 8), and its `html` is required. */
    @Test
    fun sketchIsInTheCatalogAndNeedsItsHtml() {
        val sketch = component("root", "Sketch") { put("html", "<p>hi</p>") }
        val bare = component("root", "Sketch")
        assertEquals(
            listOf(emptyList(), listOf("Component \"root\" is a Sketch with no html.")),
            listOf(SurfaceValidator.errors(view(sketch)), SurfaceValidator.errors(view(bare))),
        )
    }

    @Test
    fun pollAndBoardWithTheirBindingsAreValid() {
        val root = component("root", "Column") { put("children", JsonArray(listOf(JsonPrimitive("poll"), JsonPrimitive("board")))) }
        val poll = component("poll", SurfaceValidator.POLL) {
            putJsonObject("question") { put("path", "/question") }
            putJsonObject("options") { put("path", "/options") }
            putJsonObject("answers") { put("path", "/answers") }
        }
        val board = component("board", SurfaceValidator.BOARD) {
            putJsonObject("columns") { put("path", "/columns") }
            putJsonObject("cards") { put("path", "/cards") }
        }
        assertEquals(emptyList(), SurfaceValidator.errors(view(root, poll, board)))
    }

    @Test
    fun aPollOrBoardPropertyThatIsNoDataBindingIsNamed() {
        val poll = component("root", SurfaceValidator.POLL) {
            putJsonObject("question") { put("path", "/question") }
            put("options", JsonArray(listOf(JsonPrimitive("lunch"))))
        }
        val board = component("board", SurfaceValidator.BOARD) {
            putJsonObject("columns") { put("path", "columns") }
        }
        assertEquals(
            listOf(
                "Component \"board\" is a Board whose cards isn't a data binding ({\"path\": \"/…\"}).",
                "Component \"board\" is a Board whose columns isn't a data binding ({\"path\": \"/…\"}).",
                "Component \"root\" is a Poll whose answers isn't a data binding ({\"path\": \"/…\"}).",
                "Component \"root\" is a Poll whose options isn't a data binding ({\"path\": \"/…\"}).",
            ),
            SurfaceValidator.errors(view(poll, board)),
        )
    }

    /** A Board card may link its label to an http: or https: URL and carry a detail line (bee#197). */
    @Test
    fun aBoardCardWithAnHttpHrefAndADetailIsValid() {
        val linked = boardWith(
            "c1" to card { put("href", "https://github.com/tractat-us/bee/issues/197"); put("detail", "Cards link out.") },
            "c2" to card { put("href", "HTTP://example.test/plan") },
            "c3" to card {},
            "c4" to card { put("href", "") },
        )
        assertEquals(emptyList(), SurfaceValidator.errors(linked))
    }

    /** The page opens a card's href, so any scheme but http: and https: is refused, as is a detail that isn't text. */
    @Test
    fun aBoardCardWithAnotherSchemeOrANonStringDetailIsNamed() {
        val bad = boardWith(
            "c1" to card { put("href", "javascript:alert(1)") },
            "c2" to card { put("href", " https://example.test") },
            "c3" to card { put("href", "data:text/html,hi") },
            "c4" to card { put("href", "https://") },
            "c5" to card { put("href", 7) },
            "c6" to card { put("detail", JsonArray(listOf(JsonPrimitive("x")))) },
        )
        assertEquals(
            listOf(
                "Component \"root\" is a Board whose card \"c1\" has href \"javascript:alert(1)\", which isn't an http: or https: URL.",
                "Component \"root\" is a Board whose card \"c2\" has href \" https://example.test\", which isn't an http: or https: URL.",
                "Component \"root\" is a Board whose card \"c3\" has href \"data:text/html,hi\", which isn't an http: or https: URL.",
                "Component \"root\" is a Board whose card \"c4\" has href \"https://\", which isn't an http: or https: URL.",
                "Component \"root\" is a Board whose card \"c5\" has href 7, which isn't an http: or https: URL.",
                "Component \"root\" is a Board whose card \"c6\" has detail [\"x\"], which isn't a string.",
            ),
            SurfaceValidator.errors(bad),
        )
    }

    /**
     * A Poll's options and question must be appended (bee#81). Data there is named, whether a
     * whole array or a scalar; an appended path or an empty one is fine. Rendering doesn't check
     * it, so an older Poll still shows.
     */
    @Test
    fun appendErrorsNameAPollBindingWrittenAsData() {
        val poll = component("root", SurfaceValidator.POLL) {
            listOf("question", "options", "answers").forEach { key -> putJsonObject(key) { put("path", "/$key") } }
        }
        val data = buildJsonObject {
            put("question", "Which cache?")
            put("options", JsonArray(listOf(JsonPrimitive("Redis"))))
            putJsonObject("answers") {}
        }
        val asData = view(poll).copy(data = data)
        assertAll(
            {
                assertEquals(
                    listOf(
                        "Component \"root\" is a Poll whose options at /options isn't appended: put it in append, not data, " +
                            "so a vote's basis can name each entry.",
                        "Component \"root\" is a Poll whose question at /question isn't appended: put it in append, not data, " +
                            "so a vote's basis can name each entry.",
                    ),
                    SurfaceValidator.appendErrors(asData),
                )
            },
            { assertEquals(emptyList(), SurfaceValidator.appendErrors(asData.copy(appended = setOf("/options", "/question")))) },
            { assertEquals(emptyList(), SurfaceValidator.appendErrors(view(poll)), "nothing bound yet is fine") },
            { assertEquals(emptyList(), SurfaceValidator.errors(asData), "rendering still passes it") },
        )
    }

    /** A Chat binds its messages, and they're appended, never written as data (bee#214). */
    @Test
    fun aChatBindsMessagesThatAreAppended() {
        val chat = component("root", SurfaceValidator.CHAT) { putJsonObject("messages") { put("path", "/messages") } }
        val asData = view(chat).copy(data = buildJsonObject { put("messages", JsonArray(listOf(message("m1", "hi")))) })
        assertAll(
            { assertEquals(emptyList(), SurfaceValidator.errors(view(chat))) },
            {
                assertEquals(
                    listOf("Component \"root\" is a Chat whose messages isn't a data binding ({\"path\": \"/…\"})."),
                    SurfaceValidator.errors(view(component("root", SurfaceValidator.CHAT))),
                )
            },
            {
                assertEquals(
                    listOf(
                        "Component \"root\" is a Chat whose messages at /messages isn't appended: put it in append, not data, " +
                            "so a vote's basis can name each entry.",
                    ),
                    SurfaceValidator.appendErrors(asData),
                )
            },
            { assertEquals(emptyList(), SurfaceValidator.appendErrors(asData.copy(appended = setOf("/messages")))) },
        )
    }

    /**
     * A writer may append only a whole message: `{id, author, text, at}`, each a string, its text
     * neither blank nor past the cap, its id held by no earlier message. Rendering doesn't check it,
     * so one bad message never hides a whole Chat; the page shows what it can.
     */
    @Test
    fun appendErrorsNameAMalformedChatMessage() {
        val chat = component("root", SurfaceValidator.CHAT) { putJsonObject("messages") { put("path", "/messages") } }
        val messages = listOf(
            message("m1", "fine"),
            message("m2", " "),
            message("m3", "x".repeat(NotionActions.MAX_MESSAGE_LENGTH + 1)),
            buildJsonObject { put("id", "m4"); put("text", "who?"); put("at", "2026-11-09T12:00:00Z") },
            JsonPrimitive("just text"),
            message("m6", "x".repeat(NotionActions.MAX_MESSAGE_LENGTH)),
            message("m1", "again"),
        )
        val withMessages = view(chat).copy(data = buildJsonObject { put("messages", JsonArray(messages)) }, appended = setOf("/messages"))
        assertAll(
            {
                assertEquals(
                    listOf(
                        "Component \"root\" is a Chat whose message at /messages/1 is empty.",
                        "Component \"root\" is a Chat whose message at /messages/2 is ${NotionActions.MAX_MESSAGE_LENGTH + 1} characters, " +
                            "past the ${NotionActions.MAX_MESSAGE_LENGTH} a message holds.",
                        "Component \"root\" is a Chat whose message at /messages/3 isn't {id, author, text, at}, each a string.",
                        "Component \"root\" is a Chat whose message at /messages/4 isn't {id, author, text, at}, each a string.",
                        "Component \"root\" is a Chat whose message at /messages/6 has id \"m1\", which /messages/0 holds.",
                    ),
                    SurfaceValidator.appendErrors(withMessages),
                )
            },
            { assertEquals(emptyList(), SurfaceValidator.errors(withMessages), "rendering still passes it") },
        )
    }

    private fun message(id: String, text: String) = buildJsonObject {
        put("id", id)
        put("author", "human:alex")
        put("text", text)
        put("at", "2026-11-09T12:00:00Z")
    }

    private fun view(vararg components: JsonObject) = SurfaceView(
        id = "s",
        catalogId = NOTIONS_CATALOG_ID,
        components = components.associateBy { (it["id"] as JsonPrimitive).content },
        data = JsonObject(emptyMap()),
        conflicts = emptyMap(),
        appended = emptySet(),
    )

    /** A Board as the root, its cards bound at `/cards` and held there, every card in column `todo`. */
    private fun boardWith(vararg cards: Pair<String, JsonObject>): SurfaceView {
        val board = component("root", SurfaceValidator.BOARD) {
            putJsonObject("columns") { put("path", "/columns") }
            putJsonObject("cards") { put("path", "/cards") }
        }
        val data = buildJsonObject {
            putJsonObject("cards") {
                put("items", JsonObject(cards.toMap()))
                put("order", JsonArray(cards.map { JsonPrimitive(it.first) }))
            }
        }
        return view(board).copy(data = data)
    }

    private fun card(more: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("label", "Spec")
        put("column", "todo")
        more()
    }

    private fun component(id: String, type: String, more: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
        buildJsonObject {
            put("id", id)
            put("component", type)
            more()
        }
}
