@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.bee.core

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.bee.core.BoardFixtures.A
import us.tractat.bee.core.BoardFixtures.SURFACE
import us.tractat.bee.core.BoardFixtures.opened
import us.tractat.bee.core.BoardFixtures.str
import us.tractat.bee.core.BoardFixtures.values
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val CONFIG = QuilterConfig(antiEntropyInterval = 1.seconds, evictionAfter = 30.seconds, expectVirtualTime = true)

/** Fails its first [failures] weaves, then weaves from [delegate]. */
private class FailingLoom(private val delegate: Loom, private var failures: Int) : Loom {
    override suspend fun weave(rendezvous: Rendezvous): Seam {
        if (failures > 0) {
            failures--
            error("dial refused")
        }
        return delegate.weave(rendezvous)
    }
}

class BoardClientLifecycleTest {
    /**
     * The identity-guarded mirror, as defence in depth. In the client as built, a late mirror
     * can't run: parting a connection cancels its job under the same mutex, `Mutex.lock` cancels
     * promptly, and `StateFlow.collect` checks for cancellation. So this drives the mirror body
     * directly, with the parted connection, which is what a late body would do if that ever
     * changed. Without the `live === connection` check, it reds.
     */
    @Test
    fun lateEmissionFromAPartedQuilterNeverOverwritesTheHeldState() = runTest(StandardTestDispatcher()) {
        val loom = InMemoryLoom()
        loom.weave(Rendezvous.New(Pattern("board")))
        val clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
        val alex = BoardClient(A, loom, Rendezvous.Existing(InMemoryTag("board")), backgroundScope, clock, opened(), CONFIG, Random(0))
        alex.connect()
        runCurrent()
        val stale = assertNotNull(alex.liveConnection(), "precondition: alex is connected")
        alex.disconnect()
        runCurrent()
        alex.mutate { it.updateData(A, SURFACE, "/note", str("offline")) }
        assertNull(alex.liveConnection(), "precondition: the connection parted")
        alex.mirror(stale)
        assertEquals(setOf(str("offline")), values(alex.state.value, "/note"), "a late mirror must not drop the offline edit")
    }

    /** A refused dial is state, not silence; the redial that works clears it. */
    @Test
    fun aFailedDialIsReportedAndClearedByTheNextConnection() = runTest(StandardTestDispatcher()) {
        val mesh = InMemoryLoom()
        mesh.weave(Rendezvous.New(Pattern("board")))
        val clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
        val alex = BoardClient(A, FailingLoom(mesh, failures = 1), Rendezvous.Existing(InMemoryTag("board")), backgroundScope, clock, config = CONFIG, random = Random(0))
        alex.connect()
        runCurrent()
        val afterRefusal = alex.lastFailure.value
        val onlineAfterRefusal = alex.online.value
        advanceTimeBy(1.seconds)
        runCurrent()
        assertAll(
            { assertEquals("dial refused", afterRefusal?.message) },
            { assertFalse(onlineAfterRefusal) },
            { assertTrue(alex.online.value, "the backoff redialled") },
            { assertNull(alex.lastFailure.value, "a live connection clears the failure") },
        )
    }

    /**
     * A dial that never answers (a socket accepted and never upgraded) is cancelled at the dial
     * timeout and recorded, and the backoff redials. The hung first dial suspends until cancelled,
     * so without the bound the client stays offline with no failure, for good.
     */
    @Test
    fun aDialThatNeverAnswersTimesOutAndRedials() = runTest(StandardTestDispatcher()) {
        val mesh = InMemoryLoom()
        mesh.weave(Rendezvous.New(Pattern("board")))
        val clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
        var dials = 0
        var hungDialCancelled = false
        val loom = SpokeLoom(backgroundScope, "alex", Random(0), clock) { _, rendezvous ->
            if (dials++ == 0) {
                try {
                    awaitCancellation()
                } finally {
                    hungDialCancelled = true
                }
            }
            mesh.weave(rendezvous)
        }
        val alex = BoardClient(
            A, loom, Rendezvous.Existing(InMemoryTag("board")), backgroundScope, clock,
            config = CONFIG, random = Random(0), dialTimeout = 5.seconds,
        )
        alex.connect()
        advanceTimeBy(4_999.milliseconds)
        runCurrent()
        val failureBeforeBound = alex.lastFailure.value
        advanceTimeBy(1.milliseconds)
        runCurrent()
        val failureAtBound = alex.lastFailure.value
        val onlineAtBound = alex.online.value
        val cancelledAtBound = hungDialCancelled
        advanceTimeBy(1.seconds)
        runCurrent()
        assertAll(
            { assertNull(failureBeforeBound, "precondition: nothing fails inside the bound") },
            { assertIs<DialTimeoutException>(failureAtBound, "the hung dial is recorded at the bound") },
            { assertFalse(onlineAtBound, "precondition: a timed-out dial is not a connection") },
            { assertTrue(cancelledAtBound, "the hung dial is cancelled, not left running") },
            { assertEquals(2, dials, "the backoff redialled once") },
            { assertTrue(alex.online.value, "the redial connected") },
            { assertNull(alex.lastFailure.value, "a live connection clears the failure") },
        )
    }
}
