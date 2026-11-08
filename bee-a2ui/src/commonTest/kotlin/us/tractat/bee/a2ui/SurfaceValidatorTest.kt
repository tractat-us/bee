package us.tractat.bee.a2ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
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

    private fun view(vararg components: JsonObject) = SurfaceView(
        id = "s",
        catalogId = NOTIONS_CATALOG_ID,
        components = components.associateBy { (it["id"] as JsonPrimitive).content },
        data = JsonObject(emptyMap()),
        conflicts = emptyMap(),
    )

    private fun component(id: String, type: String, more: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
        buildJsonObject {
            put("id", id)
            put("component", type)
            more()
        }
}
