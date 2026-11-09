package us.tractat.bee.host

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import us.tractat.bee.core.DepartReason
import us.tractat.bee.core.PeerIdentity
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * [ClaimsVoter.close] returns only once the voter's last store write has (bee#151). A host's data
 * dir is free for the next host, or a test's cleanup, the moment `close` returns: a Raft append
 * still running after that moved `raft/log.tmp` onto a deleted directory and failed whichever
 * `runTest` came next.
 *
 * The store holds the voter's append the way a file write holds its thread: cancellation can't
 * interrupt it. The signal is whether `close` had returned while that write was still held, on
 * virtual time, so the interleaving is forced rather than raced.
 */
class ClaimsVoterCloseTest {
    private val alice = PeerIdentity.agent("alice", Random(1))

    @Test
    fun closeWaitsForAnAppendInFlight() = runTest(timeout = 60.seconds) {
        val store = HeldStore(InMemoryDurableStore())
        val rig = ClaimsRig(this, store)
        val voter = rig.startVoter()
        store.holdNextWrite()
        // The depart never commits once the voter is closed under it, so it runs in the background.
        backgroundScope.launch { voter.depart(alice, DepartReason.Quiet) }
        store.entered.await()

        val closing = launch { voter.close() }
        runCurrent()
        val closedWithWriteHeld = closing.isCompleted
        store.release()
        closing.join()

        assertAll(
            { assertFalse(closedWithWriteHeld, "close returned while the voter's append was still writing") },
            { assertTrue(store.finished.isCompleted, "the held append finished before close returned") },
        )
    }
}

/** A [DurableStore] that, once armed, holds its next write until [release], uncancellably. */
private class HeldStore(private val inner: DurableStore) : DurableStore by inner {
    private var armed = false
    val entered = CompletableDeferred<Unit>()
    private val gate = CompletableDeferred<Unit>()
    val finished = CompletableDeferred<Unit>()

    fun holdNextWrite() {
        armed = true
    }

    fun release() {
        gate.complete(Unit)
    }

    override suspend fun write(key: StoreKey, bytes: ByteArray) {
        if (!armed) return inner.write(key, bytes)
        armed = false
        entered.complete(Unit)
        withContext(NonCancellable) {
            gate.await()
            inner.write(key, bytes)
            finished.complete(Unit)
        }
    }
}
