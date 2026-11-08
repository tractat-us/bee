package us.tractat.bee.companion

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import us.tractat.bee.a2ui.NOTIONS_CATALOG_ID
import us.tractat.bee.a2ui.SurfaceFormat
import us.tractat.bee.a2ui.SurfaceValidator
import us.tractat.bee.a2ui.toSurfaceView
import us.tractat.bee.core.BoardClient
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.BoardWriteTooLarge
import us.tractat.bee.core.ClaimTarget
import us.tractat.bee.core.ClaimToken
import us.tractat.bee.core.SurfaceMeta
import us.tractat.bee.mcp.ActionSource
import us.tractat.bee.mcp.BeeBridge
import us.tractat.bee.mcp.BoardActionSource
import us.tractat.bee.mcp.BridgeRefusal
import us.tractat.bee.mcp.ClaimsOfflineRefusal
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.JsonNode
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * The brainstorming visual companion's contract, kept on the board (spec §3). Invariant: **what
 * the agent reads from `state_dir/events` is exactly the clicks made on the screen it last pushed,
 * each once.**
 *
 * - The newest `.html` file in [screenDir] (by mtime) is the surface [SURFACE], holding one
 *   `Sketch`. A fragment is wrapped in [frameTemplate], so it gets the companion's CSS; a full
 *   document (`<!DOCTYPE` or `<html`) is shown as written.
 * - Every new screen is appended to the surface's append-only `/screens`, so a click's basis names
 *   the screen it was made on. A click whose basis didn't see the latest screen was made on one
 *   the agent has moved past, and is dropped, never written into the new screen's events.
 * - Each new screen clears `stateDir/events`. A `waiting*.html` screen closes the surface instead
 *   ("clear the board" never deletes).
 * - Each `choice` record on the surface is claimed through Raft ([actions]), appended to
 *   `stateDir/events` as `{"type":"click","choice":…,"text":…,"timestamp":<epoch s>}`, then
 *   completed, so no restart writes it twice. The timestamp is when the click was made.
 * - The last screen pushed is kept in `stateDir/bee-companion.json`, so a restart neither pushes
 *   it again nor clears its events.
 *
 * The companion joins the board as a session does: [bridge] carries its identity, presence and
 * claims. It writes the surface through [client] as [author], validating it first (A11), because
 * the bridge's tools have no append or reopen. A screen too big for the board link is refused
 * ([BoardWriteTooLarge]) and reported; the board keeps the screen before it.
 *
 * @param report receives what the companion did, as the companion server's stdout lines do:
 *   `screen-shown`, `screen-closed`, `screen-refused` (with its `reason`) and `click-dropped`.
 * @param settle how long to wait, once online, for an existing companion surface to arrive before
 *   opening a new one. kuilt has no "caught up with the relay" signal (tractat-us/kuilt#3142), so
 *   it's a bound, not a proof.
 */
@Suppress("LongParameterList")
class CompanionAdapter(
    private val screenDir: Path,
    private val stateDir: Path,
    frameTemplate: Path,
    private val client: BoardClient,
    private val author: ReplicaId,
    private val bridge: BeeBridge,
    private val actions: ActionSource,
    private val clock: () -> Instant,
    private val report: (JsonObject) -> Unit,
    private val pollEvery: Duration = 250.milliseconds,
    private val settle: Duration = 2.seconds,
) {
    private val template = frameTemplate.readText()
    private val events = stateDir.resolve(EVENTS)
    private val shownFile = stateDir.resolve(SHOWN)

    /** Screen pushes and event writes, one at a time: a click is checked against the screen it lands under. */
    private val turn = Mutex()

    /** Starts watching [screenDir] and handling clicks, once the board link is up and has settled. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        client.online.first { it }
        withTimeoutOrNull(settle) { client.state.first { it.surfaceDoc(SURFACE) != null } }
        launch { handleClicks() }
        while (true) {
            sync()
            delay(pollEvery)
        }
    }

    /** Pushes the newest screen if it isn't the one last pushed. */
    private suspend fun sync() = turn.withLock {
        val newest = newestScreen() ?: return@withLock
        val stamp = Shown(newest.name, newest.getLastModifiedTime().toMillis())
        if (stamp == lastShown()) return@withLock
        try {
            push(newest)
            report(event("screen-${if (isWaiting(newest)) "closed" else "shown"}", newest.name))
        } catch (refused: IllegalArgumentException) {
            // BoardWriteTooLarge, or a surface the validator refused: the board keeps the screen before.
            logger.warn { "Screen ${newest.name} was refused: ${refused.message}" }
            report(event("screen-refused", newest.name) { put("reason", refused.message.orEmpty()) })
        }
        // A refused screen counts as handled too, so it isn't retried every poll.
        shownFile.writeText(Json.encodeToString(JsonObject.serializer(), stamp.json()))
    }

    private suspend fun push(screen: Path) {
        val waiting = isWaiting(screen)
        val html = if (waiting) null else framed(screen.readText())
        // Board first: if the write is refused, the events of the screen still shown are kept.
        client.mutateOrSkip { state -> screenPatch(state, screen.name, html) }
        events.deleteIfExists()
    }

    /**
     * The surface write for [file]: [html] as the Sketch, or a close when null, plus its `/screens`
     * entry. Null when there's nothing to write: a waiting screen before any surface exists.
     */
    private fun screenPatch(state: BoardState, file: String, html: String?): Patch<BoardState>? {
        val exists = state.surfaceDoc(SURFACE) != null
        val closed = state.meta(SURFACE)?.closedAt != null
        val writes = buildList<(BoardState) -> Patch<BoardState>> {
            when {
                html == null && exists && !closed -> add { it.closeSurface(author, SURFACE, clock()) }
                html == null -> Unit
                !exists -> add { it.openSurface(author, SURFACE, meta(), listOf(sketch(html, file)), JsonObject(emptyMap())) }
                else -> {
                    if (closed) add { it.reopenSurface(author, SURFACE) }
                    add { it.updateComponents(author, SURFACE, listOf(sketch(html, file))) }
                }
            }
            if (exists || html != null) add { it.appendEntry(author, SURFACE, SCREENS, JsonPrimitive(file)) }
        }
        if (writes.isEmpty()) return null
        val (after, delta) = writes.fold(state to BoardState.EMPTY) { (s, d), write ->
            val step = write(s).delta
            s.piece(step) to d.piece(step)
        }
        after.surfaceDoc(SURFACE)?.toSurfaceView(SURFACE)?.let(SurfaceValidator::errors)?.takeIf { it.isNotEmpty() }?.let {
            throw IllegalArgumentException("Surface $SURFACE was not written: ${it.joinToString(" ")}")
        }
        return Patch(delta)
    }

    private fun meta() = SurfaceMeta(TITLE, NOTIONS_CATALOG_ID, bridge.self, clock())

    private fun sketch(html: String, file: String) = buildJsonObject {
        put("id", "root")
        put("component", SurfaceValidator.SKETCH)
        put("html", html)
        put("title", file)
    }

    /** A fragment inside the frame template; a full document as written (the companion server's rule). */
    private fun framed(content: String): String {
        val head = content.trimStart().lowercase()
        if (head.startsWith("<!doctype") || head.startsWith("<html")) return content
        return template.replace(BRANDING, "").replace(CONTENT, content)
    }

    private fun newestScreen(): Path? = screenDir.listDirectoryEntries("*.html")
        .filter { !it.name.startsWith(".") && it.isRegularFile() }
        .maxWithOrNull(compareBy<Path> { it.getLastModifiedTime().toMillis() }.thenBy { it.name })

    private fun isWaiting(screen: Path) = screen.name.startsWith("waiting")

    private fun lastShown(): Shown? {
        if (!shownFile.exists()) return null
        val json = Json.parseToJsonElement(shownFile.readText()).jsonObject
        return Shown(json.getValue("file").jsonPrimitive.content, json.getValue("mtime").jsonPrimitive.long)
    }

    // ---- Clicks ----

    private suspend fun handleClicks() {
        var cursor: String? = null
        while (true) {
            val result = try {
                actions.next(SURFACE, cursor, WAIT)
            } catch (refused: BridgeRefusal) {
                // Retired mid-wait (the host lost this companion's link): it acts under a fresh
                // identity now, and its orphaned claims wait for a participant to decide.
                logger.warn { refused.message }
                continue
            }
            cursor = result.cursor
            result.record?.let { handle(it) }
        }
    }

    /** Writes [record] to the events file if it's a click on the latest screen, then completes its claim. */
    private suspend fun handle(record: JsonObject) {
        val id = BoardActionSource.parseRecordId(record.getValue("recordId").jsonPrimitive.content)
        val token = BoardActionSource.parseToken(record.getValue("token").jsonPrimitive.content)
        turn.withLock {
            val state = bridge.board.value
            val made = state.records(SURFACE).single { it.first == id }.second
            val latest = latestScreen(state)
            when {
                made.name != CHOICE -> Unit
                latest != null && made.basis.saw(SCREENS, latest) -> append(click(made.context, made.at))
                else -> report(event("click-dropped", null) { put("recordId", record.getValue("recordId")) })
            }
        }
        complete(BoardActionSource.target(id), token)
    }

    private suspend fun complete(target: ClaimTarget, token: ClaimToken) {
        while (true) {
            try {
                if (!bridge.done(target, token)) logger.warn { "Completing $target was rejected: its token is stale." }
                return
            } catch (_: ClaimsOfflineRefusal) {
                bridge.awaitClaims()
            } catch (refused: BridgeRefusal) {
                // Retired mid-click (the host lost this companion's link): the identity is fresh now.
                logger.warn { refused.message }
                return
            }
        }
    }

    private fun click(context: JsonObject, at: Instant) = buildJsonObject {
        put("type", "click")
        context["choice"]?.let { put("choice", it) }
        context["text"]?.let { put("text", it) }
        put("timestamp", at.epochSeconds)
    }

    private fun append(line: JsonObject) {
        Files.writeString(events, line.toString() + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private fun event(type: String, file: String?, more: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("type", type)
        file?.let { put("file", it) }
        more()
    }

    private data class Shown(val file: String, val mtime: Long) {
        fun json() = buildJsonObject {
            put("file", file)
            put("mtime", mtime)
        }
    }

    companion object {
        /** The one surface the companion keeps on the board. */
        const val SURFACE = "companion"

        /** The action a `Sketch` reports a `[data-choice]` click as (bee-notions/src/sketch.js). */
        const val CHOICE = "choice"

        /** The surface's append-only list of the screens pushed to it, by file name. */
        const val SCREENS = "/screens"

        const val TITLE = "Brainstorming companion"
        private const val EVENTS = "events"
        private const val SHOWN = "bee-companion.json"
        private const val CONTENT = "<!-- CONTENT -->"
        private const val BRANDING = "<!-- BRANDING -->"
        private val WAIT = 45.seconds

        /** How many screens have been pushed to the companion surface on [state]. */
        fun screensShown(state: BoardState): Int = screens(state).size

        /** The dot of the latest screen pushed, as [state] holds the surface. */
        internal fun latestScreen(state: BoardState): Dot? = screens(state).lastOrNull()

        private fun screens(state: BoardState): List<Dot> {
            val data = state.surfaceDoc(SURFACE)?.get(SurfaceFormat.DATA) as? JsonNode.Object ?: return emptyList()
            val array = data.map[SCREENS.removePrefix("/")] as? JsonNode.Array ?: return emptyList()
            return array.rga.entries().map { it.first.dot }
        }
    }
}
