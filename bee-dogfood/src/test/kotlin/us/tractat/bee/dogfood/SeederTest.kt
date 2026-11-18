package us.tractat.bee.dogfood

import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [Seeder]'s decisions against a [FakeBoard], in virtual time: what it does when the session's
 * replica hasn't synced with the host (`BoardClient.synced`, tractat-us/kuilt#3142), when an open
 * is refused, and when a surface's data has drifted.
 */
class SeederTest {
    private val surface = SeedSurface(
        "s",
        "S",
        listOf(Components.text(SeedSurface.ROOT, "hello")),
        buildJsonObject { put("k", buildJsonObject { put("column", "next") }) },
    )

    @Test
    fun aSurfaceTheUnsyncedReadsMissIsWaitedForNotOpenedAgain() = runTest {
        val board = FakeBoard(unsyncedReads = 3).apply { put(surface) }
        val outcomes = Seeder(board, syncWait = 2.seconds, retryAfter = 250.milliseconds).seed(listOf(surface))
        assertAll(
            { assertEquals(mapOf("s" to Outcome.Unchanged), outcomes, "the board synced within the bound") },
            { assertEquals(emptyList(), board.writes, "no second open of a surface that is there") },
        )
    }

    /** A replica that never syncs fails the seed at its bound, before a write decided against a board missing surfaces. */
    @Test
    fun aBoardThatNeverSyncsFailsTheSeedBeforeAnyWrite() = runTest {
        val board = FakeBoard(unsyncedReads = Int.MAX_VALUE).apply { put(surface) }
        assertFailsWith<BoardNotSynced> { Seeder(board, syncWait = 2.seconds, retryAfter = 250.milliseconds).seed(listOf(surface)) }
        assertAll(
            { assertEquals(emptyList(), board.writes, "nothing written") },
            { assertTrue(currentTime >= 2.seconds.inWholeMilliseconds, "it waited its bound first, at ${currentTime}ms") },
        )
    }

    /** Synced, the board is the host's: a surface missing from it isn't there, so it opens without waiting. */
    @Test
    fun aSurfaceMissingFromASyncedBoardIsOpenedAtOnce() = runTest {
        val board = FakeBoard()
        val outcomes = Seeder(board, syncWait = 2.seconds, retryAfter = 250.milliseconds).seed(listOf(surface))
        assertAll(
            { assertEquals(mapOf("s" to Outcome.Opened), outcomes) },
            { assertEquals(listOf(Seeder.OPEN), board.writes) },
            { assertEquals(0L, currentTime, "no settle wait") },
        )
    }

    @Test
    fun anOpenRefusedAsAlreadyThereRereadsTheBoard() = runTest {
        val board = FakeBoard(refuseOpens = 1, refusal = "Surface s is already on the board; update or reopen it.")
        board.onRefuse = { board.put(surface) }
        val outcomes = Seeder(board, retryAfter = 250.milliseconds).seed(listOf(surface))
        assertAll(
            { assertEquals(mapOf("s" to Outcome.Unchanged), outcomes, "the retry found it drawn") },
            { assertEquals(listOf(Seeder.OPEN), board.writes, "one refused open, no second") },
        )
    }

    @Test
    fun anOpenRefusedForAnotherReasonIsRethrown() = runTest {
        val board = FakeBoard(refuseOpens = Int.MAX_VALUE, refusal = "Component root is not in the catalog.")
        val refused = assertFailsWith<ToolRefused> { Seeder(board).seed(listOf(surface)) }
        assertAll(
            { assertEquals(Seeder.OPEN, refused.tool) },
            { assertEquals(1, board.writes.size, "no retry") },
        )
    }

    @Test
    fun anOpenAlwaysRefusedAsAlreadyThereGivesUpAfterItsAttempts() = runTest {
        val board = FakeBoard(refuseOpens = Int.MAX_VALUE, refusal = "Surface s is already on the board; update or reopen it.")
        assertFailsWith<ToolRefused> { Seeder(board, attempts = 4).seed(listOf(surface)) }
        assertEquals(4, board.writes.size, "one open per attempt, then the last refusal propagates")
    }

    @Test
    fun aClosedSurfaceIsRedrawnInPlace() = runTest {
        val board = FakeBoard().apply { closed += "s" }
        val outcomes = Seeder(board).seed(listOf(surface))
        assertAll(
            { assertEquals(mapOf("s" to Outcome.UpdatedClosed), outcomes) },
            { assertEquals(listOf(Seeder.UPDATE), board.writes) },
            { assertEquals(0L, currentTime, "a closed surface is on the board: no wait") },
        )
    }

    @Test
    fun aSurfaceWhoseDataDriftedIsRedrawnThoughItsComponentsMatch() = runTest {
        val board = FakeBoard().apply { put(surface.copy(data = buildJsonObject { put("k", buildJsonObject { put("column", "blocked") }) })) }
        val outcomes = Seeder(board).seed(listOf(surface))
        assertAll(
            { assertEquals(mapOf("s" to Outcome.Updated), outcomes, "a drag moved a card: the seed moves it back") },
            { assertEquals(surface.data, board.data("s"), "the seed's data is written") },
        )
    }

    @Test
    fun aSurfaceDrawnWithThisContentIsLeftAlone() = runTest {
        val board = FakeBoard().apply { put(surface) }
        val outcomes = Seeder(board).seed(listOf(surface))
        assertAll(
            { assertEquals(mapOf("s" to Outcome.Unchanged), outcomes) },
            { assertEquals(emptyList(), board.writes) },
        )
    }
}

/**
 * A board as `bee_board`, `bee_surface_open` and `bee_surface_update` answer, kept in memory. Its
 * first [unsyncedReads] reads show an empty board and say `"synced": false`, as a just-joined
 * session's replica can, and an open of a surface that is there succeeds, as a replica that is
 * behind would take it: the hazard the seeder's wait exists for. Its first [refuseOpens] opens are
 * refused with [refusal], after [onRefuse] runs.
 */
private class FakeBoard(
    private var unsyncedReads: Int = 0,
    private var refuseOpens: Int = 0,
    private val refusal: String = "",
) : BoardTools {
    private val open = linkedMapOf<String, Pair<List<JsonObject>, JsonObject>>()
    val closed = mutableSetOf<String>()

    /** The write tools called, in order, refused ones included. */
    val writes = mutableListOf<String>()
    var onRefuse: () -> Unit = {}

    fun put(surface: SeedSurface) {
        open[surface.id] = surface.components to surface.data
    }

    fun data(id: String): JsonObject = open.getValue(id).second

    override suspend fun call(name: String, arguments: Map<String, JsonElement>): JsonObject = when (name) {
        Seeder.BOARD -> read()
        Seeder.OPEN -> write(name) {
            val id = arguments.getValue("surfaceId").jsonPrimitive.content
            open[id] = arguments.getValue("components").jsonArray.map { it.jsonObject } to arguments.getValue("data").jsonObject
        }
        Seeder.UPDATE -> write(name) {
            val id = arguments.getValue("surfaceId").jsonPrimitive.content
            val pointers = arguments.getValue("data").jsonObject.mapKeys { (pointer, _) -> pointer.removePrefix("/") }
            val (components, data) = open[id] ?: (emptyList<JsonObject>() to JsonObject(emptyMap()))
            val written = arguments.getValue("components").jsonArray.map { it.jsonObject }
            open[id] = (components.filter { c -> written.none { it["id"] == c["id"] } } + written) to JsonObject(data + pointers)
            closed -= id
        }
        else -> error("No tool $name")
    }

    private fun read(): JsonObject {
        val unsynced = unsyncedReads > 0
        if (unsynced) unsyncedReads--
        return buildJsonObject {
            put(
                "open",
                buildJsonArray {
                    if (!unsynced) {
                        open.forEach { (id, surface) ->
                            add(
                                buildJsonObject {
                                    put("surfaceId", id)
                                    put("components", JsonArray(surface.first))
                                    put("data", surface.second)
                                },
                            )
                        }
                    }
                },
            )
            put("closed", JsonArray(if (unsynced) emptyList() else closed.map(::JsonPrimitive)))
            // As bee_board says it: only when it hasn't synced.
            if (unsynced) put("synced", false)
        }
    }

    private fun write(name: String, apply: () -> Unit): JsonObject {
        writes += name
        if (refuseOpens > 0 && name == Seeder.OPEN) {
            refuseOpens--
            onRefuse()
            throw ToolRefused(name, refusal)
        }
        apply()
        return JsonObject(emptyMap())
    }
}
