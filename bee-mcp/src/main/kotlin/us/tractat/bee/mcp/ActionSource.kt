package us.tractat.bee.mcp

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration

/**
 * Where `bee_wait_action` and `bee_list_actions` read action records from: one surface's, or the
 * whole board's ([ActionScope]).
 *
 * Task 7 supplies the board-backed source, which claims each record before returning it. Task 5
 * only fixes the shape, so the tools and their bounds can be proved against a fake.
 *
 * Cursors are opaque, and only a source makes them. A caller passes back the cursor it was last
 * handed, or `null` when it has seen nothing yet, and never builds one itself.
 */
interface ActionSource {
    /**
     * Holds until a record in [scope] arrives after [cursor], or [timeout] passes.
     *
     * Returns within [timeout]: the bound belongs to the source, because only the source knows the
     * cursor to hand back when nothing came.
     */
    suspend fun next(scope: ActionScope, cursor: String?, timeout: Duration): WaitResult

    /** The records in [scope] after [since] (all of them when `null`), without blocking. */
    fun list(scope: ActionScope, since: String?): ActionPage
}

/** [ActionSource.next] on the one surface [surfaceId]. */
suspend fun ActionSource.next(surfaceId: String, cursor: String?, timeout: Duration): WaitResult =
    next(ActionScope.Surface(surfaceId), cursor, timeout)

/** [ActionSource.list] on the one surface [surfaceId]. */
fun ActionSource.list(surfaceId: String, since: String?): ActionPage = list(ActionScope.Surface(surfaceId), since)

/** Which records a wait or a list reads. */
sealed interface ActionScope {
    /** The records on surface [id] alone. */
    data class Surface(val id: String) : ActionScope

    /**
     * The records on every surface this session may act on: every surface but those a [Surface]
     * wait or list refuses, the host's pairing surfaces. It takes in surfaces opened after the
     * wait began.
     */
    data object Board : ActionScope
}

/**
 * What one wait ended with: a [record] and the [cursor] after it, or [timedOut] with no record.
 *
 * After a timeout, [cursor] is still a position to resume from. It may have moved past records the
 * caller could no longer claim (another session took them), so it need not equal the cursor passed in.
 */
data class WaitResult(val record: JsonObject?, val timedOut: Boolean, val cursor: String) {
    init {
        require((record == null) == timedOut) { "A wait either returns a record or times out: $this" }
    }
}

/** The records one `list` read, and the cursor to read on from. */
data class ActionPage(val records: List<JsonObject>, val cursor: String)

/**
 * The source the bridge runs with until Task 7 joins it to the board: no scope ever has a record,
 * so every wait times out at its bound and every list is empty.
 */
object NoActions : ActionSource {
    override suspend fun next(scope: ActionScope, cursor: String?, timeout: Duration): WaitResult {
        delay(timeout)
        return WaitResult(record = null, timedOut = true, cursor = cursor ?: START)
    }

    override fun list(scope: ActionScope, since: String?): ActionPage = ActionPage(emptyList(), since ?: START)

    /** The cursor of a caller that has seen nothing yet. */
    const val START = "0"
}
