package us.tractat.bee.host

import kotlinx.coroutines.test.runTest
import us.tractat.bee.core.BoardId
import us.tractat.kuilt.raft.DurableStoreRaftStorage
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** One board is one snapshot store and one claims log together ([HostStores.settle], bee#46). */
class HostStoresTest {
    private val board = InMemoryDurableStore()
    private val claims = InMemoryDurableStore()

    @Test
    fun aFirstBootRecordsItsNewBoardInBothStores() = runTest {
        val id = HostStores.settle(board, claims).boardId
        assertEquals(listOf(id, id), listOf(BoardId.keptIn(board), BoardId.keptIn(claims)))
    }

    @Test
    fun aRebootOnTheSameStoresKeepsTheBoard() = runTest {
        val first = HostStores.settle(board, claims).boardId
        assertEquals(first, HostStores.settle(board, claims).boardId)
    }

    /** `claims/` moved aside: a new claims log, which would restart its indexes under the old mirror. */
    @Test
    fun aNewClaimsLogIsANewBoard() = runTest {
        val first = HostStores.settle(board, claims).boardId
        val freshLog = InMemoryDurableStore()
        val second = HostStores.settle(board, freshLog).boardId
        val boardKeeps = BoardId.keptIn(board)
        val after = HostStores.settle(board, freshLog).boardId
        assertAll(
            { assertNotEquals(first, second) },
            { assertEquals(second, boardKeeps, "the board store now keeps the new board") },
            { assertEquals(second, after, "and keeps it after") },
        )
    }

    @Test
    fun aNewBoardStoreIsANewBoard() = runTest {
        val first = HostStores.settle(board, claims).boardId
        assertNotEquals(first, HostStores.settle(InMemoryDurableStore(), claims).boardId)
    }

    /** As a crash between the two writes of a new id leaves them. */
    @Test
    fun storesThatDisagreeAreANewBoard() = runTest {
        val first = HostStores.settle(board, claims).boardId
        BoardId.recordIn(claims, BoardId.mint())
        val second = HostStores.settle(board, claims).boardId
        val kept = listOf(BoardId.keptIn(board), BoardId.keptIn(claims))
        assertAll(
            { assertNotEquals(first, second) },
            { assertEquals(listOf(second, second), kept) },
        )
    }

    /** Data from before bee#46: `board/` holds an id, `claims/` a Raft log (its `raft/meta`) with none. */
    @Test
    fun aClaimsLogFromBeforeIdsAdoptsTheBoardsId() = runTest {
        val old = BoardId.mint()
        BoardId.recordIn(board, old)
        claims.write(DurableStoreRaftStorage.META_KEY, byteArrayOf(1))
        val settled = HostStores.settle(board, claims).boardId
        assertAll(
            { assertEquals(old, settled) },
            { assertEquals(old, BoardId.keptIn(claims), "claims now records it") },
            { assertEquals(old, BoardId.keptIn(board)) },
        )
    }

    /** A claims log that was moved aside is empty: no `raft/meta`, so the board mints even with an id in `board/`. */
    @Test
    fun anEmptyClaimsLogDoesNotAdopt() = runTest {
        val old = BoardId.mint()
        BoardId.recordIn(board, old)
        assertNotEquals(old, HostStores.settle(board, claims).boardId)
    }
}
