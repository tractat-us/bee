package us.tractat.bee.mcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.Progress
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The wait tools, driven through an in-process SDK client on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class WaitToolTest {
    private val source = FakeActionSource()

    @Test
    fun returnsARecordWithItsCursor() = runTest {
        source.append("s1", record("first"))
        val client = connect()

        val ready = client.wait(mapOf("surfaceId" to "s1"))
        launch { source.appendAfter(5.seconds, "s1", record("second")) }
        val held = client.wait(mapOf("surfaceId" to "s1", "cursor" to ready.cursor))

        assertAll(
            { assertEquals(record("first"), ready.record) },
            { assertEquals("1", ready.cursor) },
            { assertEquals(false, ready.timedOut) },
            { assertEquals(record("second"), held.record) },
            { assertEquals("2", held.cursor) },
            { assertEquals(5_000L, currentTime) },
        )
    }

    @Test
    fun timesOutWithinTheBoundAndSaysSo() = runTest {
        val client = connect()

        val byDefault = client.wait(mapOf("surfaceId" to "s1"))
        val defaultEndedAt = currentTime
        val chosen = client.wait(mapOf("surfaceId" to "s1", "cursor" to byDefault.cursor, "timeoutSeconds" to 60))

        assertAll(
            { assertEquals(true, byDefault.timedOut) },
            { assertNull(byDefault.record) },
            { assertEquals("0", byDefault.cursor) },
            { assertEquals(45_000L, defaultEndedAt) },
            { assertEquals(true, chosen.timedOut) },
            { assertEquals("0", chosen.cursor) },
            { assertEquals(105_000L, currentTime) },
        )
    }

    @Test
    fun sendsProgressWhileHolding() = runTest {
        val client = connect()
        val progress = mutableListOf<Progress>()
        launch { source.appendAfter(25.seconds, "s1", record("late")) }

        val result = client.wait(mapOf("surfaceId" to "s1")) { progress += it }

        assertAll(
            { assertEquals(record("late"), result.record) },
            { assertEquals(listOf(10.0, 20.0), progress.map { it.progress }) },
            { assertEquals(listOf(45.0, 45.0), progress.map { it.total }) },
        )
    }

    @Test
    fun refusesATimeoutAboveSixtySeconds() = runTest {
        val client = connect()

        val result = client.call(WaitTool.WAIT, mapOf("surfaceId" to "s1", "timeoutSeconds" to 61))

        assertAll(
            { assertEquals(true, result.isError) },
            { assertTrue("60" in result.text(), "the refusal doesn't state the bound: ${result.text()}") },
            { assertEquals(0, source.waits) },
            { assertEquals(0L, currentTime) },
        )
    }

    @Test
    fun listReadsWithoutBlocking() = runTest {
        source.append("s1", record("a"))
        source.append("s1", record("b"))
        val client = connect()

        val all = client.call(WaitTool.LIST, mapOf("surfaceId" to "s1")).records()
        val since = client.call(WaitTool.LIST, mapOf("surfaceId" to "s1", "since" to "1")).records()
        val empty = client.call(WaitTool.LIST, mapOf("surfaceId" to "s2")).records()

        assertAll(
            { assertEquals(listOf(record("a"), record("b")), all) },
            { assertEquals(listOf(record("b")), since) },
            { assertEquals(emptyList(), empty) },
            { assertEquals(0L, currentTime) },
        )
    }

    private suspend fun TestScope.connect(): Client {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val server = Server(
            Implementation("bee", "test"),
            ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
                handlerCoroutineContext = dispatcher,
            ),
        )
        WaitTool.register(server, source, progressEvery = 10.seconds)
        val (clientSide, serverSide) = LinkedTransport.pair()
        server.createSession(serverSide)
        return Client(Implementation("test", "test"), ClientOptions(handlerCoroutineContext = dispatcher))
            .also { it.connect(clientSide) }
    }

    private suspend fun Client.call(
        name: String,
        arguments: Map<String, Any?>,
        onProgress: ((Progress) -> Unit)? = null,
    ): CallToolResult = callTool(name, arguments, options = RequestOptions(onProgress = onProgress, timeout = 5.minutes))

    private suspend fun Client.wait(arguments: Map<String, Any?>, onProgress: ((Progress) -> Unit)? = null): Waited {
        val result = call(WaitTool.WAIT, arguments, onProgress)
        check(result.isError != true) { "the wait was refused: ${result.text()}" }
        val json = checkNotNull(result.structuredContent)
        return Waited(
            record = json["record"] as JsonObject?,
            timedOut = json.getValue("timedOut").jsonPrimitive.boolean,
            cursor = json.getValue("cursor").jsonPrimitive.content,
        )
    }

    private fun CallToolResult.text(): String = content.filterIsInstance<TextContent>().joinToString { it.text }

    private fun CallToolResult.records(): List<JsonObject> =
        checkNotNull(structuredContent).getValue("records").jsonArray.map { it as JsonObject }

    private fun record(label: String): JsonObject = buildJsonObject { put("name", label) }

    private data class Waited(val record: JsonObject?, val timedOut: Boolean, val cursor: String)
}

/** Records per surface, with the cursor as the count of records seen. */
private class FakeActionSource : ActionSource {
    private val records = MutableStateFlow(mapOf<String, List<JsonObject>>())
    var waits = 0
        private set

    fun append(surfaceId: String, record: JsonObject) =
        records.update { it + (surfaceId to it[surfaceId].orEmpty() + record) }

    suspend fun appendAfter(delay: Duration, surfaceId: String, record: JsonObject) {
        kotlinx.coroutines.delay(delay)
        append(surfaceId, record)
    }

    override suspend fun next(surfaceId: String, cursor: String?, timeout: Duration): WaitResult {
        waits++
        val seen = cursor?.toInt() ?: 0
        val record = withTimeoutOrNull(timeout) {
            records.first { it[surfaceId].orEmpty().size > seen }.getValue(surfaceId)[seen]
        }
        return when (record) {
            null -> WaitResult(record = null, timedOut = true, cursor = seen.toString())
            else -> WaitResult(record = record, timedOut = false, cursor = (seen + 1).toString())
        }
    }

    override fun list(surfaceId: String, since: String?): List<JsonObject> =
        records.value[surfaceId].orEmpty().drop(since?.toInt() ?: 0)
}

/** One end of an in-process transport pair: what one end sends, the other receives. */
private class LinkedTransport : AbstractTransport() {
    private lateinit var peer: LinkedTransport

    override suspend fun start() = Unit

    override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) = peer.receive(message)

    override suspend fun close() {
        invokeOnCloseCallback()
        peer.invokeOnCloseCallback()
    }

    private suspend fun receive(message: JSONRPCMessage) = _onMessage(message)

    companion object {
        fun pair(): Pair<LinkedTransport, LinkedTransport> {
            val a = LinkedTransport()
            val b = LinkedTransport()
            a.peer = b
            b.peer = a
            return a to b
        }
    }
}
