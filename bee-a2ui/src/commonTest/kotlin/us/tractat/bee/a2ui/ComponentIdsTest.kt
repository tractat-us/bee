package us.tractat.bee.a2ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ComponentIdsTest {
    /**
     * Every v1.0 id bee renders is ASCII, so this stricter pattern stands in for UAX #31's
     * `^[\p{XID_Start}_][\p{XID_Continue}]*$`, which not every Kotlin target's Regex can read.
     */
    private val identifier = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    // Weighted to the characters the escape turns on: its own `_`, the prefix's letters, digits.
    private val alphabet = "_-_-be0129aZz .é/\u0000😀\uDFFF~"

    private val random = Random(230)

    private val samples: List<String> = buildList {
        addAll(listOf("", "_", "-", "0", "9lives", "root", "admit-button", "admit_button", "admit_2d_button"))
        addAll(listOf("_bee", "_bee_", "_bee_admit_2d_button", "_bee__2d_", "a--b", "a_-b", "a-_b", "é", "😀"))
        repeat(2_000) { add(String(CharArray(random.nextInt(0, 9)) { alphabet[random.nextInt(alphabet.length)] })) }
    }

    @Test
    fun aPlainIdCrossesAsItself() {
        val plain = listOf("root", "admit_button", "opt_1_label", "_", "_bee", "A9", "x__y")
        assertAll(*plain.map { id -> { assertEquals(id, ComponentIds.rendered(id), id) } }.toTypedArray())
    }

    @Test
    fun anIdThatIsNotAnIdentifierCrossesEscaped() {
        assertAll(
            { assertEquals("_bee_admit_2d_button", ComponentIds.rendered("admit-button")) },
            { assertEquals("_bee_opt_2d_1_2d_label", ComponentIds.rendered("opt-1-label")) },
            { assertEquals("_bee_9lives", ComponentIds.rendered("9lives"), "a leading digit") },
            { assertEquals("_bee_", ComponentIds.rendered(""), "the empty id") },
            { assertEquals("_bee__e9_", ComponentIds.rendered("é"), "a non-ASCII identifier is escaped too") },
            { assertEquals("_bee__5f_bee_5f_x", ComponentIds.rendered("_bee_x"), "a plain id under the prefix") },
        )
    }

    @Test
    fun everyIdRendersAsAnIdentifier() {
        val refused = samples.filterNot { identifier.matches(ComponentIds.rendered(it)) }
        assertEquals(emptyList(), refused)
    }

    @Test
    fun storedInvertsRendered() {
        val lost = samples.filter { ComponentIds.stored(ComponentIds.rendered(it)) != it }
        assertEquals(emptyList(), lost)
    }

    @Test
    fun noTwoIdsRenderAlike() {
        val distinct = samples.toSet()
        val byRendered = distinct.groupBy(ComponentIds::rendered).filterValues { it.size > 1 }
        assertAll(
            { assertEquals(emptyMap(), byRendered) },
            // The collisions a looser escape would make: `-` as `_`, or as the hex an agent might write.
            {
                assertEquals(
                    4,
                    setOf("admit-button", "admit_button", "admit_2d_button", "_bee_admit_2d_button").map(ComponentIds::rendered).toSet().size,
                )
            },
        )
    }

    @Test
    fun anIdBeeNeverRendersIsRefused() {
        val never = listOf("_bee_a_2d", "_bee__2D_", "_bee__02d_", "_bee__-2d_", "_bee___", "_bee__zz_", "_bee__110000_")
        assertAll(*never.map { id -> { assertFailsWith<IllegalArgumentException>(id) { ComponentIds.stored(id) }; Unit } }.toTypedArray())
    }

    @Test
    fun aComponentRendersItsIdAndEveryIdItNames() {
        val stored = component(
            """
            {"id":"main-col","component":"Column","children":["a-1","b_2"],"child":"c-3","trigger":"t-4","content":"m-5",
             "tabs":[{"title":"One","child":"tab-1"},{"title":"Two","child":"tab_2"}],"text":"not-an-id","action":{"event":{"name":"go-on"}}}
            """,
        )
        val expected = component(
            """
            {"id":"_bee_main_2d_col","component":"Column","children":["_bee_a_2d_1","b_2"],"child":"_bee_c_2d_3",
             "trigger":"_bee_t_2d_4","content":"_bee_m_2d_5",
             "tabs":[{"title":"One","child":"_bee_tab_2d_1"},{"title":"Two","child":"tab_2"}],"text":"not-an-id","action":{"event":{"name":"go-on"}}}
            """,
        )
        assertEquals(expected, ComponentIds.rendered(stored))
    }

    @Test
    fun aTemplateRendersTheIdItRepeats() {
        assertEquals(
            component("""{"id":"list","component":"List","children":{"componentId":"_bee_row_2d_item","path":"/rows"}}"""),
            ComponentIds.rendered(component("""{"id":"list","component":"List","children":{"componentId":"row-item","path":"/rows"}}""")),
        )
    }

    @Test
    fun aMalformedReferenceIsLeftForTheValidator() {
        val odd = component("""{"id":"root","component":"Card","child":7,"children":"x-y","tabs":{"child":"a-b"}}""")
        assertEquals(odd, ComponentIds.rendered(odd))
    }

    @Test
    fun referencesNamesEveryIdRenderedRewrites() {
        val stored = component(
            """{"id":"r","component":"Column","children":["a-1"],"child":"c","trigger":"t","content":"m","tabs":[{"child":"x"}]}""",
        )
        val rewritten = ComponentIds.rendered(stored)
        assertAll(
            { assertEquals(listOf("c", "t", "m", "a-1", "x"), ComponentIds.references(stored)) },
            {
                assertEquals(
                    ComponentIds.references(stored).map(ComponentIds::rendered),
                    ComponentIds.references(rewritten),
                )
            },
            { assertTrue(ComponentIds.references(rewritten).all(identifier::matches)) },
        )
    }

    private fun component(json: String): JsonObject = Json.parseToJsonElement(json.trimIndent()).jsonObject
}
