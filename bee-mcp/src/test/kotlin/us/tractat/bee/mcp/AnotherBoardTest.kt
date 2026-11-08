package us.tractat.bee.mcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import us.tractat.bee.core.BoardId
import us.tractat.bee.core.BoardPin
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A bridge pinned to one board ends when its host keeps another (bee#46). */
class AnotherBoardTest {
    private val a = BoardId("board-a")
    private val b = BoardId("board-b")

    @Test
    fun aBridgePinnedToAThatLearnsTheHostKeepsBEndsNamingBoth() = runTest {
        val failure = MutableStateFlow<Throwable?>(null)
        val ended = awaitAnotherBoardAfter(failure, BoardPin.to(a)) { b }
        assertAll(
            { assertEquals(AnotherBoard(a, b), ended) },
            { assertTrue(ended.message.contains("board-a") && ended.message.contains("board-b")) },
        )
    }

    @Test
    fun aHostThatKeepsTheSameBoardOrCantBeAskedDoesNotEndIt() = runTest {
        val failure = MutableStateFlow<Throwable?>(null)
        val answers = ArrayDeque<BoardId?>(listOf(a, null, b))
        val ended = awaitAnotherBoardAfter(failure, BoardPin.to(a)) { answers.removeFirst() }
        assertEquals(AnotherBoard(a, b), ended)
    }

    /** Fails the dial three times, then waits for the verdict. */
    private suspend fun kotlinx.coroutines.CoroutineScope.awaitAnotherBoardAfter(
        failure: MutableStateFlow<Throwable?>,
        pin: BoardPin,
        ask: suspend () -> BoardId?,
    ): AnotherBoard {
        val verdict = kotlinx.coroutines.CompletableDeferred<AnotherBoard>()
        launch { verdict.complete(awaitAnotherBoard(failure, pin, ask)) }
        repeat(3) {
            if (!verdict.isCompleted) {
                failure.value = IllegalStateException("refused $it")
                kotlinx.coroutines.yield()
            }
        }
        return verdict.await()
    }
}
