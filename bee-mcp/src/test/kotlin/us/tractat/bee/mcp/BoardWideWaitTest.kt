package us.tractat.bee.mcp

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import us.tractat.bee.a2ui.NOTIONS_CATALOG_ID
import us.tractat.bee.core.ActionRecord
import us.tractat.bee.core.Basis
import us.tractat.bee.core.ClaimStatus
import us.tractat.bee.core.OrphanDecision
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.SurfaceMeta
import us.tractat.bee.host.JoinRequestSurface
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * `bee_wait_action` and `bee_list_actions` without a surfaceId: one wait covers every surface a
 * session may act on, so a whiteboard agent reacts to whatever a person does on any of them.
 */
class BoardWideWaitTest {
    private val iain = ReplicaId("web-iain")
    private val bo = ReplicaId("web-bo")

    /**
     * A board-wide wait already holding when a surface opens wakes on that surface's first click,
     * and returns it claimed. The wait is seen holding (its presence says so) before the surface
     * exists, so the record can't have been there when the wait began.
     */
    @Test
    fun aBoardWideWaitWakesOnASurfaceOpenedAfterItBegan() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        val browser = rig.browser()
        ada.call(BeeTools.OPEN, openArgs("poll"))

        val waited = async { ada.waitBoard(timeoutSeconds = 30) }
        ada.client.state.first { s -> s.latestPresence().any { it.identity == ada.bridge.self && it.waiting } }
        val openedLate = "kanban" !in ada.client.state.value.openSurfaces()
        ada.call(BeeTools.OPEN, openArgs("kanban"))
        browser.state.first { "kanban" in it.openSurfaces() }
        browser.mutate { it.appendRecord(iain, click(it, "kanban", "go")) }
        val record = waited.await().record()
        val target = BoardActionSource.target(BoardActionSource.parseRecordId(record.string("recordId")))
        val token = BoardActionSource.parseToken(record.string("token"))
        assertAll(
            { assertTrue(openedLate, "rig: the surface opened after the wait began") },
            { assertEquals("kanban", record.string("surfaceId")) },
            { assertEquals(true, record.getValue("basisComplete").jsonPrimitive.boolean) },
            { assertEquals(ClaimStatus.Held(ada.bridge.self, token), rig.voter.table.value.status(target), "returned claimed, as a one-surface wait returns it") },
        )
        rig.close()
    }

    /**
     * Board-wide waits resumed from each other's cursors return every record on the board exactly
     * once: records from two authors, interleaved across two surfaces, then more on a surface opened
     * between waits. A board-wide list from the last cursor finds nothing left.
     */
    @Test
    fun aBoardWideCursorResumesWithoutLossOrRepeatAcrossSurfaces() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        val iainPage = rig.browser(iain.value)
        val boPage = rig.browser(bo.value)
        ada.call(BeeTools.OPEN, openArgs("poll"))
        ada.call(BeeTools.OPEN, openArgs("kanban"))
        listOf(iainPage, boPage).forEach { page -> page.state.first { "poll" in it.openSurfaces() && "kanban" in it.openSurfaces() } }
        iainPage.mutate { it.appendRecord(iain, click(it, "poll", "go")) }
        boPage.mutate { it.appendRecord(bo, click(it, "kanban", "go")) }
        iainPage.mutate { it.appendRecord(iain, click(it, "kanban", "go")) }
        ada.client.state.first { it.records().size == 3 }

        val before = ada.drain(cursor = null)
        ada.call(BeeTools.OPEN, openArgs("diagram"))
        boPage.state.first { "diagram" in it.openSurfaces() }
        boPage.mutate { it.appendRecord(bo, click(it, "diagram", "go")) }
        iainPage.mutate { it.appendRecord(iain, click(it, "poll", "go")) }
        ada.client.state.first { it.records().size == 5 }
        val after = ada.drain(cursor = before.cursor)
        val left = ada.call(WaitTool.LIST, mapOf("since" to after.cursor)).json().getValue("records").jsonArray

        val onBoard = ada.client.state.value.records().map { (id, _) -> BoardActionSource.recordId(id) }
        val returned = before.ids + after.ids
        assertAll(
            { assertEquals(3, before.ids.size, "rig: the first drain met three records: ${before.ids}") },
            { assertEquals(returned.distinct(), returned, "no record was returned twice") },
            { assertEquals(onBoard.toSet(), returned.toSet(), "every record on the board was returned") },
            { assertEquals(setOf("poll", "kanban", "diagram"), (before.surfaces + after.surfaces).toSet()) },
            { assertEquals(setOf(iain.value, bo.value), returned.map { it.substringBeforeLast(':') }.toSet(), "from both authors") },
            { assertEquals(0, left.size, "a board-wide list from the last cursor finds nothing left") },
        )
        rig.close()
    }

    /**
     * The board-wide scope leaves out the host's pairing surfaces, where a one-surface wait is
     * refused (bee#72), and still takes in an orphan decision the host opened, which every
     * participant answers (spec §6.4).
     */
    @Test
    fun theBoardWideScopeLeavesOutTheHostsPairingSurfaces() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        val browser = rig.browser()
        val request = rig.joinRequest()
        val decision = "${OrphanDecision.PREFIX}record:x:1"
        val meta = SurfaceMeta("Orphaned claim", NOTIONS_CATALOG_ID, PeerIdentity.host("bee-host"), kotlin.time.Instant.fromEpochMilliseconds(0))
        ada.call(BeeTools.OPEN, openArgs("s"))
        browser.state.first { request in it.openSurfaces() && "s" in it.openSurfaces() }
        browser.mutate { it.openSurface(iain, decision, meta, validSurface(), JsonObject(emptyMap())) }
        browser.mutate { it.appendRecord(iain, admit(it, request)) }
        browser.mutate { it.appendRecord(iain, click(it, "s", "go")) }
        browser.mutate { it.appendRecord(iain, click(it, decision, "go")) }
        ada.client.state.first { it.records().size == 3 }

        val listed = ada.call(WaitTool.LIST, emptyMap()).json().getValue("records").jsonArray.map { it.jsonObject.string("surfaceId") }
        val first = ada.waitBoard()
        val second = ada.waitBoard(cursor = first.cursor)
        val third = ada.waitBoard(cursor = second.cursor, timeoutSeconds = 1)
        assertAll(
            { assertEquals(listOf("s", decision), listed, "the list leaves out the join request only") },
            { assertEquals(listOf("s", decision), listOfNotNull(first.record, second.record).map { it.string("surfaceId") }) },
            { assertTrue(third.timedOut, "the admit record is never returned: ${third.record}") },
            { assertTrue(request in ada.client.state.value.openSurfaces(), "rig: the join request is open, with its record") },
        )
        rig.close()
    }

    private fun admit(state: us.tractat.bee.core.BoardState, request: String) = ActionRecord(
        surfaceId = request,
        componentId = JoinRequestSurface.ADMIT_BUTTON,
        actor = PeerIdentity.human("iain"),
        name = JoinRequestSurface.ADMIT_ACTION,
        context = buildJsonObject { put("code", "000000") },
        basis = Basis.capture(state, request),
        at = kotlin.time.Instant.fromEpochMilliseconds(0),
    )

    private data class Waited(val record: JsonObject?, val timedOut: Boolean, val cursor: String) {
        fun record(): JsonObject = checkNotNull(record) { "the wait timed out" }
    }

    /** What a run of board-wide waits returned before one timed out, and the cursor it ended on. */
    private data class Drained(val ids: List<String>, val surfaces: List<String>, val cursor: String)

    /** Board-wide waits from [cursor], each resuming from the last one's cursor, until one times out. */
    private suspend fun Session.drain(cursor: String?): Drained {
        val records = mutableListOf<JsonObject>()
        var at = cursor
        while (true) {
            val waited = waitBoard(cursor = at, timeoutSeconds = 1)
            at = waited.cursor
            if (waited.timedOut) break
            records += waited.record()
        }
        return Drained(records.map { it.string("recordId") }, records.map { it.string("surfaceId") }, checkNotNull(at))
    }

    private suspend fun Session.waitBoard(cursor: String? = null, timeoutSeconds: Int = 30): Waited {
        val args = buildMap<String, Any?> {
            cursor?.let { put("cursor", it) }
            put("timeoutSeconds", timeoutSeconds)
        }
        val result = call(WaitTool.WAIT, args)
        check(result.isError != true) { "the wait was refused: ${result.text()}" }
        val json = result.json()
        return Waited(json["record"]?.jsonObject, json.getValue("timedOut").jsonPrimitive.boolean, json.getValue("cursor").jsonPrimitive.content)
    }

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
}
