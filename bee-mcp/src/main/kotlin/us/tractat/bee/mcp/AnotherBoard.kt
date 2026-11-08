package us.tractat.bee.mcp

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import us.tractat.bee.core.BoardId
import us.tractat.bee.core.BoardPin
import us.tractat.bee.core.askBoardId

private val logger = KotlinLogging.logger {}

/**
 * The host keeps [named] now, not the [pinned] board this replica belongs to. A wiped host, or one
 * whose `claims/` was moved aside, refuses the replica for good, so its process (the bridge, a
 * companion) ends rather than sit refused where the agent can't see why, and a restart joins
 * [named].
 */
data class AnotherBoard(val pinned: BoardId, val named: BoardId) {
    /** What the bridge says as it ends: `/mcp` shows it failed. */
    val message: String
        get() = "The host keeps board $named now, not $pinned, and refuses this bridge. bee-mcp is ending: restart it (/mcp) to join $named."
}

/**
 * Waits for a failed dial ([lastFailure]) after which [ask] names a board other than the one
 * [pin] holds, and returns the two. An unanswered [ask] (null) or the same board is not that: the
 * wait goes on.
 */
suspend fun awaitAnotherBoard(lastFailure: Flow<Throwable?>, pin: BoardPin, ask: suspend () -> BoardId?): AnotherBoard =
    lastFailure.filterNotNull()
        .mapNotNull { pin.board.value?.let { pinned -> ask()?.takeIf { it != pinned }?.let { named -> AnotherBoard(pinned, named) } } }
        .first()

/** The board the host at [host] names, or null if it can't be asked: [awaitAnotherBoard]'s ask. */
suspend fun HttpClient.boardNamedOrNull(host: String): BoardId? = try {
    askBoardId(host)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (@Suppress("TooGenericExceptionCaught") unanswered: Exception) {
    logger.debug(unanswered) { "The host didn't name its board." }
    null
}
