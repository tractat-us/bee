@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.bee.core

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import us.tractat.bee.core.BoardFixtures.A
import us.tractat.bee.core.BoardFixtures.SURFACE
import us.tractat.bee.core.BoardFixtures.opened
import us.tractat.bee.core.BoardFixtures.str
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val CONFIG = QuilterConfig(antiEntropyInterval = 1.seconds, evictionAfter = 30.seconds, expectVirtualTime = true)

/** The bound the rigs below hang up over, and the client is told; far below any real one. */
private const val BOUND = 2_048

/**
 * A relay that hangs up on a spoke the moment the spoke sends it a payload over [hangUpOver]
 * bytes, as the host does with 1009 on a frame over its bound, and counts what it saw. Its spokes
 * are woven from [mesh], whose host seam the test opens.
 */
private class HangUpRelay(val mesh: Loom, private val now: () -> Long, var hangUpOver: Int) : Loom {
    /** The virtual time of each dial. */
    val dials = mutableListOf<Long>()

    /** Payloads over [hangUpOver] that reached the relay. */
    var oversize = 0

    /** The last spoke woven. */
    var current: Seam? = null

    override suspend fun weave(rendezvous: Rendezvous): Seam {
        dials += now()
        return Spoke(mesh.weave(rendezvous)).also { current = it }
    }

    private inner class Spoke(private val inner: Seam) : Seam by inner {
        override suspend fun broadcast(payload: ByteArray) = refuseOr(payload) { inner.broadcast(payload) }

        override suspend fun sendTo(peer: PeerId, payload: ByteArray) = refuseOr(payload) { inner.sendTo(peer, payload) }

        private suspend fun refuseOr(payload: ByteArray, send: suspend () -> Unit) {
            if (payload.size <= hangUpOver) return send()
            oversize++
            inner.close()
        }
    }
}

/** A board whose first-contact `FullState` is well over [BOUND]. */
private fun overBound(): BoardState = opened(buildJsonObject { put("note", "x".repeat(4 * BOUND)) })

private fun TestScope.client(loom: Loom, initial: BoardState, maxPayloadBytes: Int = BOUND, settleAfter: Duration = 5.seconds) = BoardClient(
    A, loom, Rendezvous.Existing(InMemoryTag("board")), backgroundScope, { Instant.fromEpochMilliseconds(testScheduler.currentTime) },
    initial, CONFIG, Random(0), maxPayloadBytes = maxPayloadBytes, settleAfter = settleAfter,
)

private fun TestScope.relay(hangUpOver: Int): HangUpRelay {
    val mesh = InMemoryLoom()
    return HangUpRelay(mesh, { testScheduler.currentTime }, hangUpOver)
}

/** bee#65: a board over the link's frame bound, and links that die before they are any use. */
class BoardClientFrameBoundTest {
    /**
     * A board whose `FullState` is over the bound is never sent, the client says why, and it
     * doesn't redial: on main the relay's hang-up reads as a tear, the dial resets the backoff, and
     * the client redials and resends every few hundred milliseconds, for good.
     */
    @Test
    fun aBoardOverTheFrameBoundIsNeverSentAndTheClientSaysWhy() = runTest(StandardTestDispatcher()) {
        val relay = relay(hangUpOver = BOUND)
        relay.openHost()
        val alex = client(relay, overBound())
        alex.connect()
        advanceTimeBy(30.seconds)
        runCurrent()
        val refusal = alex.tooLarge.value
        assertAll(
            { assertEquals(0, relay.oversize, "no frame over the bound reached the relay") },
            { assertEquals(1, relay.dials.size, "no redial loop: dials at ${relay.dials}") },
            { assertNotNull(refusal, "the client says it can't sync") },
            { assertTrue(refusal!!.bytes > BOUND, "the refusal names the frame's size: ${refusal.bytes}") },
            { assertEquals(BOUND, refusal!!.limit) },
            { assertFalse(alex.online.value, "a board that can't sync is offline") },
        )
    }

    /** A board too large to sync tries again when it changes, once, and still sends nothing over the bound. */
    @Test
    fun aBoardTooLargeToSyncTriesAgainOnlyWhenItChanges() = runTest(StandardTestDispatcher()) {
        val relay = relay(hangUpOver = BOUND)
        relay.openHost()
        val alex = client(relay, overBound())
        alex.connect()
        advanceTimeBy(30.seconds)
        runCurrent()
        alex.mutate { it.updateData(A, SURFACE, "/other", str("offline")) }
        advanceTimeBy(30.seconds)
        runCurrent()
        assertAll(
            { assertEquals(0, relay.oversize, "no frame over the bound reached the relay") },
            { assertEquals(2, relay.dials.size, "one more dial, for the change: dials at ${relay.dials}") },
            { assertNotNull(alex.tooLarge.value, "still too large") },
        )
    }

    /**
     * A relay that hangs up on every spoke before it is any use, for a reason the client can't
     * see, never lets the backoff reset. Full jitter from 250 ms to 5 s gives some 15 dials in 30 s;
     * resetting on every dial, as main does, gives one about every 125 ms, some 240.
     */
    @Test
    fun aLinkThatDiesBeforeItSettlesDoesNotResetTheBackoff() = runTest(StandardTestDispatcher()) {
        val relay = relay(hangUpOver = -1)
        relay.openHost()
        val alex = client(relay, opened(), maxPayloadBytes = BOARD_PAYLOAD_BYTES)
        alex.connect()
        advanceTimeBy(30.seconds)
        runCurrent()
        assertAll(
            { assertTrue(relay.dials.size in 2..25, "the backoff grew: ${relay.dials.size} dials at ${relay.dials}") },
            { assertNull(alex.tooLarge.value, "a hang-up the client can't explain isn't a board too large") },
        )
    }

    /**
     * The positive control: a link that lives past [BoardClient]'s settle time resets the backoff,
     * so its tear is redialled within the first step's 250 ms, after early hang-ups pushed it to 5 s.
     */
    @Test
    fun aLinkThatSettlesResetsTheBackoff() = runTest(StandardTestDispatcher()) {
        val relay = relay(hangUpOver = -1)
        relay.openHost()
        val alex = client(relay, opened(), maxPayloadBytes = BOARD_PAYLOAD_BYTES, settleAfter = 5.seconds)
        alex.connect()
        advanceTimeBy(20.seconds)
        runCurrent()
        val earlyDials = relay.dials.size
        relay.hangUpOver = Int.MAX_VALUE
        // The next dial, at most the 5 s cap away, stays up.
        advanceTimeBy(5.seconds)
        runCurrent()
        val settled = assertNotNull(relay.current)
        assertTrue(alex.online.value, "precondition: the link after the hang-ups is up")
        advanceTimeBy(10.seconds)
        runCurrent()
        val tornAt = testScheduler.currentTime
        settled.close()
        advanceTimeBy(250.milliseconds)
        runCurrent()
        assertAll(
            { assertTrue(earlyDials >= 4, "precondition: the hang-ups grew the backoff: $earlyDials dials") },
            { assertTrue(relay.dials.last() in tornAt..tornAt + 250, "redialled within 250 ms of $tornAt: ${relay.dials}") },
            { assertTrue(alex.online.value, "the redial connected") },
        )
    }
}

/** Opens the relay's hub, so a spoke has a peer to send its first-contact `FullState` to. */
private suspend fun HangUpRelay.openHost() {
    mesh.weave(Rendezvous.New(Pattern("board")))
}
