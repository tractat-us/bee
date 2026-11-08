package us.tractat.bee.mcp

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import us.tractat.bee.core.BoardId
import us.tractat.bee.core.BoardPin

/**
 * The host keeps [named] now, not the [pinned] board this bridge's replica belongs to. A wiped
 * host, or one whose `claims/` was moved aside, refuses the bridge for good, so the bridge ends
 * rather than sit refused where the agent can't see why: `/mcp` shows it failed, and a restart
 * joins [named].
 */
internal data class AnotherBoard(val pinned: BoardId, val named: BoardId) {
    val message: String
        get() = "The host keeps board $named now, not $pinned, and refuses this bridge. bee-mcp is ending: restart it (/mcp) to join $named."
}

/**
 * Waits for a failed dial ([lastFailure]) after which [ask] names a board other than the one
 * [pin] holds, and returns the two. An unanswered [ask] (null) or the same board is not that: the
 * wait goes on.
 */
internal suspend fun awaitAnotherBoard(lastFailure: Flow<Throwable?>, pin: BoardPin, ask: suspend () -> BoardId?): AnotherBoard =
    lastFailure.filterNotNull()
        .mapNotNull { pin.board.value?.let { pinned -> ask()?.takeIf { it != pinned }?.let { named -> AnotherBoard(pinned, named) } } }
        .first()
