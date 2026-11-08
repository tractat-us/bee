package us.tractat.bee.host

import kotlinx.coroutines.test.runTest
import us.tractat.bee.core.BoardId
import us.tractat.kuilt.store.DurableStore
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
        assertAll(
            { assertEquals(id, BoardId.keptIn(board)) },
            { assertEquals(id, BoardId.keptIn(claims)) },
        )
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
        assertAll(
            { assertNotEquals(first, second) },
            { assertEquals(second, BoardId.keptIn(board), "the board store now keeps the new board") },
            { assertEquals(second, HostStores.settle(board, freshLog).boardId, "and keeps it after") },
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
        assertAll(
            { assertNotEquals(first, second) },
            { assertEquals(listOf(second, second), listOf(board, claims).map { keptIn(it) }) },
        )
    }

    private suspend fun keptIn(store: DurableStore) = BoardId.keptIn(store)
}
