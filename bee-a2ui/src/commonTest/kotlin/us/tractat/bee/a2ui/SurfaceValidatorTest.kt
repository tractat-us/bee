package us.tractat.bee.a2ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
