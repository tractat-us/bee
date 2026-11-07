package us.tractat.bee.mcp

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration

/**
 * Where `bee_wait_action` and `bee_list_actions` read a surface's action records from.
 *
 * Task 7 supplies the board-backed source, which claims each record before returning it. Task 5
 * only fixes the shape, so the tools and their bounds can be proved against a fake.
 *
 * Cursors are opaque, and only a source makes them. A caller passes back the cursor it was last
 * handed, or `null` when it has seen nothing yet, and never builds one itself.
 */
interface ActionSource {
    /**
     * Holds until a record on [surfaceId] arrives after [cursor], or [timeout] passes.
     *
     * Returns within [timeout]: the bound belongs to the source, because only the source knows the
     * cursor to hand back when nothing came.
     */
    suspend fun next(surfaceId: String, cursor: String?, timeout: Duration): WaitResult

    /** The records on [surfaceId] after [since] (all of them when `null`), without blocking. */
    fun list(surfaceId: String, since: String?): ActionPage
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
 * The source the bridge runs with until Task 7 joins it to the board: no surface ever has a record,
 * so every wait times out at its bound and every list is empty.
 */
object NoActions : ActionSource {
    override suspend fun next(surfaceId: String, cursor: String?, timeout: Duration): WaitResult {
        delay(timeout)
        return WaitResult(record = null, timedOut = true, cursor = cursor ?: START)
    }

    override fun list(surfaceId: String, since: String?): ActionPage = ActionPage(emptyList(), since ?: START)

    /** The cursor of a caller that has seen nothing on the surface. */
    const val START = "0"
}
