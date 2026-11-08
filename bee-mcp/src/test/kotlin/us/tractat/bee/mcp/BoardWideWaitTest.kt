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
import us.tractat.bee.core.ClaimOutcome
import us.tractat.bee.core.ClaimStatus
import us.tractat.bee.core.ClaimTarget
import us.tractat.bee.core.CompanionSurfaces
import us.tractat.bee.core.DepartReason
import us.tractat.bee.core.OrphanDecision
import us.tractat.bee.core.PairingSurfaces
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.SurfaceMeta
import us.tractat.bee.host.JoinRequestSurface
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * `bee_wait_action` and `bee_list_actions` without a surfaceId: one wait covers every surface the
 * session's own bridge opened, so a whiteboard agent reacts to whatever a person does on any of
 * them, and never takes a click another handler owns.
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

        val listedBefore = ada.listBoard(since = null)
        val before = ada.drain(cursor = null)
        ada.call(BeeTools.OPEN, openArgs("diagram"))
        boPage.state.first { "diagram" in it.openSurfaces() }
        boPage.mutate { it.appendRecord(bo, click(it, "diagram", "go")) }
        iainPage.mutate { it.appendRecord(iain, click(it, "poll", "go")) }
        ada.client.state.first { it.records().size == 5 }
        val after = ada.drain(cursor = before.cursor)
        val listedAfter = ada.listBoard(since = listedBefore.second)
        val left = ada.listBoard(since = after.cursor).first

        val onBoard = ada.client.state.value.records().map { (id, _) -> BoardActionSource.recordId(id) }
        val returned = before.ids + after.ids
        val listed = listedBefore.first + listedAfter.first
        assertAll(
            { assertEquals(onBoard, listed, "the lists, resumed from a board-wide cursor, read every record once, in order") },
            { assertEquals(3, before.ids.size, "rig: the first drain met three records: ${before.ids}") },
            { assertEquals(returned.distinct(), returned, "no record was returned twice") },
            { assertEquals(emptyList(), before.relisted + after.relisted, "each wait's cursor names every record returned so far") },
            { assertEquals(onBoard.toSet(), returned.toSet(), "every record on the board was returned") },
            { assertEquals(setOf("poll", "kanban", "diagram"), (before.surfaces + after.surfaces).toSet()) },
            { assertEquals(setOf(iain.value, bo.value), returned.map { it.substringBeforeLast(':') }.toSet(), "from both authors") },
            { assertEquals(emptyList(), left, "a board-wide list from the last wait's cursor finds nothing left") },
        )
        rig.close()
    }

    /**
     * A board-wide wait takes only the surfaces its own bridge opened. Another session's surface,
     * the brainstorming companion's and an orphan decision the host opened all have a click on
     * them, and none reaches it: their records belong to other handlers (spec §3, §6.4). The other
     * session's board-wide list reads its own surface, as a control.
     */
    @Test
    fun aBoardWideWaitTakesOnlyTheSurfacesThisBridgeOpened() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        val bo = rig.session("bo")
        val browser = rig.browser()
        val companion = "${CompanionSurfaces.PREFIX}abc"
        val decision = "${OrphanDecision.PREFIX}record:x:1"
        ada.call(BeeTools.OPEN, openArgs("mine"))
        bo.call(BeeTools.OPEN, openArgs("theirs"))
        browser.state.first { "mine" in it.openSurfaces() && "theirs" in it.openSurfaces() }
        browser.mutate { it.openSurface(iain, companion, meta(PeerIdentity.agent("companion", Random(9))), validSurface(), JsonObject(emptyMap())) }
        browser.mutate { it.openSurface(iain, decision, meta(PeerIdentity.host("bee-host")), validSurface(), JsonObject(emptyMap())) }
        listOf("theirs", companion, decision, "mine").forEach { surface -> browser.mutate { it.appendRecord(iain, click(it, surface, "go")) } }
        ada.client.state.first { it.records().size == 4 }
        bo.client.state.first { it.records().size == 4 }

        val listed = ada.listSurfaces()
        val first = ada.waitBoard()
        val second = ada.waitBoard(cursor = first.cursor, timeoutSeconds = 1)
        val bosOwn = bo.listSurfaces()
        assertAll(
            { assertTrue("theirs" !in listed, "another session's surface is left out: $listed") },
            { assertTrue(companion !in listed, "the companion's surface is left out: $listed") },
            { assertTrue(decision !in listed, "an orphan decision is left out: $listed") },
            { assertEquals(listOf("mine"), listed) },
            { assertEquals("mine", first.record().string("surfaceId")) },
            { assertTrue(second.timedOut, "nothing else is this session's: ${second.record}") },
            { assertEquals(listOf("theirs"), bosOwn, "control: the other session's own surface is its own") },
        )
        rig.close()
    }

    /**
     * The surfaces another handler owns by name stay out of a board-wide scope even when this
     * bridge opened one under that name: a companion's, an orphan decision's and a pairing prefix.
     */
    @Test
    fun aBoardWideWaitLeavesOutReservedNamesEvenOnItsOwnSurfaces() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        val browser = rig.browser()
        val reserved = listOf("${CompanionSurfaces.PREFIX}own", "${OrphanDecision.PREFIX}record:y:1", "${PairingSurfaces.PREFIX}own")
        val all = reserved + "plain"
        all.forEach { ada.call(BeeTools.OPEN, openArgs(it)) }
        // The host closes a pairing surface it isn't waiting on, so the rig waits on each one's meta, not its being open.
        browser.state.first { s -> all.all { s.meta(it) != null } }
        all.forEach { surface -> browser.mutate { it.appendRecord(iain, click(it, surface, "go")) } }
        ada.client.state.first { it.records().size == 4 }

        val listed = ada.listSurfaces()
        assertAll(
            { assertTrue(reserved[0] !in listed, "a companion name: $listed") },
            { assertTrue(reserved[1] !in listed, "an orphan decision's name: $listed") },
            { assertTrue(reserved[2] !in listed, "a pairing name: $listed") },
            { assertEquals(listOf("plain"), listed) },
            { assertTrue(reserved.all { ada.client.state.value.meta(it)?.openedBy == ada.bridge.self }, "rig: ada opened every reserved name") },
        )
        rig.close()
    }

    /**
     * Pairing stays fail-closed: a board-wide scope never takes in a `bee-pair-` surface this
     * bridge didn't open, whoever opened it. Not the host's join request, not one another agent
     * opened, and not one whose meta never reached this replica, so its opener is unknown. None of
     * their records is named in a cursor, so none is passed over for good either.
     */
    @Test
    fun aBoardWideScopeLeavesOutEveryPairingSurfaceItDidNotOpen() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        val bo = rig.session("bo")
        val browser = rig.browser()
        val request = rig.joinRequest()
        val agents = "${PairingSurfaces.PREFIX}agent"
        val ghost = "${PairingSurfaces.PREFIX}ghost"
        ada.call(BeeTools.OPEN, openArgs("s"))
        bo.call(BeeTools.OPEN, openArgs(agents))
        // The host closes a pairing surface it isn't waiting on, so the rig waits on bo's one's meta, not its being open.
        browser.state.first { request in it.openSurfaces() && "s" in it.openSurfaces() && it.meta(agents) != null }
        browser.mutate { it.appendRecord(iain, admit(it, request)) }
        browser.mutate { it.appendRecord(iain, click(it, agents, "go")) }
        // A record on a surface whose meta no replica ever received: the open is pieced into a scratch state only.
        browser.mutate { st ->
            val scratch = st.piece(st.openSurface(iain, ghost, meta(PeerIdentity.agent("ghost", Random(8))), validSurface(), JsonObject(emptyMap())).delta)
            scratch.appendRecord(iain, click(scratch, ghost, "go"))
        }
        browser.mutate { it.appendRecord(iain, click(it, "s", "go")) }
        val state = ada.client.state.first { it.records().size == 4 }

        val list = ada.call(WaitTool.LIST, emptyMap()).json()
        val first = ada.waitBoard()
        val second = ada.waitBoard(cursor = first.cursor, timeoutSeconds = 1)
        val sId = BoardActionSource.recordId(state.records("s").single().first)
        assertAll(
            { assertEquals(null, state.meta(ghost), "rig: the ghost surface's meta never arrived") },
            { assertEquals(1, state.records(ghost).size, "rig: its record did") },
            { assertEquals(listOf("s"), list.getValue("records").jsonArray.map { it.jsonObject.string("surfaceId") }, "only this session's surface is listed") },
            { assertEquals(sId, list.getValue("cursor").jsonPrimitive.content, "the list's cursor names no pairing record") },
            { assertEquals("s", first.record().string("surfaceId")) },
            { assertTrue(second.timedOut, "no pairing record is returned: ${second.record}") },
            { assertEquals(sId, second.cursor, "the timed-out wait's cursor names no pairing record") },
        )
        rig.close()
    }

    /**
     * Two sessions waiting board-wide over their own surfaces, as two whiteboard agents on one
     * board do, split its records between them: disjoint, together every record, each only its own
     * surfaces'. First with clicks pending before either waits, then with both waits holding as the
     * clicks arrive.
     */
    @Test
    fun twoSessionsWaitingBoardWideSplitTheBoardByWhoOpenedWhat() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        val bo = rig.session("bo")
        val browser = rig.browser()
        ada.call(BeeTools.OPEN, openArgs("a1"))
        ada.call(BeeTools.OPEN, openArgs("a2"))
        bo.call(BeeTools.OPEN, openArgs("b1"))
        browser.state.first { s -> listOf("a1", "a2", "b1").all { it in s.openSurfaces() } }
        listOf("a1", "b1", "a2").forEach { surface -> browser.mutate { it.appendRecord(iain, click(it, surface, "go")) } }
        ada.client.state.first { it.records().size == 3 }
        bo.client.state.first { it.records().size == 3 }

        val adaBefore = ada.drain(cursor = null)
        val boBefore = bo.drain(cursor = null)
        val adaHolding = async { ada.waitBoard(cursor = adaBefore.cursor) }
        val boHolding = async { bo.waitBoard(cursor = boBefore.cursor) }
        listOf(ada, bo).forEach { session -> session.client.state.first { s -> s.latestPresence().any { it.identity == session.bridge.self && it.waiting } } }
        listOf("b1", "a2").forEach { surface -> browser.mutate { it.appendRecord(iain, click(it, surface, "go")) } }
        val adaHeld = adaHolding.await().record()
        val boHeld = boHolding.await().record()
        val adaAfter = ada.drain(cursor = null)
        val boAfter = bo.drain(cursor = null)

        val adaIds = adaBefore.ids + adaHeld.string("recordId")
        val boIds = boBefore.ids + boHeld.string("recordId")
        val onBoard = ada.client.state.value.records().map { (id, _) -> BoardActionSource.recordId(id) }
        assertAll(
            { assertEquals(listOf("a1", "a2"), adaBefore.surfaces, "ada took its own pending clicks only") },
            { assertEquals(listOf("b1"), boBefore.surfaces, "bo took its own pending click, which ada left") },
            { assertEquals("a2", adaHeld.string("surfaceId")) },
            { assertEquals("b1", boHeld.string("surfaceId")) },
            { assertEquals(emptyList(), adaAfter.ids + boAfter.ids, "a fresh drain finds nothing: each session holds what it took") },
            { assertEquals(emptySet(), adaIds.toSet() intersect boIds.toSet(), "disjoint") },
            { assertEquals(onBoard.toSet(), (adaIds + boIds).toSet(), "together, every record on the board") },
            { assertEquals(5, onBoard.size, "rig: five clicks") },
        )
        rig.close()
    }

    /**
     * After a `/clear`, the bridge acts under a fresh identity, and a surface it opened under the
     * old one is still its own: a board-wide wait takes the clicks on it, and on one opened since.
     */
    @Test
    fun aBoardWideWaitKeepsTheSurfacesItOpenedBeforeAClear() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        val browser = rig.browser()
        ada.call(BeeTools.OPEN, openArgs("before"))
        val old = ada.bridge.self
        ada.identity.retire(DepartReason.Cleared)
        ada.link().client.table.first { it.retired(old) }
        ada.call(BeeTools.OPEN, openArgs("after"))
        browser.state.first { "before" in it.openSurfaces() && "after" in it.openSurfaces() }
        listOf("before", "after").forEach { surface -> browser.mutate { it.appendRecord(iain, click(it, surface, "go")) } }
        ada.client.state.first { it.records().size == 2 }

        val drained = ada.drain(cursor = null)
        assertAll(
            { assertEquals(old, ada.client.state.value.meta("before")?.openedBy, "rig: the old identity opened it") },
            { assertTrue(ada.bridge.self != old, "rig: the bridge acts under a fresh identity") },
            { assertEquals(listOf("before", "after"), drained.surfaces) },
        )
        rig.close()
    }

    /**
     * A record a person reassigns to this session counts as its own in a board-wide wait, though
     * its surface is another agent's (spec §5.2, §6.4). Zed opened `theirs`; of its two clicks,
     * nobody claimed the first, and zed held the second and departed, orphaning it. Before the
     * reassign, ada's board-wide wait takes neither. Then, with her next board-wide wait holding,
     * a person reassigns the orphan to her: the wait returns it under the reassign's token. The
     * grant is the record, not the surface: the free click ahead of it on `theirs`, which any
     * claim would win, is neither returned nor claimed.
     */
    @Test
    fun aBoardWideWaitTakesARecordReassignedToItOnAnotherAgentsSurface() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        val (granted, free) = rig.orphanBesideAFreeClickOnAnotherAgentsSurface(ada)

        val before = ada.waitBoard(timeoutSeconds = 2)
        val holding = async { ada.waitBoard(cursor = before.cursor, timeoutSeconds = 30) }
        ada.client.state.first { s -> s.latestPresence().any { it.identity == ada.bridge.self && it.waiting } }
        val token = rig.claims.client(NodeId("iain-page")).reassign(granted, to = ada.bridge.self, by = PeerIdentity.human("iain"))
        val waited = holding.await()
        val again = ada.waitBoard(cursor = waited.cursor, timeoutSeconds = 2)
        val returned = waited.record?.let { BoardActionSource.target(BoardActionSource.parseRecordId(it.string("recordId"))) }
        assertAll(
            { assertTrue(before.timedOut, "before the reassign, nothing on zed's surface is ada's: ${before.record}") },
            { assertNotNull(token, "rig: the reassign committed") },
            { assertEquals(granted, returned, "the holding board-wide wait returns the reassigned record") },
            { assertEquals(token?.let(BoardActionSource::tokenText), waited.record?.string("token"), "under the reassign's token") },
            { assertTrue(again.timedOut, "the free click on zed's surface stays out: ${again.record}") },
            { assertEquals(ClaimStatus.Free, rig.voter.table.value.status(free), "no wait claimed the free click, which any claim would have won") },
        )
        rig.close()
    }

    /**
     * A surface whose meta hasn't reached this replica stays out of a board-wide scope under any
     * name, not only a pairing one, since its opener is unknown (spec §5.2). The ghost surface's
     * name is plain, so no reserved prefix keeps it out: only the unknown opener does. Its record
     * is never listed, returned or named in a cursor.
     */
    @Test
    fun aBoardWideScopeLeavesOutASurfaceWhoseOpenerIsUnknown() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        val browser = rig.browser()
        val ghost = "plain-ghost"
        ada.call(BeeTools.OPEN, openArgs("s"))
        browser.state.first { "s" in it.openSurfaces() }
        // A record on a surface whose meta no replica ever received: the open is pieced into a scratch state only.
        browser.mutate { st ->
            val scratch = st.piece(st.openSurface(iain, ghost, meta(PeerIdentity.agent("ghost", Random(8))), validSurface(), JsonObject(emptyMap())).delta)
            scratch.appendRecord(iain, click(scratch, ghost, "go"))
        }
        browser.mutate { it.appendRecord(iain, click(it, "s", "go")) }
        val state = ada.client.state.first { it.records().size == 2 }

        val listed = ada.listBoard(since = null)
        val first = ada.waitBoard()
        val second = ada.waitBoard(cursor = first.cursor, timeoutSeconds = 1)
        val sId = BoardActionSource.recordId(state.records("s").single().first)
        assertAll(
            { assertEquals(null, state.meta(ghost), "rig: the ghost surface's meta never arrived") },
            { assertEquals(1, state.records(ghost).size, "rig: its record did") },
            { assertTrue(HANDLED_ELSEWHERE_PREFIXES.none(ghost::startsWith), "rig: no reserved prefix covers the ghost's name") },
            { assertEquals(listOf(sId) to sId, listed, "only this session's record is listed, and named in the cursor") },
            { assertEquals("s", first.record().string("surfaceId")) },
            { assertTrue(second.timedOut, "the ghost's record is never returned: ${second.record}") },
            { assertEquals(sId, second.cursor, "the timed-out wait's cursor names no ghost record") },
        )
        rig.close()
    }

    /**
     * Opens `theirs` as agent zed from a browser and clicks it twice. Nobody claims the first
     * click; zed claims the second and departs crashed, orphaning it. Returns the orphan's claim
     * target and the free one's, once [session] has both records. [session] opens nothing, so no
     * surface on the board is its own.
     *
     * The free click comes first in sequence order on purpose: a wait that took in the whole
     * surface once a record on it was granted would meet the free click, and win it, before the
     * grant. With the order reversed, the grant is handed over first and leaves the granted set,
     * so a surface-wide grant would go unseen.
     */
    private suspend fun JoinRig.orphanBesideAFreeClickOnAnotherAgentsSurface(session: Session): Pair<ClaimTarget, ClaimTarget> {
        val browser = browser()
        val zed = PeerIdentity.agent("zed", Random(9))
        browser.mutate { it.openSurface(iain, "theirs", meta(zed), validSurface(), JsonObject(emptyMap())) }
        repeat(2) { browser.mutate { it.appendRecord(iain, click(it, "theirs", "go")) } }
        val (free, orphan) = session.client.state.first { it.records("theirs").size == 2 }.records("theirs").map { BoardActionSource.target(it.first) }
        val won = claims.client(NodeId("zed")).claim(orphan, zed)
        check(won is ClaimOutcome.Won) { "rig: zed holds the second record: $won" }
        voter.depart(zed, DepartReason.Crashed)
        voter.table.first { it.status(orphan) is ClaimStatus.Orphaned }
        check(session.client.state.value.meta("theirs")?.openedBy == zed) { "rig: zed, not the session, opened the surface" }
        return orphan to free
    }

    /** The reserved surface-id prefixes a board-wide scope leaves out by name, as `BoardActionSource` lists them. */
    private val HANDLED_ELSEWHERE_PREFIXES = listOf(PairingSurfaces.PREFIX, OrphanDecision.PREFIX, CompanionSurfaces.PREFIX)

    private fun meta(openedBy: PeerIdentity) = SurfaceMeta("Not ada's", NOTIONS_CATALOG_ID, openedBy, kotlin.time.Instant.fromEpochMilliseconds(0))

    /** The surfaces of a board-wide list's records, in order. */
    private suspend fun Session.listSurfaces(): List<String> =
        call(WaitTool.LIST, emptyMap()).json().getValue("records").jsonArray.map { it.jsonObject.string("surfaceId") }

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

    /**
     * What a run of board-wide waits returned before one timed out, and the cursor it ended on.
     * [relisted] is every record a list from a returned record's own cursor read again, though a
     * wait had returned it by then: empty when each cursor names all it has passed.
     */
    private data class Drained(val ids: List<String>, val surfaces: List<String>, val cursor: String, val relisted: List<String>)

    /**
     * Board-wide waits from [cursor], each resuming from the last one's cursor, until one times out.
     * After each record, a list from that wait's cursor checks the cursor itself, since the
     * session's claims would pass over a record it already holds even if the cursor forgot it.
     */
    private suspend fun Session.drain(cursor: String?): Drained {
        val records = mutableListOf<JsonObject>()
        val relisted = mutableListOf<String>()
        var at = cursor
        while (true) {
            val waited = waitBoard(cursor = at, timeoutSeconds = 1)
            at = waited.cursor
            if (waited.timedOut) break
            records += waited.record()
            relisted += listBoard(since = waited.cursor).first.filter { id -> records.any { it.string("recordId") == id } }
        }
        return Drained(records.map { it.string("recordId") }, records.map { it.string("surfaceId") }, checkNotNull(at), relisted)
    }

    /** A board-wide list's record ids and cursor. */
    private suspend fun Session.listBoard(since: String?): Pair<List<String>, String> {
        val json = call(WaitTool.LIST, buildMap { since?.let { put("since", it) } }).json()
        return json.getValue("records").jsonArray.map { it.jsonObject.string("recordId") } to json.getValue("cursor").jsonPrimitive.content
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
