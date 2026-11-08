@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.bee.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class LivePresenceTest {
    @Test
    fun peersShowsAPersonOnTwoTabsOnceByTheirNewestEntry() = runTest {
        val board = MutableStateFlow(
            BoardState.EMPTY
                .piece { it.postPresence(OLD_TAB, entry(iain, at = 1), 1) }
                .piece { it.postPresence(NEW_TAB, entry(iain, at = 2), 1) }
                .piece { it.postPresence(BRIDGE, entry(alice, at = 1), 1) },
        )
        val roster = LivePresence(board, { NOW }, backgroundScope) { throw it }
        runCurrent()
        assertAll(
            { assertEquals(3, roster.live().size) },
            { assertEquals(mapOf(iain to entry(iain, at = 2), alice to entry(alice, at = 1)), roster.peers()) },
        )
    }

    private companion object {
        val OLD_TAB = ReplicaId("web-old")
        val NEW_TAB = ReplicaId("web-new")
        val BRIDGE = ReplicaId("mcp-alice")
        val iain = PeerIdentity.human("iain")
        val alice = PeerIdentity.agent("alice", Random(1))
        val NOW: Instant = Instant.fromEpochSeconds(10)

        fun entry(who: PeerIdentity, at: Long) = PresenceEntry(who, claimsNode = null, status = null, lastActedAt = Instant.fromEpochSeconds(at), waiting = false)
    }
}
