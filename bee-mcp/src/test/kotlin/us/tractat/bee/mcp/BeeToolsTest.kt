@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.bee.mcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import us.tractat.bee.core.ActionRecord
import us.tractat.bee.core.Basis
import us.tractat.bee.core.BoardClient
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.ClaimTarget
import us.tractat.bee.core.ClaimsConnection
import us.tractat.bee.core.ClaimsLink
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.Presence
import us.tractat.bee.host.BoardStar
import us.tractat.bee.host.ClaimsRig
import us.tractat.bee.host.ClaimsVoter
import us.tractat.bee.host.InMemoryClaimsHost
import us.tractat.bee.host.boardMirror
import us.tractat.kuilt.cluster.ClusterEndpoints
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class BeeToolsTest {
    /** A surface one session opens reaches a browser's replica and another session's `bee_board`. */
    @Test
    fun openPutsTheSurfaceOnTheBoardForEveryone() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        val bo = rig.session("bo")
        val browser = rig.browser()

        val opened = ada.call(BeeTools.OPEN, openArgs("lunch"))
        val seen = browser.state.first { "lunch" in it.openSurfaces() }
        bo.client.state.first { "lunch" in it.openSurfaces() }
        val board = bo.call(BeeTools.BOARD, emptyMap()).json()
        val listed = board.getValue("open").jsonArray.map { it.jsonObject.getValue("surfaceId").jsonPrimitive.content }
        assertAll(
            { assertEquals(null, opened.isError, "opened: ${opened.text()}") },
            { assertEquals(ada.bridge.self, seen.meta("lunch")?.openedBy, "the browser sees who opened it") },
            { assertEquals(listOf("lunch"), listed, "the other session's bee_board lists it") },
        )
        rig.close()
    }

    /** Review Focus 1: a malformed surface is refused with an error naming the fault, and the board is unchanged. */
    @Test
    fun openRefusesMalformedComponents() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")

        val noRoot = ada.call(BeeTools.OPEN, openArgs("a", components = listOf(button("ok"), text("ok-label", "OK"))))
        val unknownType = ada.call(BeeTools.OPEN, openArgs("b", components = listOf(component("root", "Marquee"))))
        val danglingChild = ada.call(
            BeeTools.OPEN,
            openArgs("c", components = listOf(buildJsonObject { put("id", "root"); put("component", "Column"); put("children", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("ghost")) }) })),
        )
        val control = ada.call(BeeTools.OPEN, openArgs("d"))
        assertAll(
            { assertTrue(noRoot.isError == true && "\"root\"" in noRoot.text(), "no root: ${noRoot.text()}") },
            { assertTrue(unknownType.isError == true && "Marquee" in unknownType.text(), "unknown type: ${unknownType.text()}") },
            { assertTrue(danglingChild.isError == true && "ghost" in danglingChild.text(), "dangling child: ${danglingChild.text()}") },
            { assertEquals(listOf("d"), ada.client.state.value.openSurfaces(), "only the valid surface reached the board") },
            { assertEquals(null, control.isError, "control: a valid surface opens") },
        )
        rig.close()
    }

    /** `bee_board_clear` closes every open surface, and every one keeps its document. */
    @Test
    fun clearClosesEverySurfaceAndDeletesNone() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        ada.call(BeeTools.OPEN, openArgs("one"))
        ada.call(BeeTools.OPEN, openArgs("two"))
        val before = ada.client.state.value.openSurfaces()

        val cleared = ada.call(BeeTools.CLEAR, emptyMap()).json()
        val after = ada.client.state.value
        assertAll(
            { assertEquals(listOf("one", "two"), before, "rig: both were open") },
            { assertEquals(listOf("one", "two"), cleared.getValue("closed").jsonArray.map { it.jsonPrimitive.content }.sorted()) },
            { assertEquals(emptyList(), after.openSurfaces(), "nothing is open") },
            { assertEquals(setOf("one", "two"), after.closedSurfaces().toSet(), "both are closed") },
            { assertTrue(after.surfaceDoc("one") != null && after.surfaceDoc("two") != null, "neither was deleted") },
        )
        rig.close()
    }

    /** `bee_board` lists who is attached: both sessions, by identity. */
    @Test
    fun boardListsWhoIsAttached() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        val bo = rig.session("bo")
        ada.client.state.first { s -> s.presence.entries.values.any { it.value?.identity == bo.bridge.self } }

        val attached = ada.call(BeeTools.BOARD, emptyMap()).json().getValue("attached").jsonArray
            .map { it.jsonObject.getValue("identity").jsonPrimitive.content }
        assertEquals(listOf(ada.bridge.self, bo.bridge.self).map { it.toString() }.sorted(), attached)
        rig.close()
    }

    /**
     * A read-only call counts as acting: a session that only reads the board keeps its presence
     * fresh, so it never goes quiet. Its `lastActedAt` moves to the read, by the next post.
     */
    @Test
    fun readOnlyCallsCountAsActing() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        ada.call(BeeTools.BOARD, emptyMap())
        val first = ada.lastActedAt()

        delay(19.minutes)
        ada.call(WaitTool.LIST, mapOf("surfaceId" to "none"))
        val readAt = rig.claims.clock()
        delay(Presence.EVERY + 1.seconds)
        val afterList = ada.lastActedAt()
        delay(19.minutes)
        ada.call(BeeTools.BOARD, emptyMap())
        val boardAt = rig.claims.clock()
        delay(Presence.EVERY + 1.seconds)
        val afterBoard = ada.lastActedAt()
        assertAll(
            { assertTrue(afterList >= readAt && afterList > first, "bee_list_actions counted: $first, then $afterList") },
            { assertTrue(afterBoard >= boardAt, "bee_board counted: $afterBoard") },
        )
        rig.close()
    }

    /**
     * Every tool that writes to the board says it's shared, first; no reading tool does. The
     * writing set is checked against what's registered, so a new writing tool can't skip it.
     */
    @Test
    fun everyWritingToolSaysItIsShared() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val tools = rig.session("ada").mcp.listTools().tools.associate { it.name to it.description.orEmpty() }

        val writing = tools.filterKeys { it in BeeTools.WRITING }
        val reading = tools - BeeTools.WRITING
        assertAll(
            { assertEquals(BeeTools.WRITING, writing.keys, "every writing tool is registered") },
            { assertEquals(setOf(BeeTools.BOARD, WaitTool.WAIT, WaitTool.LIST), reading.keys, "the rest only read") },
            {
                val unshared = writing.filterValues { !(it.startsWith("Puts ") && BeeTools.SHARED in it.substringBefore('.')) }
                assertEquals(emptyMap(), unshared, "writing tools whose first sentence doesn't say it's shared")
            },
            { assertEquals(emptyMap(), reading.filterValues { BeeTools.SHARED in it }, "reading tools that claim to share") },
        )
        rig.close()
    }
}

internal fun openArgs(id: String, components: List<JsonObject> = validSurface()) = mapOf(
    "surfaceId" to id,
    "title" to "Surface $id",
    "components" to JsonArray(components),
)

internal fun component(id: String, type: String) = buildJsonObject { put("id", id); put("component", type) }

internal fun column(id: String, vararg children: String) = buildJsonObject {
    put("id", id)
    put("component", "Column")
    put("children", JsonArray(children.map { kotlinx.serialization.json.JsonPrimitive(it) }))
}

/** A Button whose label is the Text component `<id>-label`. */
internal fun button(id: String) = buildJsonObject {
    put("id", id)
    put("component", "Button")
    put("child", "$id-label")
}

internal fun text(id: String, words: String) = buildJsonObject {
    put("id", id)
    put("component", "Text")
    put("text", words)
}

/** One button under a root column: the smallest surface the Notions validator passes. */
internal fun validSurface() = listOf(column("root", "go"), button("go"), text("go-label", "Go"))

/**
 * Bridges, a browser and the host's two endpoints, in memory on virtual time: bee-host's relay
 * ([BoardStar]) and voter ([ClaimsRig]'s room host), with the voter's mirror on the relay's board
 * as `BeeHost` wires it.
 */
internal class JoinRig(private val test: TestScope) {
    val star = BoardStar(test)
    val claims = ClaimsRig(test)
    lateinit var voter: ClaimsVoter
    private var bridges = 0

    suspend fun start() {
        star.start()
        voter = ClaimsVoter.start(test.backgroundScope, claims.host, claims.store, claims.raftConfig, boardMirror(star.relay), claims.clock)
    }

    /** Stops the voter before the test's scope ends: cancelled with it, its relay throws (spike 4 notes). */
    suspend fun close() = voter.close()

    /** The browser [clickOn] clicked from. */
    lateinit var browserClient: BoardClient

    /** Opens surface `s` from [session] and clicks it once from a browser; returns the click's claim target. */
    suspend fun clickOn(session: Session): ClaimTarget {
        browserClient = browser()
        session.call(BeeTools.OPEN, openArgs("s"))
        browserClient.state.first { "s" in it.openSurfaces() }
        browserClient.mutate { it.appendRecord(ReplicaId("web-iain"), click(it, "s", "go")) }
        val (id, _) = session.client.state.first { it.records("s").isNotEmpty() }.records("s").first()
        return BoardActionSource.target(id)
    }

    /** Session `ada` with surface `s` open and one click on it; returns the session and the click's claim target. */
    suspend fun clicked(): Pair<Session, ClaimTarget> {
        val ada = session("ada")
        val browser = browser()
        ada.call(BeeTools.OPEN, openArgs("s"))
        browser.state.first { "s" in it.openSurfaces() }
        browser.mutate { it.appendRecord(ReplicaId("web-iain"), click(it, "s", "go")) }
        val (id, _) = ada.client.state.first { it.records("s").isNotEmpty() }.records("s").single()
        return ada to BoardActionSource.target(id)
    }

    /** A browser's replica, online. */
    fun browser(name: String = "web-iain"): BoardClient = star.client(ReplicaId(name)).also { it.connect() }

    /**
     * A bridge named [name], with its own replica, supervised claims connection and identity,
     * served over an in-process MCP client. Each claims link it opens runs [onLink] on its scope,
     * which a test uses to break a link.
     */
    suspend fun session(
        name: String,
        scope: CoroutineScope = test.backgroundScope,
        retryDelay: (Int) -> Duration = { 1.seconds },
        onLink: (Int, CoroutineScope) -> Unit = { _, _ -> },
    ): Session {
        val n = ++bridges
        val author = ReplicaId("mcp-$name")
        val client = star.client(author, scope = scope).also { it.connect() }
        var links = 0
        val failures = mutableListOf<Throwable>()
        val connection = ClaimsConnection(
            scope = scope,
            nextNode = { NodeId("bridge-$name-$n-${++links}") },
            open = { node, linkScope ->
                ClaimsLink.open(
                    node = node,
                    loomFor = { peer -> claims.host.loomFor(NodeId(peer.value)) },
                    endpoints = ClusterEndpoints(listOf(InMemoryTag(InMemoryClaimsHost.SESSION))),
                    scope = linkScope,
                    raftConfig = claims.raftConfig,
                    clock = claims.clock,
                    random = Random(1_000 * n + links),
                ).also { onLink(links, linkScope) }
            },
            onFailure = { failures += it },
            retryDelay = retryDelay,
        )
        val identity = BridgeIdentity(name, Random(n)) { old, reason -> connection.depart(old, reason) }
        val presence = Presence(client, author, connection.link.filterNotNull().map { it.node }, claims.clock, scope) { throw it }
        val bridge = BeeBridge(client, author, connection, identity, presence, claims.clock).also { it.announce() }
        connection.link.filterNotNull().first()
        return Session(bridge, client, connection, identity, failures, connect(bridge))
    }

    private suspend fun connect(bridge: BeeBridge): Client {
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val server = Server(
            Implementation("bee", "test"),
            ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
                handlerCoroutineContext = dispatcher,
            ),
        )
        WaitTool.register(server, BoardActionSource(bridge))
        BeeTools.register(server, bridge)
        val (clientSide, serverSide) = LinkedTransport.pair()
        server.createSession(serverSide)
        return Client(Implementation("test", "test"), ClientOptions(handlerCoroutineContext = dispatcher))
            .also { it.connect(clientSide) }
    }
}

internal class Session(
    val bridge: BeeBridge,
    val client: BoardClient,
    val connection: ClaimsConnection,
    val identity: BridgeIdentity,
    val linkFailures: List<Throwable>,
    val mcp: Client,
) {
    /** The live claims link, waiting for one if it's being rebuilt. */
    suspend fun link(): ClaimsLink = connection.link.filterNotNull().first()

    /** What this session's own posted presence says about when it last acted. */
    fun lastActedAt(): kotlin.time.Instant =
        client.state.value.presence.entries.values.mapNotNull { it.value }.single { it.identity == bridge.self }.lastActedAt

    /** The live link's Raft node. */
    suspend fun node(): NodeId = link().node

    suspend fun call(name: String, arguments: Map<String, Any?>): CallToolResult =
        mcp.callTool(name, arguments, options = RequestOptions(timeout = 5.minutes))
}

/** A click on [componentId] of surface [surfaceId], as the browser driver records it: its basis read from [state]. */
internal fun click(state: BoardState, surfaceId: String, componentId: String, basis: Basis = Basis.capture(state, surfaceId)) = ActionRecord(
    surfaceId = surfaceId,
    componentId = componentId,
    actor = PeerIdentity.human("iain"),
    name = "click",
    context = JsonObject(emptyMap()),
    basis = basis,
    at = kotlin.time.Instant.fromEpochMilliseconds(0),
)

internal fun CallToolResult.text(): String = content.filterIsInstance<TextContent>().joinToString { it.text }

internal fun CallToolResult.json(): JsonObject = checkNotNull(structuredContent) { "no structured result: ${text()}" }
