@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.bee.mcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.put
import us.tractat.bee.a2ui.NOTIONS_CATALOG_ID
import us.tractat.bee.core.ActionRecord
import us.tractat.bee.core.Basis
import us.tractat.bee.core.BoardClient
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.ClaimCommand
import us.tractat.bee.core.ClaimTarget
import us.tractat.bee.core.ClaimToken
import us.tractat.bee.core.ClaimsConnection
import us.tractat.bee.core.ClaimsLink
import us.tractat.bee.core.DepartReason
import us.tractat.bee.core.OrphanDecision
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.Presence
import us.tractat.bee.core.PresenceEntry
import us.tractat.bee.core.SurfaceMeta
import us.tractat.bee.host.BoardStar
import us.tractat.bee.host.Caller
import us.tractat.bee.host.ClaimsRig
import us.tractat.bee.host.ClaimsVoter
import us.tractat.bee.host.Device
import us.tractat.bee.host.InMemoryClaimsHost
import us.tractat.bee.host.JoinRequestSurface
import us.tractat.bee.host.Opened
import us.tractat.bee.host.Pairing
import us.tractat.bee.host.SecureSecrets
import us.tractat.bee.host.boardMirror
import us.tractat.bee.host.memoryKeys
import us.tractat.kuilt.cluster.ClusterEndpoints
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.raft.DurableStoreRaftStorage
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.test.assertAll
import java.security.SecureRandom
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

    /**
     * `bee_board_clear` skips the host's surfaces: a pending join request stays open, so its
     * device isn't stranded until its pairing expires (bee#71).
     */
    @Test
    fun clearLeavesAPendingJoinRequestOpen() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        val request = rig.joinRequest()
        ada.call(BeeTools.OPEN, openArgs("one"))
        ada.client.state.first { request in it.openSurfaces() && "one" in it.openSurfaces() }

        val cleared = ada.call(BeeTools.CLEAR, emptyMap()).json()
        val after = rig.star.relay.state.first { "one" !in it.openSurfaces() }
        assertAll(
            { assertEquals(listOf("one"), cleared.getValue("closed").jsonArray.map { it.jsonPrimitive.content }) },
            { assertEquals(listOf(request), after.openSurfaces(), "the join request stays open") },
        )
        rig.close()
    }

    /**
     * An agent can't wait on or list a join request. The host handles its admit records without a
     * claim, so a record an agent took would hand it the code a person typed (bee#72).
     */
    @Test
    fun waitAndListRefuseAJoinRequest() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        val browser = rig.browser()
        val request = rig.joinRequest()
        browser.state.first { request in it.openSurfaces() }
        browser.mutate { state ->
            val admit = ActionRecord(
                surfaceId = request,
                componentId = JoinRequestSurface.ADMIT_BUTTON,
                actor = PeerIdentity.human("iain"),
                name = JoinRequestSurface.ADMIT_ACTION,
                context = buildJsonObject { put("code", "000000") },
                basis = Basis.capture(state, request),
                at = kotlin.time.Instant.fromEpochMilliseconds(0),
            )
            state.appendRecord(ReplicaId("web-iain"), admit)
        }
        ada.client.state.first { it.records(request).isNotEmpty() }

        val waited = ada.call(WaitTool.WAIT, mapOf("surfaceId" to request, "timeoutSeconds" to 1))
        val listed = ada.call(WaitTool.LIST, mapOf("surfaceId" to request))
        val control = ada.call(WaitTool.LIST, mapOf("surfaceId" to "none"))
        assertAll(
            { assertTrue(waited.isError == true && "the host's" in waited.text(), "wait: ${waited.text()}") },
            { assertTrue(listed.isError == true && "the host's" in listed.text(), "list: ${listed.text()}") },
            { assertEquals(null, control.isError, "control: listing any other surface still works") },
        )
        rig.close()
    }

    /**
     * The refusal is for pairing surfaces only: an orphan decision is host-opened too, and every
     * participant answers it (spec §6.4), so an agent can still wait on it and list it.
     */
    @Test
    fun waitAndListDoNotRefuseAHostOpenedOrphanDecision() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        val browser = rig.browser()
        val decision = "${OrphanDecision.PREFIX}record:x:1"
        val meta = SurfaceMeta("Orphaned claim", NOTIONS_CATALOG_ID, PeerIdentity.host("bee-host"), kotlin.time.Instant.fromEpochMilliseconds(0))
        browser.mutate { it.openSurface(ReplicaId("web-iain"), decision, meta, validSurface(), JsonObject(emptyMap())) }
        browser.mutate { it.appendRecord(ReplicaId("web-iain"), click(it, decision, "go")) }
        ada.client.state.first { it.records(decision).isNotEmpty() }

        val listed = ada.call(WaitTool.LIST, mapOf("surfaceId" to decision))
        val waited = ada.call(WaitTool.WAIT, mapOf("surfaceId" to decision, "timeoutSeconds" to 1))
        assertAll(
            { assertEquals(null, listed.isError, "list: ${listed.text()}") },
            { assertEquals(null, waited.isError, "wait: ${waited.text()}") },
            { assertTrue("\"timedOut\":false" in waited.text().replace(" ", ""), "wait returns the record: ${waited.text()}") },
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

    /** `bee_board` names the caller as `you`, so a session can tell itself apart from a namesake (#136). */
    @Test
    fun boardSaysWhichAttachedAgentIsTheCaller() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        val bo = rig.session("bo")

        val adaSees = ada.call(BeeTools.BOARD, emptyMap()).json()["you"]?.jsonPrimitive?.content
        val boSees = bo.call(BeeTools.BOARD, emptyMap()).json()["you"]?.jsonPrimitive?.content
        assertAll(
            { assertEquals(ada.bridge.self.toString(), adaSees) },
            { assertEquals(bo.bridge.self.toString(), boSees) },
        )
        rig.close()
    }

    /** A departed identity is gone for good, so `bee_board` drops it from `attached` while its presence lingers (#136). */
    @Test
    fun boardLeavesDepartedAgentsOutOfAttached() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        val bo = rig.session("bo")
        val gone = bo.bridge.self
        ada.client.state.first { s -> s.presence.entries.values.any { it.value?.identity == gone } }

        rig.voter.depart(gone, DepartReason.Crashed)
        ada.client.state.first { s -> s.departures().any { it.identity == gone } }
        val attached = ada.call(BeeTools.BOARD, emptyMap()).json().getValue("attached").jsonArray
            .map { it.jsonObject.getValue("identity").jsonPrimitive.content }
        val roster = ada.bridge.roster()
        assertAll(
            { assertTrue(gone in roster, "rig: bo's presence is still in ada's roster") },
            { assertTrue(gone.toString() !in attached, "bo is departed, not attached: $attached") },
            { assertTrue(ada.bridge.self.toString() in attached, "ada is still attached: $attached") },
        )
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
            { assertEquals(setOf(BeeTools.BOARD, WaitTool.WAIT, WaitTool.LIST, WhiteboardGuide.EXAMPLES_TOOL), reading.keys, "the rest only read") },
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

    /** How many releases of [target] under [token] the voter's log holds, committed or rejected. */
    suspend fun releasesOf(target: ClaimTarget, token: ClaimToken): Int =
        commands().count { it is ClaimCommand.Release && it.target == target && it.token == token }

    /** How many claims of [target] the voter's log holds, committed or lost. */
    suspend fun claimsOf(target: ClaimTarget): Int = commands().count { it is ClaimCommand.Claim && it.target == target }

    /** The claim commands in the voter's log, in order, past entries that aren't one (raft's own). */
    private suspend fun commands(): List<ClaimCommand> =
        DurableStoreRaftStorage.open(claims.store).entries(1).mapNotNull { entry ->
            try {
                ClaimCommand.decode(entry.command)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }

    /** Posts a join request from the host, as `BeeHost` does for a keyless device; returns its surface id. */
    fun joinRequest(): String {
        val pairing = Pairing(star.clock, SecureSecrets(SecureRandom()), memoryKeys())
        val requests = JoinRequestSurface(star.relay, pairing, star.clock).also { it.start(test.backgroundScope) }
        val ticket = pairing.open(Caller.byPeer("192.168.1.20")) as Opened.Ticket
        requests.post(ticket, Device(Caller.byPeer("192.168.1.20"), screen = null))
        return JoinRequestSurface.surfaceId(ticket.id)
    }

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
        val bridge = BeeBridge(client, author, connection, identity, presence, claims.clock, scope).also { it.announce() }
        connection.link.filterNotNull().first()
        return Session(bridge, client, connection, identity, failures, connect(bridge))
    }

    private suspend fun connect(bridge: BeeBridge): Client {
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val server = BeeServer.create(bridge, dispatcher)
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

    /** The claims node this session's own posted presence names now. */
    fun postedClaimsNode(): NodeId? = ownEntry(client.state.value)?.claimsNode

    /** Suspends until this session's own posted presence names [node]. */
    suspend fun awaitPostedClaimsNode(node: NodeId) {
        client.state.first { ownEntry(it)?.claimsNode == node }
    }

    private fun ownEntry(state: BoardState): PresenceEntry? =
        state.presence.entries.values.mapNotNull { it.value }.singleOrNull { it.identity == bridge.self }

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
