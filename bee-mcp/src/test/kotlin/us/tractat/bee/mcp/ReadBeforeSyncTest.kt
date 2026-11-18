package us.tractat.bee.mcp

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import us.tractat.bee.a2ui.NOTIONS_CATALOG_ID
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.SurfaceMeta
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A read the moment a session connects is of the board the host holds, or says it isn't
 * (bee#240): `bee_board` and `bee_list_actions` wait up to [SYNC_WAIT] for the replica's first sync
 * ([BeeBridge.awaitSynced]), and answer `"synced": false` only when it hasn't come. The wait runs
 * on the test's virtual clock.
 */
class ReadBeforeSyncTest {
    @Test
    fun aReadOnAReplicaThatHasntSyncedWaitsItsBoundThenSaysSo() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada", online = false)
        val before = testScheduler.currentTime
        val board = ada.call(BeeTools.BOARD, emptyMap()).json()
        val waited = (testScheduler.currentTime - before).milliseconds
        val listed = ada.call(WaitTool.LIST, emptyMap()).json()
        assertAll(
            { assertEquals(JsonPrimitive(false), board["synced"], "bee_board says it hasn't synced: $board") },
            { assertTrue(waited >= SYNC_WAIT, "it waited its bound first: $waited") },
            { assertEquals(JsonPrimitive(false), listed["synced"], "bee_list_actions says so too: $listed") },
        )
        rig.close()
    }

    /** A session whose replica has synced reads the host's board and says nothing of syncing. */
    @Test
    fun aSyncedReadShowsTheBoardAndSaysNothingOfSyncing() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val browser = rig.browser()
        val meta = SurfaceMeta("Lunch", NOTIONS_CATALOG_ID, PeerIdentity.human("iain"), Instant.parse("2026-11-17T12:00:00Z"))
        browser.mutate { it.openSurface(ReplicaId("web-iain"), "lunch", meta, validSurface(), JsonObject(emptyMap())) }
        rig.star.relay.state.first { "lunch" in it.openSurfaces() }
        val bo = rig.session("bo")
        val board = bo.call(BeeTools.BOARD, emptyMap()).json()
        val listed = bo.call(WaitTool.LIST, emptyMap()).json()
        val open = board.getValue("open").jsonArray.map { it.jsonObject.getValue("surfaceId").jsonPrimitive.content }
        assertAll(
            { assertEquals(listOf("lunch"), open, "the host's surface is on it") },
            { assertNull(board["synced"], "nothing to say: $board") },
            { assertNull(listed["synced"], "nor here: $listed") },
        )
        rig.close()
    }
}
