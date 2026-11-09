package us.tractat.bee.dogfood

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import us.tractat.bee.a2ui.NOTIONS_CATALOG_ID
import us.tractat.bee.a2ui.SurfaceValidator
import us.tractat.bee.a2ui.SurfaceView
import us.tractat.kuilt.test.assertAll
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlanBoardTest {
    private val plan = FixturePlan.parse()

    @Test
    fun aPlanBecomesOneColumnPerStatusAndOneCardPerItemWithDoneCapped() {
        val board = board(PlanBoard.surface(plan))
        val columns = board.obj("columns")
        val cards = board.obj("cards").obj("items")
        assertAll(
            { assertEquals(listOf("done", "in-flight", "waiting-on-iain", "next"), columns.getValue("order").jsonArray.map { it.jsonPrimitive.content }, "columns in column_order") },
            { assertEquals("Done (${FixturePlan.DONE_ITEMS})", columns.obj("items").obj("done").string("title"), "a column counts its items") },
            { assertEquals("iain", columns.obj("items").obj("waiting-on-iain").string("owner"), "a column keeps its owner") },
            { assertEquals("in-flight", cards.obj(FixturePlan.IN_FLIGHT).string("column"), "an open item is a card in its column") },
            { assertEquals("waiting-on-iain", cards.obj("bee-25").string("column")) },
            { assertEquals((3..7).map { "task-$it" }.toSet(), cards.keys.filter { it.startsWith("task-") }.toSet(), "the done column shows only the latest landed") },
            { assertEquals("+ 2 earlier, in docs/project/plan.yaml", cards.obj(PlanBoard.EARLIER_DONE).string("label")) },
        )
    }

    @Test
    fun theSamePlanDrawsTheSameSurfaceAndADroppedItemMovesTheBindings() {
        val first = PlanBoard.surface(plan)
        val again = PlanBoard.surface(FixturePlan.parse())
        val dropped = FixturePlan.NEXT
        val without = PlanBoard.surface(JsonObject(plan + ("items" to JsonObject(plan.obj("items") - dropped))))
        assertAll(
            { assertEquals(first, again, "a re-seed of an unchanged plan changes nothing") },
            { assertNotEquals(first.data.keys, without.data.keys, "a dropped card writes under a new key") },
            { assertTrue(dropped !in board(without).obj("cards").obj("items"), "a dropped item has no card") },
            { assertEquals("/${without.data.keys.single()}/cards", without.components.single().obj("cards").string("path"), "the Board binds the new key") },
        )
    }

    @Test
    fun aChangedCardKeepsTheKeySoItsWriteOverwritesInPlace() {
        val items = plan.obj("items")
        val moved = JsonObject(items.obj(FixturePlan.NEXT) + mapOf("status" to JsonPrimitive("in-flight"), "title" to JsonPrimitive("Card links, renamed")))
        val changed = PlanBoard.surface(JsonObject(plan + ("items" to JsonObject(items + (FixturePlan.NEXT to moved)))))
        val first = PlanBoard.surface(plan)
        assertAll(
            { assertEquals(first.data.keys, changed.data.keys, "the same cards and columns: the same key") },
            { assertEquals("in-flight", board(changed).obj("cards").obj("items").obj(FixturePlan.NEXT).string("column"), "the card moved") },
            { assertNotEquals(first.data, changed.data, "its content changed") },
        )
    }

    @Test
    fun theRealPlanParsesAndMaps() {
        val real = PlanYaml.parse(Path("../docs/project/plan.yaml").readText())
        val cards = board(PlanBoard.surface(real)).obj("cards").obj("items")
        val items = real.obj("items").mapValues { it.value.jsonObject }
        val notDone = items.filterValues { it.string("status") != "done" }
        assertAll(
            { assertTrue(notDone.all { (id, item) -> cards[id]?.jsonObject?.string("column") == item.string("status") }, "every open item is a card in its column") },
            { assertTrue(cards.keys.count { items[it]?.string("status") == "done" } <= PlanBoard.DONE_SHOWN, "the done column is capped") },
            {
                val surface = PlanBoard.surface(real)
                val view = SurfaceView(
                    id = surface.id,
                    catalogId = NOTIONS_CATALOG_ID,
                    components = surface.components.associateBy { it.string("id") },
                    data = surface.data,
                    conflicts = emptyMap(),
                    appended = emptySet(),
                )
                assertEquals(emptyList(), SurfaceValidator.errors(view), "every card's href and detail pass the validator")
            },
        )
    }

    @Test
    fun aCardLinksItsReferenceAndTitleAndCarriesTheFirstSentenceUnlessDone() {
        val item = PlanYaml.parse(
            """
            kind: issue
            title: "bee's own development board"
            status: next
            summary: "Stand it up locally. Then the attention-mode switch."
            links:
              - { title: "Plan", url: "../superpowers/plans/x.md" }
              - { title: "bee#21", url: "https://github.com/tractat-us/bee/issues/21" }
            """.trimIndent(),
        )
        val done = JsonObject(item + ("status" to JsonPrimitive("done")))
        val unlinked = JsonObject(item - "links")
        assertAll(
            {
                assertEquals(
                    mapOf(
                        "label" to "bee#21 · bee's own development board",
                        "column" to "next",
                        "href" to "https://github.com/tractat-us/bee/issues/21",
                        "detail" to "Stand it up locally.",
                    ),
                    PlanBoard.card(item).mapValues { it.value.jsonPrimitive.content },
                )
            },
            { assertEquals("", PlanBoard.card(done).string("detail"), "a done card writes an empty detail, so a landed card loses its line") },
            { assertEquals("bee#21 · bee's own development board", PlanBoard.card(done).string("label")) },
            { assertEquals(JsonPrimitive(""), PlanBoard.card(unlinked)["href"], "an item with no GitHub link writes an empty href") },
            { assertEquals("bee's own development board", PlanBoard.card(unlinked).string("label")) },
            { assertEquals("kuilt#3126", PlanBoard.ref(PlanYaml.parse("links: [{ title: k, url: 'https://github.com/tractat-us/kuilt/pull/3126' }]"))) },
        )
    }

    @Test
    fun aLongSentenceIsCutAtAWord() {
        val long = "word ".repeat(40).trim() + "."
        val cut = PlanBoard.short(long)
        assertAll(
            { assertTrue(cut.length <= PlanBoard.SUMMARY_CHARS + 1, "cut to the limit: $cut") },
            { assertTrue(cut.endsWith("word…"), "at a word, with an ellipsis: $cut") },
            { assertEquals("v1.2 ships.", PlanBoard.short("v1.2 ships. Then more."), "a dot inside a word ends no sentence") },
        )
    }

    private fun board(surface: SeedSurface): JsonObject = surface.data.values.single().jsonObject
}
