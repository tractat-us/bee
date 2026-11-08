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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import us.tractat.bee.a2ui.NOTIONS_CATALOG_ID
import us.tractat.bee.a2ui.SurfaceFormat
import us.tractat.bee.a2ui.SurfaceValidator
import us.tractat.bee.a2ui.toSurfaceView
import us.tractat.bee.core.BoardClient
import us.tractat.bee.core.BoardId
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
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * The brainstorming visual companion's contract, kept on the board (spec §3). Invariant: **what
 * the agent reads from `state_dir/events` is exactly the clicks made on the screen it last pushed,
 * each once**, or the line saying that screen was refused.
 *
 * - The newest `.html` file in [screenDir] (by mtime) is this companion's own [surface],
 *   `companion-<id>`, holding one `Sketch`. Each companion has its own, so two brainstorming
 *   sessions on one board never take each other's clicks. A fragment is wrapped in [frameTemplate], so it gets the companion's CSS; a full
 *   document (`<!DOCTYPE` or `<html`) is shown as written. A file that vanishes between the
 *   listing and the read is skipped; the next poll sees whatever replaced it.
 * - Every new screen is appended to the surface's append-only `/screens`, so a click's basis names
 *   the screen it was made on. A click is written only if its basis saw the latest screen and the
 *   surface is open; any other was made on a screen the agent has moved past, and is dropped.
 * - Each new screen clears `stateDir/events`. A `waiting*.html` screen closes the surface instead
 *   ("clear the board" never deletes). A graceful stop closes it too ([closeSurface]); a crash
 *   leaves it open.
 * - A screen the board refuses (too big for its link, [BoardWriteTooLarge], or an invalid surface,
 *   [ScreenInvalid]) is handled as a close: the surface closes and `/screens` advances, so no
 *   click on the screen before it is written. Its events hold one line,
 *   `{"type":"screen-refused","file":…,"reason":…}`, which the agent reads as it reads clicks.
 * - Each `choice` record on [surface] (and no other) is claimed through Raft ([actions]), appended to
 *   `stateDir/events` as `{"type":"click","choice":…,"text":…,"timestamp":<epoch s>}`, then
 *   completed, so no restart writes it twice. The timestamp is when the click was made.
 * - `stateDir/bee-companion.json`, written whole or not at all, keeps the [board] it's on, its
 *   [surface] there, and the last screen handled, so a restart neither pushes that screen again
 *   nor clears its events. It also says whether the board had the surface then; if it did, a
 *   restart on the same board waits for the surface to arrive and never opens a second one. That
 *   wait is bounded: a board that still lacks it after [surfaceWait] (its host crashed before its
 *   save) fails the companion with [SurfaceMissing]. A file naming another board (a new host, a
 *   moved `board/`, another `BEE_HOST`) counts as none: the companion starts over, under a fresh id.
 *
 * The companion joins the board as a session does: [bridge] carries its identity, presence and
 * claims. It writes the surface through [client] as [author], validating it first (A11), because
 * the bridge's tools have no append or reopen.
 *
 * @param report receives what the companion did, as the companion server's stdout lines do:
 *   `screen-shown`, `screen-closed`, `screen-refused` (with its `reason`) and `click-dropped`.
 * @param board the board the host serves ([BoardId.PATH]).
 * @param random mints this companion's surface id, when `bee-companion.json` has none for [board].
 * @param settle how long a companion that never saw the surface waits, once online, for an
 *   existing one to arrive before opening a new one. kuilt has no "caught up with the relay"
 *   signal (tractat-us/kuilt#3142), so it's a bound, not a proof.
 * @param surfaceWait how long a companion whose state says the board had its surface waits for it.
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
    private val board: BoardId,
    random: Random,
    private val pollEvery: Duration = 250.milliseconds,
    private val settle: Duration = 2.seconds,
    private val surfaceWait: Duration = 60.seconds,
) {
    private val template = frameTemplate.readText()
    private val events = stateDir.resolve(EVENTS)
    private val keptFile = stateDir.resolve(KEPT)

    /** What [keptFile] holds, or will once [start] writes it. Changed only under [turn]. */
    private var kept: Kept = readKept()?.takeIf { it.board == board }
        ?: Kept(board, "$SURFACE_PREFIX${random.nextBytes(SURFACE_ID_BYTES).toHexString()}", shown = null)

    /** The surface this companion keeps on the board: its own, `companion-<id>`. */
    val surface: String = kept.surface

    /** Screen pushes and event writes, one at a time: a click is checked against the screen it lands under. */
    private val turn = Mutex()

    /** Starts watching [screenDir] and handling clicks, once the board link is up and holds what it must. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        writeKept(kept)
        client.online.first { it }
        awaitSurface()
        launch { handleClicks() }
        while (true) {
            sync()
            delay(pollEvery)
        }
    }

    /**
     * Waits for [surface]. A companion whose state says the board had it waits up to [surfaceWait],
     * because opening it again would race the one on the board, then fails. One that never saw it
     * can only wait [settle] (tractat-us/kuilt#3142).
     *
     * @throws SurfaceMissing if the board said to have it still lacks it after [surfaceWait]
     */
    private suspend fun awaitSurface() {
        val held = { state: BoardState -> state.surfaceDoc(surface) != null }
        if (kept.shown?.hadSurface == true) {
            logger.info { "$KEPT says $board has surface $surface: waiting up to $surfaceWait for it to arrive." }
            withTimeoutOrNull(surfaceWait) { client.state.first(held) } ?: throw SurfaceMissing(board, surface)
        } else {
            withTimeoutOrNull(settle) { client.state.first(held) }
        }
    }

    /**
     * Closes [surface], as a graceful stop does, so a finished session leaves no open, dead Sketch
     * on the board, and waits until the host has the close. Unbounded: the caller bounds it. Only
     * the surface closes: `bee-companion.json` still names the screen shown, so a restart over the
     * same directories leaves the surface closed until the agent pushes a new screen.
     *
     * @return whether the host acknowledged the close; false when the companion is offline
     */
    suspend fun closeSurface(): Boolean = turn.withLock {
        client.mutateOrSkip { state ->
            val open = state.surfaceDoc(surface) != null && state.meta(surface)?.closedAt == null
            if (open) state.closeSurface(author, surface, clock()) else null
        }
        client.awaitAcknowledged()
    }

    /** Shows the newest screen if it isn't the one last handled. */
    private suspend fun sync() = turn.withLock {
        val screen = newestScreen(screenDir.listDirectoryEntries("*.html")) ?: return@withLock
        val last = kept.shown
        if (last != null && last.file == screen.name && last.mtime == screen.mtime) return@withLock
        // Gone since it was listed: the next poll sees whatever replaced it.
        val content = readOrNull(screen.path) ?: return@withLock
        val waiting = isWaiting(screen.name)
        val refusal = try {
            write(screen.name, if (waiting) null else framed(content))
            null
        } catch (refused: BoardWriteTooLarge) {
            refused.message
        } catch (refused: ScreenInvalid) {
            refused.message
        }
        // A refused screen closes the surface and advances /screens, so the screen before it takes no more clicks.
        if (refusal != null) write(screen.name, null)
        events.deleteIfExists()
        if (refusal == null) {
            report(event(if (waiting) "screen-closed" else "screen-shown", screen.name))
        } else {
            logger.warn { "Screen ${screen.name} was refused: $refusal" }
            val line = event("screen-refused", screen.name) { put("reason", refusal) }
            append(line)
            report(line)
        }
        // A refused screen counts as handled too, so it isn't retried every poll.
        kept = kept.copy(shown = Shown(screen.name, screen.mtime, hadSurface = client.state.value.surfaceDoc(surface) != null))
        writeKept(kept)
    }

    /** Writes screen [file] to the board: [html] as the Sketch, or a close when null. */
    private suspend fun write(file: String, html: String?) {
        client.mutateOrSkip { state -> screenPatch(state, file, html) }
    }

    /**
     * The surface write for [file]: [html] as the Sketch, or a close when null, plus its `/screens`
     * entry. Null when there's nothing to write: a close before any surface exists.
     *
     * @throws ScreenInvalid if the surface it would leave fails validation
     */
    private fun screenPatch(state: BoardState, file: String, html: String?): Patch<BoardState>? {
        val exists = state.surfaceDoc(surface) != null
        val closed = state.meta(surface)?.closedAt != null
        val writes = buildList<(BoardState) -> Patch<BoardState>> {
            when {
                html == null && exists && !closed -> add { it.closeSurface(author, surface, clock()) }
                html == null -> Unit
                !exists -> add { it.openSurface(author, surface, meta(), listOf(sketch(html, file)), JsonObject(emptyMap())) }
                else -> {
                    if (closed) add { it.reopenSurface(author, surface) }
                    add { it.updateComponents(author, surface, listOf(sketch(html, file))) }
                }
            }
            if (exists || html != null) add { it.appendEntry(author, surface, SCREENS, JsonPrimitive(file)) }
        }
        if (writes.isEmpty()) return null
        val (after, delta) = writes.fold(state to BoardState.EMPTY) { (s, d), write ->
            val step = write(s).delta
            s.piece(step) to d.piece(step)
        }
        val errors = after.surfaceDoc(surface)?.toSurfaceView(surface)?.let(SurfaceValidator::errors).orEmpty()
        if (errors.isNotEmpty()) throw ScreenInvalid(surface, errors)
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

    private fun isWaiting(file: String) = file.startsWith("waiting")

    /** What [keptFile] says, or null when there's none. Every field it holds is required. */
    private fun readKept(): Kept? {
        if (!keptFile.exists()) return null
        val kept = Kept.of(Json.parseToJsonElement(keptFile.readText()).jsonObject)
        if (kept.board != board) logger.info { "$KEPT names ${kept.board}, not $board: the companion starts over on this board." }
        return kept
    }

    /** Replaces [keptFile] whole, so a crash mid-write never leaves a file a restart can't read. */
    private fun writeKept(kept: Kept) {
        val temp = Files.createTempFile(stateDir, ".$KEPT", ".tmp")
        temp.writeText(Json.encodeToString(JsonObject.serializer(), kept.json()))
        Files.move(temp, keptFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    // ---- Clicks ----

    private suspend fun handleClicks() {
        var cursor: String? = null
        while (true) {
            val result = try {
                actions.next(surface, cursor, WAIT)
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
            val made = state.records(surface).single { it.first == id }.second
            val latest = latestScreen(state, surface)
            // A closed surface's latest screen is a waiting or refused one, which takes no clicks.
            val open = state.meta(surface)?.closedAt == null
            when {
                made.name != CHOICE -> Unit
                open && latest != null && made.basis.saw(SCREENS, latest) -> append(click(made.context, made.at))
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

    /** What `bee-companion.json` keeps: the [board] it's on, its [surface] there, and the last screen handled. */
    private data class Kept(val board: BoardId, val surface: String, val shown: Shown?) {
        fun json() = buildJsonObject {
            put("board", board.value)
            put("surface", surface)
            shown?.let { put("shown", it.json()) }
        }

        companion object {
            fun of(json: JsonObject) = Kept(
                board = BoardId(json.getValue("board").jsonPrimitive.content),
                surface = json.getValue("surface").jsonPrimitive.content,
                shown = json["shown"]?.jsonObject?.let(Shown::of),
            )
        }
    }

    /** The last screen handled, by name and mtime, and whether the board had the surface after it. */
    private data class Shown(val file: String, val mtime: Long, val hadSurface: Boolean) {
        fun json() = buildJsonObject {
            put("file", file)
            put("mtime", mtime)
            put("hadSurface", hadSurface)
        }

        companion object {
            fun of(json: JsonObject) = Shown(
                file = json.getValue("file").jsonPrimitive.content,
                mtime = json.getValue("mtime").jsonPrimitive.long,
                hadSurface = json.getValue("hadSurface").jsonPrimitive.boolean,
            )
        }
    }

    /** A screen file as one listing saw it. */
    internal data class Screen(val path: Path, val mtime: Long) {
        val name: String get() = path.name
    }

    /** A surface the validator refused: the companion's own write, so the board is unchanged (A11). */
    class ScreenInvalid(surface: String, errors: List<String>) : Exception("Surface $surface was not written: ${errors.joinToString(" ")}")

    /**
     * The board lacks the surface `bee-companion.json` says it had, past the wait: its host lost it
     * (it crashed before its save). Opening it again could race a copy still on its way, so the
     * companion stops and the person decides.
     */
    class SurfaceMissing(board: BoardId, surface: String) :
        Exception("$board lacks the surface $surface that $KEPT says it had; delete the file to reopen")

    companion object {
        /** Every companion's surface id starts so; a random id follows, one per companion. */
        const val SURFACE_PREFIX = "companion-"
        private const val SURFACE_ID_BYTES = 6

        /** The action a `Sketch` reports a `[data-choice]` click as (bee-notions/src/sketch.js). */
        const val CHOICE = "choice"

        /** The surface's append-only list of the screens pushed to it, by file name. */
        const val SCREENS = "/screens"

        const val TITLE = "Brainstorming companion"
        private const val EVENTS = "events"
        private const val KEPT = "bee-companion.json"
        private const val CONTENT = "<!-- CONTENT -->"
        private const val BRANDING = "<!-- BRANDING -->"
        private val WAIT = 45.seconds

        /** How many screens have been pushed to companion [surface] on [state]. */
        fun screensShown(state: BoardState, surface: String): Int = screens(state, surface).size

        /** The dot of the latest screen pushed, as [state] holds [surface]. */
        internal fun latestScreen(state: BoardState, surface: String): Dot? = screens(state, surface).lastOrNull()

        /**
         * The newest of [entries] by mtime (ties by name), skipping dot files, anything not a
         * regular file, and any file gone since it was listed.
         */
        internal fun newestScreen(entries: List<Path>): Screen? = entries
            .filter { !it.name.startsWith(".") }
            .mapNotNull(::statOrNull)
            .maxWithOrNull(compareBy<Screen> { it.mtime }.thenBy { it.name })

        private fun statOrNull(path: Path): Screen? = try {
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
            if (attributes.isRegularFile) Screen(path, attributes.lastModifiedTime().toMillis()) else null
        } catch (_: NoSuchFileException) {
            null
        }

        private fun readOrNull(path: Path): String? = try {
            path.readText()
        } catch (_: NoSuchFileException) {
            null
        }

        private fun screens(state: BoardState, surface: String): List<Dot> {
            val data = state.surfaceDoc(surface)?.get(SurfaceFormat.DATA) as? JsonNode.Object ?: return emptyList()
            val array = data.map[SCREENS.removePrefix("/")] as? JsonNode.Array ?: return emptyList()
            return array.rga.entries().map { it.first.dot }
        }
    }
}
