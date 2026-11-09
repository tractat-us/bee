@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.bee.app

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.bee.host.BoardStar
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val AGENT = ReplicaId("agent-test")
private val APP = ReplicaId("app-test")
private val T0 = Instant.parse("2026-11-09T12:00:00Z")

class SurfaceTitlesTest {
    /**
     * The app's replica, joined to an in-memory host relay, lists what an agent opened there:
     * open surfaces only, in the order opened. The titles are chosen so that order is not the
     * alphabetical one, and a closed surface sits between two open ones.
     */
    @Test
    fun theAppListsTheHostsOpenSurfacesByTitleInTheOrderOpened() = runTest(StandardTestDispatcher()) {
        val star = BoardStar(this).apply { start() }
        val agent = star.client(AGENT)
        val app = star.client(APP)
        agent.connect()
        app.connect()
        runCurrent()
        agent.mutate { it.open(AGENT, "retro", "Retro", T0) }
        agent.mutate { it.open(AGENT, "lunch", "Lunch", T0 + 1.seconds) }
        agent.mutate { it.open(AGENT, "plan", "Plan", T0 + 2.seconds) }
        agent.mutate { it.closeSurface(AGENT, "lunch", T0 + 3.seconds) }
        advanceTimeBy(100.milliseconds)
        runCurrent()
        assertAll(
            { assertTrue(app.online.value, "precondition: the app is online") },
            { assertEquals(listOf("Retro", "Plan"), app.state.value.openSurfaceTitles()) },
        )
    }
}
