package us.tractat.bee.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.bee.core.BoardFixtures.A
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.test.assertAll
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * [Presence.waiting]: the peer shows waiting while any wait is in progress, and however a wait
 * ends, its count is given back (bee#165). Each test ends with a plain [Presence.update], whose
 * post reads the count afresh: a leaked count shows there as `waiting = true` with no wait left.
 */
class PresenceWaitingTest {
    /**
     * A wait whose first post throws, as one cancelled on a contended lock does, still gives its
     * count back. The clock throws inside that post, under Presence's own lock, which is where a
     * cancelled `Mutex.lock` or `BoardClient.mutate` throws from.
     */
    @Test
    fun aWaitCancelledAtItsFirstPostLeavesNobodyWaiting() = runTest {
        val rig = Rig(this)
        rig.failNextClockRead = CancellationException("cancelled at the wait's first post")
        val thrown = assertFailsWith<CancellationException> { rig.presence.waiting(ALEX, null, rig.now()) { error("rig: the block never runs") } }
        rig.presence.update(ALEX, "after", rig.now())
        assertAll(
            { assertEquals("cancelled at the wait's first post", thrown.message, "rig: the first post threw") },
            { assertEquals(false, rig.posted().waiting, "a wait that never got past its first post leaves the peer not waiting") },
        )
    }

    /** A wait whose own cleanup rethrows a cancellation, as a release on a torn claims link does, still gives its count back. */
    @Test
    fun aWaitWhoseCleanupRethrowsACancellationLeavesNobodyWaiting() = runTest {
        val rig = Rig(this)
        var shownWaiting = false
        assertFailsWith<CancellationException> {
            rig.presence.waiting(ALEX, null, rig.now()) {
                shownWaiting = rig.posted().waiting
                throw CancellationException("the release was torn")
            }
        }
        rig.presence.update(ALEX, "after", rig.now())
        assertAll(
            { assertTrue(shownWaiting, "rig: the wait showed waiting while it ran") },
            { assertEquals(false, rig.posted().waiting, "the ended wait leaves the peer not waiting") },
        )
    }

    /**
     * Two waits overlap, as an HTTP session's calls do. When one ends, the peer still shows
     * waiting through the other, and every post meanwhile says so; when the other ends, it
     * doesn't. Each post reads the count under the lock that orders posts, so no writer holds a
     * reading it took earlier: the interleaving where one did (a stale `false`) no longer exists.
     */
    @Test
    fun aWaitThatEndsLeavesAnOverlappingWaitShowing() = runTest {
        val rig = Rig(this)
        val endFirst = CompletableDeferred<Unit>()
        val endSecond = CompletableDeferred<Unit>()
        val first = async { rig.presence.waiting(ALEX, null, rig.now()) { endFirst.await() } }
        val second = async { rig.presence.waiting(ALEX, null, rig.now()) { endSecond.await() } }
        runCurrent()
        endFirst.complete(Unit)
        first.await()
        val afterFirst = rig.posted().waiting
        rig.presence.update(ALEX, "a write mid-wait", rig.now())
        val afterWrite = rig.posted().waiting
        endSecond.complete(Unit)
        second.await()
        assertAll(
            { assertTrue(afterFirst, "one wait ending leaves the other showing") },
            { assertTrue(afterWrite, "a write while the other wait holds still shows waiting") },
            { assertEquals(false, rig.posted().waiting, "both ended: not waiting") },
        )
    }

    private class Rig(test: TestScope) {
        var failNextClockRead: Throwable? = null
        private val time = test.testScheduler
        fun now(): Instant = Instant.fromEpochMilliseconds(time.currentTime)
        private val presenceClock: () -> Instant = { failNextClockRead?.let { failNextClockRead = null; throw it } ?: now() }
        val client = BoardClient(A, InMemoryLoom(), Rendezvous.Existing(InMemoryTag("board")), test.backgroundScope, ::now)
        val presence = Presence(client, A, emptyFlow(), presenceClock, test.backgroundScope) { throw it }

        /** This peer's entry as last posted. */
        fun posted(): PresenceEntry = client.state.value.latestPresence().single()
    }

    private companion object {
        val ALEX = PeerIdentity.human("alex")
    }
}
