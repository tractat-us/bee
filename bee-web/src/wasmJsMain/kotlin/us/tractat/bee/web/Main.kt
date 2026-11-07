package us.tractat.bee.web

import kotlinx.browser.document
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import us.tractat.bee.a2ui.A2uiMessage
import us.tractat.bee.a2ui.NOTIONS_CATALOG_ID

/**
 * Spike 1's page: Kotlin draws one surface through the stock renderer and counts presses.
 * Later tasks replace this with the board driver.
 */
fun main() {
    val root = document.getElementById("board") ?: error("index.html has no #board element")
    val log = document.getElementById("actions") ?: error("index.html has no #actions element")
    val renderer = A2uiRenderer(root)
    renderer.render(
        listOf(
            A2uiMessage.CreateSurface("spike-1", NOTIONS_CATALOG_ID),
            A2uiMessage.UpdateComponents(
                "spike-1",
                listOf(
                    buildJsonObject {
                        put("id", "root")
                        put("component", "Column")
                        put("children", JsonArray(listOf(JsonPrimitive("hello"), JsonPrimitive("press-button"))))
                    },
                    buildJsonObject {
                        put("id", "hello")
                        put("component", "Text")
                        put("text", "Hello from Kotlin")
                    },
                    buildJsonObject {
                        put("id", "press-label")
                        put("component", "Text")
                        put("text", "Press")
                    },
                    buildJsonObject {
                        put("id", "press-button")
                        put("component", "Button")
                        put("child", "press-label")
                        putJsonObject("action") { putJsonObject("event") { put("name", "press") } }
                    },
                ),
            ),
        ),
    )
    MainScope().launch {
        var presses = 0
        renderer.actions.collect { action ->
            presses += 1
            log.textContent = "Kotlin received ${action.name} from ${action.sourceComponentId} ($presses)"
        }
    }
}
