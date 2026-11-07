package us.tractat.bee.web

import kotlinx.browser.document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node
import org.w3c.dom.ParentNode
import org.w3c.dom.ShadowRoot
import us.tractat.bee.a2ui.A2uiJson
import us.tractat.bee.a2ui.A2uiMessage
import us.tractat.bee.a2ui.NOTIONS_CATALOG_ID
import us.tractat.kuilt.test.assertAll
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Kotlin/wasm ↔ Lit seam (spike 1): Kotlin hands the stock A2UI renderer JSON, the
 * renderer draws real DOM, and a click comes back to Kotlin as an [us.tractat.bee.a2ui.A2uiAction].
 *
 * Lit renders asynchronously, so each check polls the DOM on the real event loop
 * ([Dispatchers.Default] is the browser's), bounded by [UNTIL].
 */
class RendererSeamTest {
    private val root: HTMLElement = (document.createElement("div") as HTMLElement).also { document.body!!.appendChild(it) }

    @AfterTest
    fun detach() {
        root.remove()
    }

    @Test
    fun clickReachesKotlinAsAnAction() = onEventLoop {
        val renderer = A2uiRenderer(root)
        renderer.render(helloSurface("spike-1"))

        val button = awaitElement { deepQuery(root, "button") }
        button.click()
        val action = withTimeout(UNTIL) { renderer.actions.first() }

        assertAll(
            { assertEquals("press", action.name) },
            { assertEquals("spike-1", action.surfaceId) },
            { assertEquals("press-button", action.sourceComponentId) },
        )
    }

    @Test
    fun textRendersFromKotlin() = onEventLoop {
        A2uiRenderer(root).render(helloSurface("spike-1"))

        awaitTrue("the Text renders") { deepText(root).contains("Hello from Kotlin") }
    }

    @Test
    fun createOnAnExistingIdIsAnIntegrityError() = onEventLoop {
        // What the stock processor reports when a surface id is created twice. A2uiRenderer
        // never sends this: it turns a reopen into delete + create (reopenAfterDeleteRendersAgain).
        val host = Notions.createRendererHost(root)
        val create = A2uiJson.encode(listOf(A2uiMessage.CreateSurface("twice", NOTIONS_CATALOG_ID)))
        val first = host.process(create)
        val second = Json.parseToJsonElement(host.process(create)).jsonArray.map { it.jsonObject }

        assertAll(
            { assertEquals("[]", first) },
            { assertEquals(1, second.size, "errors: $second") },
            { assertEquals("INTEGRITY_ERROR", second.single().code(), "errors: $second") },
        )
    }

    @Test
    fun reopenAfterDeleteRendersAgain() = onEventLoop {
        val renderer = A2uiRenderer(root)
        renderer.render(helloSurface("again"))
        awaitTrue("the first open renders") { deepText(root).contains("Hello from Kotlin") }

        renderer.render(listOf(A2uiMessage.DeleteSurface("again")))
        awaitTrue("the delete clears it") { !deepText(root).contains("Hello from Kotlin") }

        renderer.render(helloSurface("again", text = "Hello again"))
        awaitTrue("the reopen renders") { deepText(root).contains("Hello again") }

        assertEquals(1, root.querySelectorAll("a2ui-surface").length)
    }

    @Test
    fun reopenWithoutDeleteReplacesTheSurface() = onEventLoop {
        val renderer = A2uiRenderer(root)
        renderer.render(helloSurface("open"))
        awaitTrue("the first open renders") { deepText(root).contains("Hello from Kotlin") }

        renderer.render(helloSurface("open", text = "Opened twice"))
        awaitTrue("the second open renders") { deepText(root).contains("Opened twice") }

        assertAll(
            { assertEquals(1, root.querySelectorAll("a2ui-surface").length) },
            { assertTrue(!deepText(root).contains("Hello from Kotlin")) },
        )
    }

    @Test
    fun dataModelDeleteRemovesTheValue() = onEventLoop {
        val renderer = A2uiRenderer(root)
        val bound = buildJsonObject {
            put("id", "root")
            put("component", "Text")
            putJsonObject("text") { put("path", "/greeting") }
        }
        renderer.render(
            listOf(
                A2uiMessage.CreateSurface("data", NOTIONS_CATALOG_ID),
                A2uiMessage.UpdateComponents("data", listOf(bound)),
                A2uiMessage.UpdateDataModel("data", "/greeting", JsonPrimitive("bound value")),
            ),
        )
        awaitTrue("the bound value renders") { deepText(root).contains("bound value") }

        renderer.render(listOf(A2uiMessage.UpdateDataModel("data", "/greeting", null)))
        awaitTrue("the delete clears it") { !deepText(root).contains("bound value") }
        // The value went, not the component bound to it.
        assertNotNull(deepQuery(root, "a2ui-basic-text"), "the Text element is gone")
    }

    @Test
    fun rendererErrorsThrow() = onEventLoop {
        // An update for a surface that was never created: the processor reports it.
        val orphan = A2uiMessage.UpdateDataModel("never-created", "/n", JsonPrimitive(1))
        val thrown = assertFailsWith<A2uiRenderException> { A2uiRenderer(root).render(listOf(orphan)) }

        assertEquals(1, thrown.errors.size, "errors: ${thrown.errors}")
    }

    private fun helloSurface(id: String, text: String = "Hello from Kotlin"): List<A2uiMessage> = listOf(
        A2uiMessage.CreateSurface(id, NOTIONS_CATALOG_ID),
        A2uiMessage.UpdateComponents(
            id,
            listOf(
                component("root", "Column") { put("children", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("hello"), JsonPrimitive("press-button")))) },
                component("hello", "Text") { put("text", text) },
                component("press-label", "Text") { put("text", "Press") },
                component("press-button", "Button") {
                    put("child", "press-label")
                    putJsonObject("action") { putJsonObject("event") { put("name", "press") } }
                },
            ),
        ),
    )

    private fun component(id: String, type: String, props: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject {
            put("id", id)
            put("component", type)
            props()
        }

    /** A RendererErrorMessage: `{"error": {"code": …, "message": …, "surfaceId": …}}`. */
    private fun JsonObject.code(): String = getValue("error").jsonObject.getValue("code").jsonPrimitive.content

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

/** The first element matching [selector] under [scope], looking inside shadow roots too. */
internal fun deepQuery(scope: ParentNode, selector: String): HTMLElement? {
    scope.querySelector(selector)?.let { return it as HTMLElement }
    return shadowRoots(scope).firstNotNullOfOrNull { deepQuery(it, selector) }
}

/** The text under [scope], including the text inside shadow roots. */
internal fun deepText(scope: ParentNode): String =
    ((scope as Node).textContent ?: "") + shadowRoots(scope).joinToString("") { deepText(it) }

private fun shadowRoots(scope: ParentNode): List<ShadowRoot> {
    val all = scope.querySelectorAll("*")
    return (0 until all.length).mapNotNull { (all.item(it) as Element).shadowRoot }
}
