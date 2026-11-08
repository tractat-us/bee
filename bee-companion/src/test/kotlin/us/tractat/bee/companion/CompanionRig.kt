package us.tractat.bee.companion

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
import us.tractat.bee.core.BoardId
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
 * Two rigs over one [dir] are one companion's directories on two hosts' boards, as a restart
 * against a new or a crashed host is: [board] is the id that rig's host serves.
 *
 * @param maxFrameBytes when set, every spoke's link to the hub publishes this frame ceiling, as a
 *   real fabric would, so the seams above it publish a payload budget.
 */
internal class CompanionRig(
    private val test: TestScope,
    val dir: Path,
    private val maxFrameBytes: Int? = null,
    val board: BoardId = BoardId("board-rig"),
) {
    val star = BoardStar(test)
    val claims = ClaimsRig(test)
    lateinit var voter: ClaimsVoter
    val frameTemplate: Path = dir.resolve("frame-template.html").also { it.writeText(FRAME_TEMPLATE) }

    /** The directories every helper uses unless it's told others. */
    val dirs: Dirs = dirs("")
    val stateDir: Path get() = dirs.state
    val events: Path get() = dirs.events
    private val dispatcher = StandardTestDispatcher(test.testScheduler)
    private var companions = 0
    private var screens = 0
    private val running = mutableListOf<Running>()
    private var lastSurface: String? = null

    /** The surface of the companion started last: the one the helpers act on unless told another. */
    val surface: String get() = checkNotNull(lastSurface) { "rig: no companion has started" }

    /** A companion's screen and state directories, under [dir]/[name]. */
    fun dirs(name: String): Dirs {
        val root = dir.resolve(name)
        return Dirs(Files.createDirectories(root.resolve("content")), Files.createDirectories(root.resolve("state")))
    }

    /** One companion's `screen_dir` and `state_dir`. */
    class Dirs(val screens: Path, val state: Path) {
        val events: Path get() = state.resolve("events")
    }

    suspend fun start() {
        star.start()
        voter = ClaimsVoter.start(test.backgroundScope, claims.host, claims.store, claims.raftConfig, boardMirror(star.relay), claims.clock)
    }

    /**
     * Stops the voter before the test's scope ends: cancelled with it, its relay throws (spike 4
     * notes). A companion that failed when the test didn't ask for its failure fails the test.
     */
    suspend fun close() {
        voter.close()
        running.firstOrNull { it.failedUnseen }?.let { throw AssertionError("A companion failed", it.failed.getCompleted()) }
    }

    /** A browser's replica, online, through the same kind of link as the companion's. */
    fun browser(name: String = "web-iain"): Browser {
        val author = ReplicaId(name)
        return Browser(author, star.client(author, loom = loom(name)).also { it.connect() })
    }

    /** A browser's replica and the author it writes as: each browser appends its clicks as itself. */
    class Browser(val author: ReplicaId, val client: BoardClient)

    /**
     * Spokes, by loom name, that don't get the board yet: every frame from the hub over
     * [LATE_FRAME_BYTES] is lost on the way, as a relay that's slow to send it would be. The small
     * frames (the mesh handshake, presence) still pass, so the spoke goes online. Released, the
     * Quilter's anti-entropy heals the board within seconds.
     */
    private val heldBack = mutableSetOf<String>()

    fun holdBoardBack(name: String) {
        heldBack += name
    }

    fun releaseBoard(name: String) {
        heldBack -= name
    }

    /** Spokes, by loom name, whose every frame to the hub takes [SLOW_UPLINK] to leave, as on a slow link. */
    private val slowed = mutableSetOf<String>()

    fun slowUplink(name: String) {
        slowed += name
    }

    /** A spoke loom like [BoardStar.loom], whose link publishes [maxFrameBytes] when the rig sets it. */
    private fun loom(name: String, scope: CoroutineScope = test.backgroundScope): SpokeLoom =
        SpokeLoom(scope, name, Random(name.hashCode()), star.clock) { id, _ ->
            val (hubEnd, spokeEnd) = connectionPair()
            star.hubEnds += hubEnd
            star.source.offer(hubEnd)
            val shaped = Slow(Late(spokeEnd) { name in heldBack }) { name in slowed }
            val link = maxFrameBytes?.let { Budgeted(shaped, it) } ?: shaped
            peerMesh(id, listOf(link), dispatcher)
        }

    /** A link that publishes [maxFrameBytes], as a framed transport does. */
    private class Budgeted(base: Connection, override val maxFrameBytes: Int) : Connection by base

    /** A link that loses the hub's large frames while [held]. */
    private class Late(base: Connection, held: () -> Boolean) : Connection by base {
        override val incoming: Flow<ByteArray> = base.incoming.filter { !held() || it.size <= LATE_FRAME_BYTES }
    }

    /** A link whose frames take [SLOW_UPLINK] to leave while [slow]; one its sender stops waiting for is lost. */
    private class Slow(private val base: Connection, private val slow: () -> Boolean) : Connection by base {
        override suspend fun send(frame: ByteArray) {
            if (slow()) delay(SLOW_UPLINK)
            base.send(frame)
        }
    }

    /**
     * A companion over [dirs], joined like a session, running on a fresh child scope. Its adapter
     * runs under a supervisor, so a failure is kept for [Running.awaitFailure], not thrown at the test.
     */
    suspend fun companion(dirs: Dirs = this.dirs): Running {
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
        val presence = Presence(client, author, connection.link.filterNotNull().map { it.node }, claims.clock, scope) { throw it }
        val bridge = BeeBridge(client, author, connection, identity, presence, claims.clock, scope).also { it.announce() }
        connection.link.filterNotNull().first()
        val reports = mutableListOf<JsonObject>()
        val adapter = CompanionAdapter(
            screenDir = dirs.screens,
            stateDir = dirs.state,
            frameTemplate = frameTemplate,
            client = client,
            author = author,
            bridge = bridge,
            actions = BoardActionSource(bridge),
            clock = claims.clock,
            report = { reports += it },
            board = board,
            random = Random(n),
        )
        val failed = CompletableDeferred<Throwable>()
        val supervised = CoroutineScope(
            scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + CoroutineExceptionHandler { _, e -> failed.complete(e) },
        )
        lastSurface = adapter.surface
        return Running(author.value, adapter, client, bridge, reports, failed, scope, adapter.start(supervised)).also { running += it }
    }

    /** A companion the rig started, under its loom [name]. */
    class Running(
        val name: String,
        val adapter: CompanionAdapter,
        val client: BoardClient,
        val bridge: BeeBridge,
        val reports: List<JsonObject>,
        val failed: CompletableDeferred<Throwable>,
        private val scope: CoroutineScope,
        private val job: Job,
    ) {
        val surface: String get() = adapter.surface
        private var failureSeen = false

        /** True when the adapter failed and the test never asked for that failure. */
        val failedUnseen: Boolean get() = failed.isCompleted && !failureSeen

        /** The adapter's failure, once it fails. */
        suspend fun awaitFailure(): Throwable {
            failureSeen = true
            return failed.await()
        }

        /** Stops the companion and everything it ran, as a killed process would. */
        suspend fun stop() {
            job.cancelAndJoin()
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    /** Writes screen [name] with [html] into [dirs], newer than every screen before it. */
    fun screen(name: String, html: String, dirs: Dirs = this.dirs): Path {
        val file = dirs.screens.resolve(name)
        file.writeText(html)
        Files.setLastModifiedTime(file, FileTime.fromMillis(1_706_000_000_000 + 1_000L * ++screens))
        return file
    }

    /** [shows], on [browser]'s board. */
    suspend fun shows(browser: Browser, surface: String = this.surface, html: (String) -> Boolean): BoardState =
        shows(browser.client, surface, html)

    /** Waits on [client]'s board until companion [surface] shows [html], and returns that board. */
    suspend fun shows(client: BoardClient, surface: String = this.surface, html: (String) -> Boolean): BoardState =
        withTimeout(WAIT) {
            client.state.first { state -> sketchHtml(state, surface)?.let(html) == true }
        }

    /** Clicks [choice] on companion [surface]'s sketch from [browser], as the page records it: under its own author. */
    suspend fun click(browser: Browser, choice: String, text: String, atEpochSeconds: Long, surface: String = this.surface) {
        browser.client.mutate { state ->
            val record = ActionRecord(
                surfaceId = surface,
                componentId = "root",
                actor = PeerIdentity.human("iain"),
                name = CompanionAdapter.CHOICE,
                context = buildJsonObject {
                    put("choice", choice)
                    put("text", text)
                },
                basis = Basis.capture(state, surface),
                at = Instant.fromEpochSeconds(atEpochSeconds),
            )
            state.appendRecord(browser.author, record)
        }
    }

    /** [dirs]' events file's lines, once it has [count] of them. */
    suspend fun eventLines(count: Int, dirs: Dirs = this.dirs): List<String> = withTimeout(WAIT) {
        while (lines(dirs).size < count) delay(100.milliseconds)
        lines(dirs)
    }

    fun lines(dirs: Dirs = this.dirs): List<String> =
        if (dirs.events.exists()) dirs.events.readText().lines().filter { it.isNotBlank() } else emptyList()

    companion object {
        val WAIT: Duration = 60.seconds

        /** The largest hub frame a held-back spoke still gets: above the handshake's and presence's. */
        const val LATE_FRAME_BYTES = 2_000

        /** How long a slowed spoke's frame takes to leave for the hub. */
        val SLOW_UPLINK: Duration = 500.milliseconds

        const val FRAME_TEMPLATE = "<!DOCTYPE html>\n<html><head><style>.frame-css { color: teal; }</style></head>" +
            "<body><!-- BRANDING --><div id=\"frame-content\"><!-- CONTENT --></div></body></html>"

        /** The html of companion [surface]'s `Sketch`, or null while it has none. */
        fun sketchHtml(state: BoardState, surface: String): String? {
            val view = state.surfaceDoc(surface)?.toSurfaceView(surface) ?: return null
            val root = view.components["root"] ?: return null
            return (root["html"] as? JsonPrimitive)?.content
        }
    }
}
