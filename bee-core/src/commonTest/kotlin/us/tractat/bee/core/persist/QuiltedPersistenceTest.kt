@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.bee.core.persist

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import us.tractat.bee.core.BoardFixtures
import us.tractat.bee.core.BoardState
import us.tractat.kuilt.crdt.GSet
import us.tractat.kuilt.crdt.JsonCrdt
import us.tractat.kuilt.crdt.JsonNode
import us.tractat.kuilt.crdt.JsonValue
import us.tractat.kuilt.crdt.MVRegister
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class QuiltedPersistenceTest {
    private val key = StoreKey("test/state")

    @Test
    fun loadRestoresAMutableStateUnderTheNewAuthor() = runTest {
        val store = InMemoryDurableStore()
        val docs = QuiltedPersistence(store, key, JsonCrdt.serializer())
        val written = JsonCrdt.empty(OLD).piece { it.set("title", leaf(OLD, "lunch")) }
        val saver = docs.saveOnChange(MutableStateFlow(written), backgroundScope)
        runCurrent()
        saver.stop()

        val restored = docs.load { it.withReplica(NEW) }!!
        val unrestored = docs.load { it }!!
        assertAll(
            { assertEquals(written, restored) },
            { assertEquals(setOf("title", "where"), restored.piece { it.set("where", leaf(NEW, "park")) }.keys) },
            // A decoded document has no author, so it can't mutate until restore gives it one.
            { assertFailsWith<IllegalArgumentException> { unrestored.set("where", leaf(NEW, "park")) } },
        )
    }

    @Test
    fun loadIsNullWhenNothingWasSaved() = runTest {
        assertNull(QuiltedPersistence(InMemoryDurableStore(), key, SET).load { it })
    }

    @Test
    fun preparedFieldsAreNotSaved() = runTest {
        val sets = QuiltedPersistence(InMemoryDurableStore(), key, SET)
        val state = MutableStateFlow(GSet.of("surface", "~presence"))
        val saver = sets.saveOnChange(state, backgroundScope, ::withoutEphemeral)
        runCurrent()
        saver.stop()

        assertEquals(GSet.of("surface"), sets.load { it })
    }

    @Test
    fun unchangedStateIsNotRewritten() = runTest {
        val store = CountingStore()
        val sets = QuiltedPersistence(store, key, SET)
        val state = MutableStateFlow(GSet.of("surface"))
        val saver = sets.saveOnChange(state, backgroundScope, ::withoutEphemeral)
        runCurrent()
        val first = store.writes

        state.value = GSet.of("surface", "~presence")
        advanceTimeBy(5.seconds)
        val afterStripped = store.writes
        state.value = GSet.of("surface", "~presence", "record")
        advanceTimeBy(5.seconds)
        val afterReal = store.writes
        saver.stop()

        assertAll(
            { assertEquals(1, first, "the first state is written at once") },
            { assertEquals(1, afterStripped, "a change prepare takes out is not written") },
            { assertEquals(2, afterReal, "a change that survives prepare is written") },
            { assertEquals(2, store.writes, "stop writes nothing already saved") },
        )
    }

    @Test
    fun aBurstIsWrittenAtMostOncePerInterval() = runTest {
        val store = CountingStore()
        val sets = QuiltedPersistence(store, key, SET, interval = 2.seconds)
        val state = MutableStateFlow(GSet.of("a"))
        val saver = sets.saveOnChange(state, backgroundScope)
        runCurrent()
        (1..10).forEach { n ->
            state.value = state.value.piece(GSet.of("b$n"))
            advanceTimeBy(100)
        }
        val duringBurst = store.writes
        advanceTimeBy(2.seconds)
        val afterBurst = store.writes
        saver.stop()
        val saved = sets.load { it }

        assertAll(
            { assertEquals(1, duringBurst, "only the first state, within the first interval") },
            { assertEquals(2, afterBurst, "the burst, as one write of its last state") },
            { assertEquals(11, saved?.elements?.size) },
        )
    }

    @Test
    fun stopSavesTheLastChange() = runTest {
        val sets = QuiltedPersistence(InMemoryDurableStore(), key, SET)
        val state = MutableStateFlow(GSet.of("a"))
        val saver = sets.saveOnChange(state, backgroundScope)
        runCurrent()
        state.value = GSet.of("a", "b")
        saver.stop()

        assertEquals(GSet.of("a", "b"), sets.load { it })
    }

    /** A second tab takes over the writing from a replica that missed what the first one saved. */
    @Test
    fun aLateWriterNeverShrinksTheSnapshot() = runTest {
        val store = InMemoryDurableStore()
        val first = QuiltedPersistence(store, key, SET).saveOnChange(MutableStateFlow(GSet.of("a", "b")), backgroundScope)
        runCurrent()
        first.stop()

        val sets = QuiltedPersistence(store, key, SET)
        val second = sets.saveOnChange(MutableStateFlow(GSet.of("c")), backgroundScope)
        runCurrent()
        second.stop()

        assertEquals(GSet.of("a", "b", "c"), sets.load { it })
    }

    @Test
    fun aFailedWriteIsRecordedAndRetried() = runTest {
        val store = CountingStore(failNext = 1)
        val sets = QuiltedPersistence(store, key, SET, interval = 2.seconds)
        val saver = sets.saveOnChange(MutableStateFlow(GSet.of("a")), backgroundScope)
        runCurrent()
        val failed = saver.lastFailure.value
        advanceTimeBy(2.seconds + 1.milliseconds)
        runCurrent()
        val retried = saver.lastFailure.value
        saver.stop()
        val saved = sets.load { it }

        assertAll(
            { assertEquals("disk full", failed?.message) },
            { assertNull(retried, "the retry, with nothing changed, succeeds and clears the failure") },
            { assertEquals(GSet.of("a"), saved) },
        )
    }

    /** A throwing `prepare` is a bug: it ends the writer at once, not a retry every interval. */
    @Test
    fun aThrowingPrepareFailsTheSaverInsteadOfRetrying() = runTest {
        val failures = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, failure -> failures += failure },
        )
        var prepared = 0
        val saver = QuiltedPersistence(InMemoryDurableStore(), key, SET).saveOnChange(MutableStateFlow(GSet.of("a")), scope) {
            prepared++
            throw IllegalStateException("prepare is broken")
        }
        runCurrent()
        advanceTimeBy(10.seconds)
        scope.cancel()

        assertAll(
            { assertEquals(listOf("prepare is broken"), failures.map { it.message }) },
            { assertEquals(1, prepared, "never retried") },
            { assertNull(saver.lastFailure.value, "not a store failure") },
        )
    }

    /** A copy that doesn't decode fails [QuiltedPersistence.load], and the next writer replaces it. */
    @Test
    fun aSnapshotThatWontDecodeIsReportedThenOverwritten() = runTest {
        val store = InMemoryDurableStore()
        store.write(key, "not a snapshot".encodeToByteArray())
        val sets = QuiltedPersistence(store, key, SET)
        val loadFailure = try {
            sets.load { it }
            null
        } catch (failure: SerializationException) {
            failure
        }

        val saver = sets.saveOnChange(MutableStateFlow(GSet.of("a")), backgroundScope)
        runCurrent()
        val writeFailure = saver.lastFailure.value
        saver.stop()
        val replaced = sets.load { it }

        assertAll(
            { assertNotNull(loadFailure) },
            { assertNull(writeFailure, "the writer is not stuck on the bad copy") },
            { assertEquals(GSet.of("a"), replaced) },
        )
    }

    /**
     * Well-formed JSON whose inner decoders refuse (here a peer identity) is still an unreadable
     * copy. kotlinx 1.12 wraps such a refusal as a [SerializationException]; the catch is wider
     * ([IllegalArgumentException]) so a version that doesn't can't stop the page.
     */
    @Test
    fun aWellFormedSnapshotWithABadIdentityIsReplacedNotFatal() = runTest {
        val store = InMemoryDurableStore()
        val boards = QuiltedPersistence(store, key, BoardState.serializer())
        val withRecord = BoardFixtures.opened().piece { it.appendRecord(ReplicaId("web-a"), BoardFixtures.record(it)) }
        val written = boards.saveOnChange(MutableStateFlow(withRecord), backgroundScope)
        runCurrent()
        written.stop()
        val good = store.read(key)!!.decodeToString()
        val bad = Regex("\"(human|agent):[^\"]*\"").replace(good, "\"nobody\"")
        store.write(key, bad.encodeToByteArray())

        val loadFailure = try {
            boards.load { it }
            null
        } catch (failure: IllegalArgumentException) {
            failure
        }
        val saver = boards.saveOnChange(MutableStateFlow(BoardState.EMPTY), backgroundScope)
        runCurrent()
        val writeFailure = saver.lastFailure.value
        saver.stop()
        val replaced = boards.load { it }

        assertAll(
            { assertNotEquals(good, bad, "the fixture carries a record actor to corrupt") },
            { assertNotNull(loadFailure) },
            { assertNull(writeFailure, "the writer is not stuck on the bad copy") },
            { assertEquals(BoardState.EMPTY, replaced) },
        )
    }

    private fun withoutEphemeral(set: GSet<String>) = GSet.of(*set.elements.filterNot { it.startsWith("~") }.toTypedArray())

    private fun leaf(replica: ReplicaId, text: String) = JsonNode.Leaf(MVRegister.empty<JsonValue>().set(replica, JsonValue.Str(text)))

    /** An in-memory store that counts its writes, and fails the first [failNext] of them. */
    private class CountingStore(
        private var failNext: Int = 0,
        private val delegate: DurableStore = InMemoryDurableStore(),
    ) : DurableStore by delegate {
        var writes = 0

        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            if (failNext > 0) {
                failNext--
                throw IllegalStateException("disk full")
            }
            writes++
            delegate.write(key, bytes)
        }
    }

    private companion object {
        val OLD = ReplicaId("web-old")
        val NEW = ReplicaId("web-new")
        val SET = GSet.serializer(String.serializer())
    }
}
