@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.bee.host

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import us.tractat.bee.a2ui.NOTIONS_CATALOG_ID
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.SurfaceMeta
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val SAM = ReplicaId("web-sam")
private val KEPT_FOR = 1.hours

private fun TestScope.settle(duration: Duration) {
    advanceTimeBy(duration)
    runCurrent()
}

/**
 * bee#274 over the in-memory relay, under the scheduler's clock: the host removes a surface closed
 * for its grace period, and the removal reaches a page that held it.
 */
class ClosedSurfacesTest {
    @Test
    fun aSurfaceClosedForItsGracePeriodLeavesTheHostAndThePage() = runTest(StandardTestDispatcher()) {
        val star = BoardStar(this).apply { start() }
        val sam = star.client(SAM).apply { connect() }
        settle(1.seconds)
        sam.mutate { it.openSurface(SAM, "old", meta(star), listOf(root), JsonObject(emptyMap())) }
        sam.mutate { it.openSurface(SAM, "kept", meta(star), listOf(root), JsonObject(emptyMap())) }
        sam.mutate { it.closeSurface(SAM, "old", star.clock()) }
        settle(1.seconds)
        ClosedSurfaces(star.relay, KEPT_FOR, star.clock).start(backgroundScope)

        settle(KEPT_FOR - 5.minutes)
        val inGrace = star.relay.state.value
        settle(5.minutes + KEPT_FOR / 4 + 2.seconds)
        assertAll(
            { assertEquals(listOf("old"), inGrace.closedSurfaces(), "inside its grace period the surface stays") },
            { assertEquals(setOf("kept"), star.relay.state.value.surfaces.keys, "the host removed it") },
            { assertEquals(setOf("kept"), sam.state.value.surfaces.keys, "the page took the removal") },
            { assertEquals(listOf("kept"), sam.state.value.openSurfaces()) },
        )
    }

    @Test
    fun aSurfaceReopenedInsideItsGracePeriodStays() = runTest(StandardTestDispatcher()) {
        val star = BoardStar(this).apply { start() }
        val sam = star.client(SAM).apply { connect() }
        settle(1.seconds)
        sam.mutate { it.openSurface(SAM, "old", meta(star), listOf(root), JsonObject(emptyMap())) }
        sam.mutate { it.closeSurface(SAM, "old", star.clock()) }
        ClosedSurfaces(star.relay, KEPT_FOR, star.clock).start(backgroundScope)
        settle(KEPT_FOR / 2)
        sam.mutate { it.reopenSurface(SAM, "old") }
        settle(KEPT_FOR * 3)
        assertEquals(listOf("old"), sam.state.value.openSurfaces())
    }

    @Test
    fun aClosedSurfaceIsKeptForADayByDefault() {
        assertAll(
            { assertEquals(1.days, ClosedSurfaces.KEPT_FOR) },
            { assertEquals(1.days, HostConfig(dataDir = kotlin.io.path.Path("unused")).closedKeptFor) },
        )
    }

    private fun meta(star: BoardStar) = SurfaceMeta("Notes", NOTIONS_CATALOG_ID, PeerIdentity.human("sam"), star.clock())

    private val root: JsonObject = buildJsonObject {
        put("id", "root")
        put("component", "Text")
        put("text", "Notes")
    }
}
