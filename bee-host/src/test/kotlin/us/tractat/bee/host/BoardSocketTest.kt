package us.tractat.bee.host

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import us.tractat.bee.core.BoardId
import us.tractat.bee.host.Handshakes.Companion.FORBIDDEN
import us.tractat.bee.host.Handshakes.Companion.SWITCHING
import us.tractat.kuilt.test.assertAll
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A socket names the board it expects, and the host refuses any other before the upgrade (bee#46),
 * on a real host from loopback, so neither the key gate nor the site gate is in the way.
 */
class BoardSocketTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dataDir = Files.createTempDirectory("w45-board-socket-")
    private val host = runBlocking { BeeHost.start(scope, HostConfig(port = 0, dataDir = dataDir)) }
    private val calls = Handshakes(host.port)

    @AfterTest
    fun stop() {
        host.close()
        scope.cancel()
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun aSocketNamingAnotherBoardIsRefused() {
        val stale = BoardId.mint()
        assertAll(
            { assertEquals(FORBIDDEN, calls.upgrade(board("&${BoardId.PARAM}=$stale")), "a board from before a wipe") },
            { assertEquals(FORBIDDEN, calls.upgrade(claims("&${BoardId.PARAM}=$stale")), "on claims too") },
            { assertEquals(FORBIDDEN, calls.upgrade(board("&${BoardId.PARAM}=")), "an empty id") },
        )
    }

    /** A client that names no board could be one from before this gate, holding any board: it is refused too. */
    @Test
    fun aSocketNamingNoBoardIsRefused() {
        assertAll(
            { assertEquals(FORBIDDEN, calls.upgrade(board(""))) },
            { assertEquals(FORBIDDEN, calls.upgrade(claims(""))) },
        )
    }

    @Test
    fun aSocketNamingThisBoardIsAdmitted() {
        assertAll(
            { assertEquals(SWITCHING, calls.upgrade(board("&${BoardId.PARAM}=${host.boardId}"))) },
            { assertEquals(SWITCHING, calls.upgrade(claims("&${BoardId.PARAM}=${host.boardId}"))) },
        )
    }

    // kuilt's server looms read the dialler's fabric id from `peer`.
    private fun board(more: String) = "${BoardRelay.PATH}?peer=probe$more"

    private fun claims(more: String) = "${BeeHost.CLAIMS_PATH}?peer=probe$more"
}
