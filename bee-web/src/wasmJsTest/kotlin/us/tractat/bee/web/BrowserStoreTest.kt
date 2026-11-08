package us.tractat.bee.web

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A JS throw out of the store reaches `catch (Exception)` (tractat-us/kuilt#3143). Without the
 * mapping it is a `JsException`, a `Throwable` only, and stops the page.
 */
class BrowserStoreTest {
    @Test
    fun aJsThrowFromTheStoreIsAnException() = runTest {
        val store = ThrowingStore().mappingJsThrows()
        val caught = listOf<suspend () -> Unit>(
            { store.read(KEY) },
            { store.write(KEY, ByteArray(0)) },
            { store.delete(KEY) },
        ).map { call ->
            try {
                call()
                null
            } catch (failure: Exception) {
                failure
            }
        }

        assertAll(
            { assertEquals(3, caught.filterNotNull().size, caught.toString()) },
            { caught.forEach { assertIs<JsStoreException>(it) } },
            { caught.forEach { assertIs<JsException>(it?.cause) } },
        )
    }

    /** A store whose every call throws as a closing `IDBDatabase.transaction` does, from JS. */
    private class ThrowingStore : DurableStore {
        override suspend fun read(key: StoreKey): ByteArray? {
            throwInvalidState()
            return null
        }

        override suspend fun write(key: StoreKey, bytes: ByteArray) = throwInvalidState()

        override suspend fun delete(key: StoreKey) = throwInvalidState()
    }

    private companion object {
        val KEY = StoreKey("board/id")
    }
}

@JsFun("() => { const e = new Error('the database is closing'); e.name = 'InvalidStateError'; throw e; }")
private external fun throwInvalidState()
