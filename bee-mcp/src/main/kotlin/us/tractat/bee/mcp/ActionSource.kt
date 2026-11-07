package us.tractat.bee.mcp

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration

/**
 * Where `bee_wait_action` and `bee_list_actions` read a surface's action records from.
 *
 * Task 7 supplies the board-backed source, which claims each record before returning it. Task 5
 * only fixes the shape, so the tools and their bounds can be proved against a fake.
 */
interface ActionSource {
    /**
     * Holds until a record on [surfaceId] arrives after [cursor], or [timeout] passes.
     *
     * Returns within [timeout]: the bound belongs to the source, because only the source knows the
     * cursor to hand back when nothing came. A `null` [cursor] means the caller has seen nothing yet.
     */
    suspend fun next(surfaceId: String, cursor: String?, timeout: Duration): WaitResult

    /** The records on [surfaceId] after [since] (all of them when `null`), without blocking. */
    fun list(surfaceId: String, since: String?): List<JsonObject>
}

/** What one wait ended with: a [record] and the [cursor] after it, or [timedOut] and the cursor unchanged. */
data class WaitResult(val record: JsonObject?, val timedOut: Boolean, val cursor: String) {
    init {
        require((record == null) == timedOut) { "A wait either returns a record or times out: $this" }
    }
}

/**
 * The source the bridge runs with until Task 7 joins it to the board: no surface ever has a record,
 * so every wait times out at its bound and every list is empty.
 */
object NoActions : ActionSource {
    override suspend fun next(surfaceId: String, cursor: String?, timeout: Duration): WaitResult {
        delay(timeout)
        return WaitResult(record = null, timedOut = true, cursor = cursor ?: START)
    }

    override fun list(surfaceId: String, since: String?): List<JsonObject> = emptyList()

    /** The cursor of a caller that has seen nothing on the surface. */
    const val START = "0"
}
