package us.tractat.bee.core

import us.tractat.bee.core.BoardFixtures.A
import us.tractat.bee.core.BoardFixtures.B
import us.tractat.bee.core.BoardFixtures.SURFACE
import us.tractat.bee.core.BoardFixtures.opened
import us.tractat.bee.core.BoardFixtures.str
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [holds]: what makes a [BoardClient] synced. A board noted from the relay's `FullState` but not
 * yet merged into the client's board isn't held, so the client isn't synced until the merge lands,
 * whatever order kuilt runs the two in (bee#240).
 */
class BoardHoldsTest {
    private val relay = opened().piece { it.updateData(B, SURFACE, "/note", str("from the host")) }

    @Test
    fun aBoardHoldsWhatItHasMergedAndNothingMore() = assertAll(
        { assertFalse(BoardState.EMPTY.holds(relay), "a fresh client's empty board doesn't hold the relay's") },
        { assertFalse(opened().holds(relay), "nor does one missing a write the relay has") },
        { assertTrue(BoardState.EMPTY.piece(relay).holds(relay), "merged, it does") },
        { assertTrue(relay.holds(relay), "a board holds itself") },
        { assertTrue(relay.holds(BoardState.EMPTY), "an empty relay board is held by any board") },
        {
            val ahead = relay.piece { it.updateData(A, SURFACE, "/mine", str("local")) }
            assertTrue(ahead.holds(relay) && !relay.holds(ahead), "a board with writes of its own still holds the relay's")
        },
    )
}
