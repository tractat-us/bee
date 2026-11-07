package us.tractat.bee.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import us.tractat.bee.core.BoardFixtures.A
import us.tractat.bee.core.BoardFixtures.B
import us.tractat.bee.core.BoardFixtures.SURFACE
import us.tractat.bee.core.BoardFixtures.addCard
import us.tractat.bee.core.BoardFixtures.board
import us.tractat.bee.core.BoardFixtures.card
import us.tractat.bee.core.BoardFixtures.cardOrder
import us.tractat.bee.core.BoardFixtures.entryDots
import us.tractat.bee.core.BoardFixtures.node
import us.tractat.bee.core.BoardFixtures.opened
import us.tractat.bee.core.BoardFixtures.str
import us.tractat.bee.core.BoardFixtures.view
import us.tractat.kuilt.crdt.JsonNode
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * tractat-us/bee#14: an object write replaces, and a card order is an append-only `Rga` of ids.
 *
 * Every race here is checked in both merge orders. Several tests pin what kuilt's add-wins does
 * beneath a removed key; they describe behaviour that is ruled and documented, not wanted.
 */
class ReplaceSemanticsTest {
    @Test
    fun objectWriteRemovesTheKeysItSaw() {
        // B wrote /poll/votes and A saw it, so A's write of /poll without votes retires B's key
        // too, through ORMap's observed remove, not a null.
        val base = opened(buildJsonObject { put("poll", buildJsonObject { put("question", "Lunch?") }) })
        val voted = base.piece { it.updateData(B, SURFACE, "/poll/votes", JsonPrimitive(3)) }
        val replace = voted.updateData(A, SURFACE, "/poll", buildJsonObject { put("question", "Dinner?") })
        val replaced = voted.piece(replace)
        assertAll(
            { assertEquals(setOf("question"), (node(replaced, "/poll") as JsonNode.Object).map.keys) },
            { assertEquals(buildJsonObject { put("question", "Dinner?") }, view(replaced).data["poll"]) },
            // A peer that took B's write after the replace drops it all the same.
            { assertEquals(replaced, base.piece(replace.delta).piece(voted)) },
        )
    }

    @Test
    fun concurrentAddSurvivesAReplace() {
        // The replace removes only the keys its writer saw: B's concurrent key is not among them.
        val base = opened(buildJsonObject { put("poll", buildJsonObject { put("question", "Lunch?"); put("votes", 3) }) })
        val onA = base.piece { it.updateData(A, SURFACE, "/poll", buildJsonObject { put("question", "Dinner?") }) }
        val onB = base.piece { it.updateData(B, SURFACE, "/poll/comment", str("hi")) }
        val ab = onA.piece(onB)
        assertAll(
            { assertEquals(ab, onB.piece(onA)) },
            { assertEquals(buildJsonObject { put("comment", "hi"); put("question", "Dinner?") }, view(ab).data["poll"]) },
        )
    }

    @Test
    fun replacedArrayNeverReissuesADot() {
        // A replace tombstones an array's entries and keeps its node, so the next append mints a
        // fresh dot. Removing the node would restart its counter at Dot(A, 1), and a basis that saw
        // the old entry would then claim the new one.
        val base = opened(buildJsonObject { put("poll", buildJsonObject { put("question", "Lunch?") }) })
            .piece { it.appendEntry(A, SURFACE, "/poll/options", str("Pizza")) }
        val sawPizza = Basis.capture(base, SURFACE)
        val pizza = entryDots(base, "/poll/options").single()
        val replaced = base.piece { it.updateData(A, SURFACE, "/poll", buildJsonObject { put("question", "Dinner?") }) }
        val reappended = replaced.piece { it.appendEntry(A, SURFACE, "/poll/options", str("Tacos")) }
        val tacos = entryDots(reappended, "/poll/options").single()
        assertAll(
            { assertIs<JsonNode.Array>(node(replaced, "/poll/options")) },
            { assertEquals(JsonArray(emptyList()), (view(replaced).data.getValue("poll") as JsonObject)["options"]) },
            { assertNotEquals(pizza, tacos) },
            { assertFalse(sawPizza.saw("/poll/options", tacos)) },
            // A replica holding only the old entry hasn't delivered what the new basis names.
            { assertFalse(Basis.capture(reappended, SURFACE).completeIn(base, SURFACE)) },
            { assertTrue(sawPizza.completeIn(reappended, SURFACE)) },
        )
    }

    @Test
    fun concurrentAppendSurvivesAClear() {
        // The clear tombstones only the entries A saw; B's concurrent append is not among them.
        val base = opened(buildJsonObject { put("poll", buildJsonObject { put("question", "Lunch?") }) })
            .piece { it.appendEntry(A, SURFACE, "/poll/options", str("Pizza")) }
        val onA = base.piece { it.updateData(A, SURFACE, "/poll", buildJsonObject { put("question", "Dinner?") }) }
        val onB = base.piece { it.appendEntry(B, SURFACE, "/poll/options", str("Tacos")) }
        val ab = onA.piece(onB)
        assertAll(
            { assertEquals(ab, onB.piece(onA)) },
            { assertEquals(listOf(str("Tacos")), (view(ab).data.getValue("poll") as JsonObject)["options"]) },
        )
    }

    @Test
    fun concurrentEditRevivesARemovedItemAsAFragment() {
        // Pinned, not wanted: A empties the items while B sets c1's column. Add-wins keeps c1, with
        // only B's write in it, so the card comes back without its label. The projection shows it.
        val base = board("c1")
        val onA = base.piece { it.updateData(A, SURFACE, "/board/items", JsonObject(emptyMap())) }
        val onB = base.piece { it.updateData(B, SURFACE, "/board/items/c1/column", str("done")) }
        val ab = onA.piece(onB)
        assertAll(
            { assertEquals(ab, onB.piece(onA)) },
            { assertEquals(buildJsonObject { put("column", "done") }, items(ab)["c1"]) },
            { assertEquals(listOf("c1"), cardOrder(ab)) },
        )
    }

    @Test
    fun revivalCarriesTheWritersEarlierWrites() {
        // Pinned, not wanted: B relabelled c1 and A saw it before emptying the items. B's concurrent
        // column write revives c1, and ORMap.put folds B's own earlier label into it.
        val relabelled = board("c1").piece { it.updateData(B, SURFACE, "/board/items/c1/label", str("from b")) }
        val onA = relabelled.piece { it.updateData(A, SURFACE, "/board/items", JsonObject(emptyMap())) }
        val onB = relabelled.piece { it.updateData(B, SURFACE, "/board/items/c1/column", str("done")) }
        val ab = onA.piece(onB)
        assertAll(
            { assertEquals(ab, onB.piece(onA)) },
            { assertEquals(buildJsonObject { put("column", "done"); put("label", "from b") }, items(ab)["c1"]) },
        )
    }

    @Test
    fun typeChangeAfterARemovalLosesAConcurrentScalar() {
        // Pinned, not wanted: once A's replace removes /poll/votes, A may write it as an object. B's
        // concurrent scalar loses under kuilt's Object > Leaf rule, and no conflict is shown.
        val base = opened(buildJsonObject { put("poll", buildJsonObject { put("question", "Lunch?"); put("votes", 3) }) })
        val onA = base.piece { it.updateData(A, SURFACE, "/poll", buildJsonObject { put("question", "Lunch?") }) }
            .piece { it.updateData(A, SURFACE, "/poll/votes", buildJsonObject { put("count", 9) }) }
        val onB = base.piece { it.updateData(B, SURFACE, "/poll/votes", JsonPrimitive(4)) }
        val ab = onA.piece(onB)
        assertAll(
            { assertEquals(ab, onB.piece(onA)) },
            { assertEquals(buildJsonObject { put("count", 9) }, (view(ab).data.getValue("poll") as JsonObject)["votes"]) },
            { assertEquals(emptyMap(), view(ab).conflicts) },
        )
    }

    @Test
    fun concurrentCardAddsShowOnePermutation() {
        // A adds c1 as a Board does. B writes c2 with no append and appends c0 a second time. Both
        // merge orders show each card once, in one order: the Rga's ids, then the missing c2.
        val base = board("c0")
        val onA = addCard(base, A, "c1")
        val onB = base.piece { it.updateData(B, SURFACE, "/board/items/c2", card("c2")) }
            .piece { it.appendEntry(B, SURFACE, "/board/order", str("c0")) }
        val ab = onA.piece(onB)
        val ba = onB.piece(onA)
        assertAll(
            { assertEquals(3, entryDots(ab, "/board/order").size, "precondition: the Rga holds c0 twice and no c2") },
            { assertEquals(listOf("c0", "c1", "c2"), cardOrder(ab)) },
            { assertEquals(cardOrder(ab), cardOrder(ba)) },
        )
    }

    @Test
    fun theOrderShowsEachKeyedItemOnce() {
        // The projection appends keyed items the order lacks, sorted by id, drops ids that name no
        // item, and keeps an id's first occurrence, so every peer shows the same permutation.
        val state = board("c0", "c1")
            .piece { it.updateData(A, SURFACE, "/board/items/c3", card("c3")) }
            .piece { it.updateData(B, SURFACE, "/board/items/c2", card("c2")) }
            .piece { it.appendEntry(B, SURFACE, "/board/order", str("c0")) }
            .piece { it.appendEntry(B, SURFACE, "/board/order", str("gone")) }
        assertAll(
            { assertEquals(4, entryDots(state, "/board/order").size, "precondition: the Rga holds the repeat and the stray") },
            { assertEquals(listOf("c0", "c1", "c2", "c3"), cardOrder(state)) },
        )
    }

    @Test
    fun absentOrderShowsTheItemsSortedById() {
        val state = opened(
            buildJsonObject { put("board", buildJsonObject { put("items", buildJsonObject { put("c1", card("c1")); put("c0", card("c0")) }) }) },
        )
        assertAll(
            { assertEquals(null, node(state, "/board/order"), "precondition: no order was ever appended") },
            { assertEquals(listOf("c0", "c1"), cardOrder(state)) },
        )
    }

    @Test
    fun objectWriteToACollectionKeepsItsOrderWorking() {
        // Writing /board without naming order clears the order's entries but keeps the array, so
        // the items show sorted by id, and the next append still lands, with a fresh dot.
        val before = board("c1", "c0")
        val rewritten = before.piece {
            it.updateData(A, SURFACE, "/board", buildJsonObject { put("items", buildJsonObject { put("c0", card("c0")); put("c1", card("c1")) }) })
        }
        val added = addCard(rewritten, A, "c2")
        val earlier = entryDots(before, "/board/order").toSet()
        assertAll(
            { assertEquals(listOf("c1", "c0"), cardOrder(before), "precondition: the order isn't sorted") },
            { assertIs<JsonNode.Array>(node(rewritten, "/board/order")) },
            { assertEquals(listOf("c0", "c1"), cardOrder(rewritten)) },
            { assertEquals(listOf("c2", "c0", "c1"), cardOrder(added)) },
            { assertTrue(entryDots(added, "/board/order").none { it in earlier }) },
        )
    }

    private fun items(state: BoardState): JsonObject =
        (view(state).data.getValue("board") as JsonObject).getValue("items") as JsonObject
}
