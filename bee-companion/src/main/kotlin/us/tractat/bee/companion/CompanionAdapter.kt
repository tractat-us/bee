package us.tractat.bee.companion

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import us.tractat.bee.core.BoardClient
import us.tractat.bee.core.BoardState
import us.tractat.bee.mcp.ActionSource
import us.tractat.bee.mcp.BeeBridge
import us.tractat.kuilt.crdt.ReplicaId
import java.nio.file.Path
import kotlin.time.Instant

/** Red-first stub (Task 8 step 1): the shape the tests drive, doing nothing yet. */
@Suppress("UnusedPrivateProperty", "unused")
class CompanionAdapter(
    private val screenDir: Path,
    private val stateDir: Path,
    frameTemplate: Path,
    private val client: BoardClient,
    private val author: ReplicaId,
    private val bridge: BeeBridge,
    private val actions: ActionSource,
    private val clock: () -> Instant,
    private val report: (JsonObject) -> Unit,
) {
    fun start(scope: CoroutineScope): Job = scope.launch { }

    companion object {
        const val SURFACE = "companion"
        const val CHOICE = "choice"

        fun screensShown(state: BoardState): Int = 0
    }
}
