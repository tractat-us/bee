package us.tractat.bee.a2ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

class A2uiBindingsTest {
    @Test
    fun aV091BindingTakesTheAtPrefix() {
        assertAll(
            {
                assertEquals(
                    component("""{"id":"t","component":"Text","text":{"@path":"/note"}}"""),
                    A2uiBindings.upgrade(component("""{"id":"t","component":"Text","text":{"path":"/note"}}""")),
                )
            },
            {
                // A row of a list template binds relative to its item.
                assertEquals(
                    component("""{"id":"t","component":"Text","text":{"@path":"name"}}"""),
                    A2uiBindings.upgrade(component("""{"id":"t","component":"Text","text":{"path":"name"}}""")),
                )
            },
        )
    }

    @Test
    fun aBindingInsideAnActionContextIsUpgraded() {
        assertEquals(
            component("""{"id":"b","component":"Button","child":"l","action":{"event":{"name":"send","context":{"text":{"@path":"/answer"}}}}}"""),
            A2uiBindings.upgrade(
                component("""{"id":"b","component":"Button","child":"l","action":{"event":{"name":"send","context":{"text":{"path":"/answer"}}}}}"""),
            ),
        )
    }

    @Test
    fun aV091FunctionCallTakesTheAtPrefixAndLosesItsReturnType() {
        // v1.0 moved returnType into the catalog's function definition; an argument's binding is upgraded too.
        assertEquals(
            component("""{"id":"t","component":"Text","text":{"@call":"formatString","args":{"value":{"@path":"/n"}}}}"""),
            A2uiBindings.upgrade(
                component(
                    """{"id":"t","component":"Text","text":{"call":"formatString","args":{"value":{"path":"/n"}},"returnType":"string"}}""",
                ),
            ),
        )
    }

    @Test
    fun whatV10SpellsTheSameIsLeftAlone() {
        val template = component("""{"id":"r","component":"Column","children":{"componentId":"row","path":"/items"}}""")
        val current = component("""{"id":"t","component":"Text","text":{"@path":"/note"}}""")
        val literal = component("""{"id":"b","component":"Button","child":"l","action":{"event":{"name":"go","context":{"at":{"path":"/x","line":3}}}}}""")
        assertAll(
            { assertEquals(template, A2uiBindings.upgrade(template), "a ChildList template keeps its path") },
            { assertEquals(current, A2uiBindings.upgrade(current), "a v1.0 binding stays as it is") },
            { assertEquals(literal, A2uiBindings.upgrade(literal), "an object with more than path was never a binding") },
        )
    }

    @Test
    fun pathOfReadsOnlyTheV10Spelling() {
        assertAll(
            { assertEquals("/note", A2uiBindings.pathOf(A2uiBindings.binding("/note"))) },
            { assertEquals(null, A2uiBindings.pathOf(component("""{"path":"/note"}"""))) },
            { assertEquals(null, A2uiBindings.pathOf(null)) },
        )
    }

    private fun component(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
}
