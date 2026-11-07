package us.tractat.bee.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * tractat-us/bee#14: an object write replaces by keep-and-clear, and a card order is an
 * append-only `Rga` of ids.
 *
 * Every race is checked in both merge orders. The probes from #19's review each run beside a
 * control without the replace: a replace must leave a later write's merge exactly as it was.
 */
class ReplaceSemanticsTest {
    @Test
    fun objectWriteClearsTheKeysItSaw() {
        // B wrote /poll/votes and A saw it, so A's write of /poll without votes clears B's key too.
        // The key is kept, with its register, and the projection hides it.
        val base = opened(buildJsonObject { put("poll", buildJsonObject { put("question", "Lunch?") }) })
        val voted = base.piece { it.updateData(B, SURFACE, "/poll/votes", JsonPrimitive(3)) }
        val replace = voted.updateData(A, SURFACE, "/poll", buildJsonObject { put("question", "Dinner?") })
        val replaced = voted.piece(replace)
        assertAll(
            { assertIs<JsonNode.Leaf>(node(replaced, "/poll/votes")) },
            { assertEquals(buildJsonObject { put("question", "Dinner?") }, poll(replaced)) },
            { assertEquals(emptyMap(), view(replaced).conflicts) },
            // A peer that took B's write after the replace clears it all the same.
            { assertEquals(replaced, base.piece(replace.delta).piece(voted)) },
        )
    }

    @Test
    fun concurrentAddSurvivesAReplace() {
        // The replace clears only what its writer saw: B's concurrent key is not among it.
        val base = opened(buildJsonObject { put("poll", buildJsonObject { put("question", "Lunch?"); put("votes", 3) }) })
        val onA = base.piece { it.updateData(A, SURFACE, "/poll", buildJsonObject { put("question", "Dinner?") }) }
        val onB = base.piece { it.updateData(B, SURFACE, "/poll/comment", str("hi")) }
        val ab = onA.piece(onB)
        assertAll(
            { assertEquals(ab, onB.piece(onA)) },
            { assertEquals(buildJsonObject { put("comment", "hi"); put("question", "Dinner?") }, poll(ab)) },
        )
    }

    @Test
    fun rewriteAfterAReplaceStillConflictsWithAConcurrentWrite() {
        // Review probe 1. A clears votes and writes 5 while B writes 4. Had the clear removed the
        // key, A's 5 would reuse the register dot B already superseded, and vanish.
        val base = opened(buildJsonObject { put("poll", buildJsonObject { put("question", "Lunch?"); put("votes", 3) }) })
        val onB = base.piece { it.updateData(B, SURFACE, "/poll/votes", JsonPrimitive(4)) }
        fun onA(start: BoardState) = start.piece { it.updateData(A, SURFACE, "/poll/votes", JsonPrimitive(5)) }
        val control = onA(base)
        val replaced = onA(base.piece { it.updateData(A, SURFACE, "/poll", buildJsonObject { put("question", "Lunch?") }) })
        val both = listOf<JsonElement>(JsonPrimitive(4), JsonPrimitive(5))
        assertAll(
            { assertEquals(both, view(control.piece(onB)).conflicts["/poll/votes"], "control") },
            { assertEquals(both, view(replaced.piece(onB)).conflicts["/poll/votes"]) },
            { assertEquals(replaced.piece(onB), onB.piece(replaced)) },
        )
    }

    @Test
    fun rewriteAfterARootReplaceSurvivesAConcurrentReplace() {
        // Review probe 2. A clears the whole model, then writes /poll/question, while B replaces
        // /poll. Had A's clear removed poll, A's write would reuse a key tag B's replace retires.
        val base = opened(buildJsonObject { put("poll", buildJsonObject { put("question", "Lunch?") }) })
        val onB = base.piece { it.updateData(B, SURFACE, "/poll", buildJsonObject { put("note", "b") }) }
        fun onA(start: BoardState) = start.piece { it.updateData(A, SURFACE, "/poll/question", str("Dinner?")) }
        val control = onA(base)
        val replaced = onA(base.piece { it.updateData(A, SURFACE, "/", JsonObject(emptyMap())) })
        val expected = buildJsonObject { put("note", "b"); put("question", "Dinner?") }
        assertAll(
            { assertEquals(expected, poll(control.piece(onB)), "control") },
            { assertEquals(expected, poll(replaced.piece(onB))) },
            { assertEquals(replaced.piece(onB), onB.piece(replaced)) },
        )
    }

    @Test
    fun replaceNeverFreesAPathForANewKind() {
        // Review probe 3. B's concurrent object beats A's appended array at /poll/options. Had A's
        // replace of /poll removed options, A could append there again, reissuing Dot(A, 1).
        val base = opened(buildJsonObject { put("poll", buildJsonObject { put("question", "Lunch?") }) })
        val onA = base.piece { it.appendEntry(A, SURFACE, "/poll/options", str("Pizza")) }
        val onB = base.piece { it.updateData(B, SURFACE, "/poll/options", buildJsonObject { put("x", 1) }) }
        val merged = onA.piece(onB)
        val replaced = merged.piece { it.updateData(A, SURFACE, "/poll", buildJsonObject { put("question", "Lunch?") }) }
        assertAll(
            { assertIs<JsonNode.Object>(node(merged, "/poll/options"), "precondition: the object won") },
            { assertFailsWith<IllegalArgumentException>("control") { merged.appendEntry(A, SURFACE, "/poll/options", str("Tacos")) } },
            { assertFailsWith<IllegalArgumentException> { replaced.appendEntry(A, SURFACE, "/poll/options", str("Tacos")) } },
        )
    }

    @Test
    fun clearedPathKeepsItsKind() {
        // A cleared leaf takes a scalar or a whole array again, never an object: a clear doesn't
        // free a path to change kind, so no concurrent scalar can be lost to Object > Leaf.
        val base = opened(buildJsonObject { put("poll", buildJsonObject { put("question", "Lunch?"); put("votes", 3) }) })
        val cleared = base.piece { it.updateData(A, SURFACE, "/poll", buildJsonObject { put("question", "Lunch?") }) }
        val count = buildJsonObject { put("count", 9) }
        assertAll(
            { assertFailsWith<IllegalArgumentException>("control") { base.updateData(A, SURFACE, "/poll/votes", count) } },
            { assertFailsWith<IllegalArgumentException> { cleared.updateData(A, SURFACE, "/poll/votes", count) } },
            { assertFailsWith<IllegalArgumentException> { cleared.appendEntry(A, SURFACE, "/poll/votes", str("x")) } },
            { assertEquals(JsonPrimitive(5), poll(cleared.piece { it.updateData(A, SURFACE, "/poll/votes", JsonPrimitive(5)) })["votes"]) },
        )
    }

    @Test
    fun replacedArrayNeverReissuesADot() {
        // A replace tombstones an array's entries and keeps its node, so the next append mints a
        // fresh dot, and a basis that saw the old entry can't claim the new one.
        val base = opened(buildJsonObject { put("poll", buildJsonObject { put("question", "Lunch?") }) })
            .piece { it.appendEntry(A, SURFACE, "/poll/options", str("Pizza")) }
        val sawPizza = Basis.capture(base, SURFACE)
        val pizza = entryDots(base, "/poll/options").single()
        val replaced = base.piece { it.updateData(A, SURFACE, "/poll", buildJsonObject { put("question", "Dinner?") }) }
        val reappended = replaced.piece { it.appendEntry(A, SURFACE, "/poll/options", str("Tacos")) }
        val tacos = entryDots(reappended, "/poll/options").single()
        assertAll(
            { assertIs<JsonNode.Array>(node(replaced, "/poll/options")) },
            { assertEquals(JsonArray(emptyList()), poll(replaced)["options"]) },
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
            { assertEquals(JsonArray(listOf(str("Tacos"))), poll(ab)["options"]) },
        )
    }

    @Test
    fun droppedObjectShowsAsAnEmptyHusk() {
        // A replace keeps a dropped object and clears it, so it shows as {} rather than absent.
        val base = opened(buildJsonObject { put("poll", buildJsonObject { put("question", "Lunch?") }); put("answer", "x") })
        val replaced = base.piece { it.updateData(A, SURFACE, "/", buildJsonObject { put("answer", "y") }) }
        assertEquals(buildJsonObject { put("answer", "y"); put("poll", JsonObject(emptyMap())) }, view(replaced).data)
    }

    @Test
    fun concurrentEditLeavesAClearedItemAsAFragment() {
        // A empties the items while B sets c1's column. B's write survives the clear and nothing
        // else of c1 does, so the card shows without its label. The projection shows it as it is.
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
    fun clearSupersedesWhatItsWriterSaw() {
        // B relabelled c1 and A saw it, and only A wrote c1's due date. A empties the items while
        // B sets c1's column: the label and the due date stay cleared, and only B's column shows.
        val seen = board("c1")
            .piece { it.updateData(A, SURFACE, "/board/items/c1/due", str("fri")) }
            .piece { it.updateData(B, SURFACE, "/board/items/c1/label", str("from b")) }
        val onA = seen.piece { it.updateData(A, SURFACE, "/board/items", JsonObject(emptyMap())) }
        val onB = seen.piece { it.updateData(B, SURFACE, "/board/items/c1/column", str("done")) }
        val ab = onA.piece(onB)
        assertAll(
            { assertEquals(ab, onB.piece(onA)) },
            { assertEquals(buildJsonObject { put("column", "done") }, items(ab)["c1"]) },
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
    fun onlyItemsAndOrderMakeACollection() {
        // An object with members besides items and order is ordinary data and gets no order. Once
        // a replace clears the extra member, only items is shown, and it is a collection again.
        val cart = buildJsonObject { put("items", buildJsonObject { put("x", 1) }); put("title", "t") }
        val state = opened(buildJsonObject { put("cart", cart) })
        val cleared = state.piece { it.updateData(A, SURFACE, "/cart", buildJsonObject { put("items", buildJsonObject { put("x", 1) }) }) }
        assertAll(
            { assertEquals(cart, view(state).data["cart"]) },
            { assertEquals(JsonArray(listOf(str("x"))), (view(cleared).data.getValue("cart") as JsonObject)["order"]) },
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

    private fun poll(state: BoardState): JsonObject = view(state).data.getValue("poll") as JsonObject

    private fun items(state: BoardState): JsonObject =
        (view(state).data.getValue("board") as JsonObject).getValue("items") as JsonObject
}
