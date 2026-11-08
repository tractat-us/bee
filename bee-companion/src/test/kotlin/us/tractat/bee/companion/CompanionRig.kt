package us.tractat.bee.companion

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import us.tractat.bee.a2ui.toSurfaceView
import us.tractat.bee.core.ActionRecord
import us.tractat.bee.core.Basis
import us.tractat.bee.core.BoardClient
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.ClaimsConnection
import us.tractat.bee.core.ClaimsLink
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.Presence
import us.tractat.bee.core.SpokeLoom
import us.tractat.bee.host.BoardStar
import us.tractat.bee.host.ClaimsRig
import us.tractat.bee.host.ClaimsVoter
import us.tractat.bee.host.InMemoryClaimsHost
import us.tractat.bee.host.boardMirror
import us.tractat.bee.mcp.BeeBridge
import us.tractat.bee.mcp.BoardActionSource
import us.tractat.bee.mcp.BridgeIdentity
import us.tractat.kuilt.cluster.ClusterEndpoints
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.core.fabric.peerMesh
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.test.fabric.connectionPair
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The companion on an in-memory board, under virtual time: [BoardStar]'s relay, [ClaimsRig]'s
 * voter, a browser replica that clicks, and companions joined as a session joins (bee-mcp's
 * JoinRig shape). Each companion runs on its own child scope, so a test can stop one and start
 * another over the same directories, as a restart does.
 *
 * @param maxFrameBytes when set, every spoke's link to the hub publishes this frame ceiling, as a
 *   real fabric would, so the seams above it publish a payload budget.
 */
internal class CompanionRig(private val test: TestScope, val dir: Path, private val maxFrameBytes: Int? = null) {
    val star = BoardStar(test)
    val claims = ClaimsRig(test)
    lateinit var voter: ClaimsVoter
    val screenDir: Path = Files.createDirectories(dir.resolve("content"))
    val stateDir: Path = Files.createDirectories(dir.resolve("state"))
    val frameTemplate: Path = dir.resolve("frame-template.html").also { it.writeText(FRAME_TEMPLATE) }
    val events: Path = stateDir.resolve("events")
    private val dispatcher = StandardTestDispatcher(test.testScheduler)
    private var companions = 0
    private var screens = 0

    suspend fun start() {
        star.start()
        voter = ClaimsVoter.start(test.backgroundScope, claims.host, claims.store, claims.raftConfig, boardMirror(star.relay), claims.clock)
    }

    /** Stops the voter before the test's scope ends: cancelled with it, its relay throws (spike 4 notes). */
    suspend fun close() = voter.close()

    /** A browser's replica, online, through the same kind of link as the companion's. */
    fun browser(name: String = "web-iain"): BoardClient =
        star.client(ReplicaId(name), loom = loom(name)).also { it.connect() }

    /** A spoke loom like [BoardStar.loom], whose link publishes [maxFrameBytes] when the rig sets it. */
    private fun loom(name: String, scope: CoroutineScope = test.backgroundScope): SpokeLoom =
        SpokeLoom(scope, name, Random(name.hashCode()), star.clock) { id, _ ->
            val (hubEnd, spokeEnd) = connectionPair()
            star.hubEnds += hubEnd
            star.source.offer(hubEnd)
            val link = maxFrameBytes?.let { Budgeted(spokeEnd, it) } ?: spokeEnd
            peerMesh(id, listOf(link), dispatcher)
        }

    /** A link that publishes [maxFrameBytes], as a framed transport does. */
    private class Budgeted(base: Connection, override val maxFrameBytes: Int) : Connection by base

    /** A companion over the rig's directories, joined like a session, running on a fresh child scope. */
    suspend fun companion(): Running {
        val n = ++companions
        val scope = CoroutineScope(test.backgroundScope.coroutineContext + Job(test.backgroundScope.coroutineContext[Job]))
        val author = ReplicaId("companion-$n")
        val client = star.client(author, loom = loom(author.value, scope), scope = scope).also { it.connect() }
        var links = 0
        val connection = ClaimsConnection(
            scope = scope,
            nextNode = { NodeId("companion-$n-${++links}") },
            open = { node, linkScope ->
                ClaimsLink.open(
                    node = node,
                    loomFor = { peer -> claims.host.loomFor(NodeId(peer.value)) },
                    endpoints = ClusterEndpoints(listOf(InMemoryTag(InMemoryClaimsHost.SESSION))),
                    scope = linkScope,
                    raftConfig = claims.raftConfig,
                    clock = claims.clock,
                    random = Random(1_000 * n + links),
                )
            },
            onFailure = { throw it },
            retryDelay = { 1.seconds },
        )
        val identity = BridgeIdentity("companion", Random(n)) { old, reason -> connection.depart(old, reason) }
        val presence = Presence(client, author, claims.clock, scope) { throw it }
        val bridge = BeeBridge(client, author, connection, identity, presence, claims.clock).also { it.announce() }
        connection.link.filterNotNull().first()
        val reports = mutableListOf<JsonObject>()
        val adapter = CompanionAdapter(
            screenDir = screenDir,
            stateDir = stateDir,
            frameTemplate = frameTemplate,
            client = client,
            author = author,
            bridge = bridge,
            actions = BoardActionSource(bridge),
            clock = claims.clock,
            report = { reports += it },
        )
        return Running(adapter, client, bridge, reports, scope, adapter.start(scope))
    }

    class Running(
        val adapter: CompanionAdapter,
        val client: BoardClient,
        val bridge: BeeBridge,
        val reports: List<JsonObject>,
        private val scope: CoroutineScope,
        private val job: Job,
    ) {
        /** Stops the companion and everything it ran, as a killed process would. */
        suspend fun stop() {
            job.cancelAndJoin()
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    /** Writes screen [name] with [html], newer than every screen before it. */
    fun screen(name: String, html: String): Path {
        val file = screenDir.resolve(name)
        file.writeText(html)
        Files.setLastModifiedTime(file, FileTime.fromMillis(1_706_000_000_000 + 1_000L * ++screens))
        return file
    }

    /** Waits on [client]'s board until the companion surface shows [html], and returns that board. */
    suspend fun shows(client: BoardClient, html: (String) -> Boolean): BoardState = withTimeout(WAIT) {
        client.state.first { state -> sketchHtml(state)?.let(html) == true }
    }

    /** Clicks [choice] on the companion's sketch from [browser], as the page records it. */
    suspend fun click(browser: BoardClient, choice: String, text: String, atEpochSeconds: Long) {
        browser.mutate { state ->
            val record = ActionRecord(
                surfaceId = CompanionAdapter.SURFACE,
                componentId = "root",
                actor = PeerIdentity.human("iain"),
                name = CompanionAdapter.CHOICE,
                context = buildJsonObject {
                    put("choice", choice)
                    put("text", text)
                },
                basis = Basis.capture(state, CompanionAdapter.SURFACE),
                at = Instant.fromEpochSeconds(atEpochSeconds),
            )
            state.appendRecord(ReplicaId("web-iain"), record)
        }
    }

    /** The events file's lines, once it has [count] of them. */
    suspend fun eventLines(count: Int): List<String> = withTimeout(WAIT) {
        while (lines().size < count) delay(100.milliseconds)
        lines()
    }

    fun lines(): List<String> = if (events.exists()) events.readText().lines().filter { it.isNotBlank() } else emptyList()

    companion object {
        val WAIT: Duration = 60.seconds

        const val FRAME_TEMPLATE = "<!DOCTYPE html>\n<html><head><style>.frame-css { color: teal; }</style></head>" +
            "<body><!-- BRANDING --><div id=\"frame-content\"><!-- CONTENT --></div></body></html>"

        /** The html of the companion surface's `Sketch`, or null while it has none. */
        fun sketchHtml(state: BoardState): String? {
            val view = state.surfaceDoc(CompanionAdapter.SURFACE)?.toSurfaceView(CompanionAdapter.SURFACE) ?: return null
            val root = view.components["root"] ?: return null
            return (root["html"] as? JsonPrimitive)?.content
        }
    }
}
