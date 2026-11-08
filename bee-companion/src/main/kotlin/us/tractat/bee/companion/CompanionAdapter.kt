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
 * - The newest `.html` file in [screenDir] (by mtime) is the surface [SURFACE], holding one
 *   `Sketch`. A fragment is wrapped in [frameTemplate], so it gets the companion's CSS; a full
 *   document (`<!DOCTYPE` or `<html`) is shown as written. A file that vanishes between the
 *   listing and the read is skipped; the next poll sees whatever replaced it.
 * - Every new screen is appended to the surface's append-only `/screens`, so a click's basis names
 *   the screen it was made on. A click is written only if its basis saw the latest screen and the
 *   surface is open; any other was made on a screen the agent has moved past, and is dropped.
 * - Each new screen clears `stateDir/events`. A `waiting*.html` screen closes the surface instead
 *   ("clear the board" never deletes).
 * - A screen the board refuses (too big for its link, [BoardWriteTooLarge], or an invalid surface,
 *   [ScreenInvalid]) is handled as a close: the surface closes and `/screens` advances, so no
 *   click on the screen before it is written. Its events hold one line,
 *   `{"type":"screen-refused","file":…,"reason":…}`, which the agent reads as it reads clicks.
 * - Each `choice` record on the surface is claimed through Raft ([actions]), appended to
 *   `stateDir/events` as `{"type":"click","choice":…,"text":…,"timestamp":<epoch s>}`, then
 *   completed, so no restart writes it twice. The timestamp is when the click was made.
 * - The last screen handled is kept in `stateDir/bee-companion.json`, written whole or not at all,
 *   so a restart neither pushes it again nor clears its events. It also says whether the board
 *   had the surface then; if it did, a restart waits for the surface to arrive and never opens a
 *   second one.
 *
 * The companion joins the board as a session does: [bridge] carries its identity, presence and
 * claims. It writes the surface through [client] as [author], validating it first (A11), because
 * the bridge's tools have no append or reopen.
 *
 * @param report receives what the companion did, as the companion server's stdout lines do:
 *   `screen-shown`, `screen-closed`, `screen-refused` (with its `reason`) and `click-dropped`.
 * @param settle how long a companion that never saw the surface waits, once online, for an
 *   existing one to arrive before opening a new one. kuilt has no "caught up with the relay"
 *   signal (tractat-us/kuilt#3142), so it's a bound, not a proof.
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

    /** Starts watching [screenDir] and handling clicks, once the board link is up and holds what it must. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        client.online.first { it }
        awaitSurface()
        launch { handleClicks() }
        while (true) {
            sync()
            delay(pollEvery)
        }
    }

    /**
     * Waits for the board's companion surface. A companion whose state says the board had it waits
     * as long as that takes, because opening it again would race the one on the board. One that
     * never saw it can only wait [settle] (tractat-us/kuilt#3142).
     */
    private suspend fun awaitSurface() {
        val held = { state: BoardState -> state.surfaceDoc(SURFACE) != null }
        if (lastShown()?.surface == true) {
            logger.info { "$SHOWN says the board has surface $SURFACE: waiting for it to arrive." }
            client.state.first(held)
        } else {
            withTimeoutOrNull(settle) { client.state.first(held) }
        }
    }

    /** Shows the newest screen if it isn't the one last handled. */
    private suspend fun sync() = turn.withLock {
        val screen = newestScreen(screenDir.listDirectoryEntries("*.html")) ?: return@withLock
        val last = lastShown()
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
        writeShown(Shown(screen.name, screen.mtime, surface = client.state.value.surfaceDoc(SURFACE) != null))
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
        val errors = after.surfaceDoc(SURFACE)?.toSurfaceView(SURFACE)?.let(SurfaceValidator::errors).orEmpty()
        if (errors.isNotEmpty()) throw ScreenInvalid(errors)
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

    private fun lastShown(): Shown? {
        if (!shownFile.exists()) return null
        val json = Json.parseToJsonElement(shownFile.readText()).jsonObject
        return Shown(
            file = json.getValue("file").jsonPrimitive.content,
            mtime = json.getValue("mtime").jsonPrimitive.long,
            // A file from before this field was kept: it exists, so a surface may well be on the board.
            surface = json["surface"]?.jsonPrimitive?.boolean ?: true,
        )
    }

    /** Replaces [shownFile] whole, so a crash mid-write never leaves a file a restart can't read. */
    private fun writeShown(shown: Shown) {
        val temp = Files.createTempFile(stateDir, ".$SHOWN", ".tmp")
        temp.writeText(Json.encodeToString(JsonObject.serializer(), shown.json()))
        Files.move(temp, shownFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
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
            // A closed surface's latest screen is a waiting or refused one, which takes no clicks.
            val open = state.meta(SURFACE)?.closedAt == null
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

    /** The last screen handled, by name and mtime, and whether the board had the surface after it. */
    private data class Shown(val file: String, val mtime: Long, val surface: Boolean) {
        fun json() = buildJsonObject {
            put("file", file)
            put("mtime", mtime)
            put("surface", surface)
        }
    }

    /** A screen file as one listing saw it. */
    internal data class Screen(val path: Path, val mtime: Long) {
        val name: String get() = path.name
    }

    /** A surface the validator refused: the companion's own write, so the board is unchanged (A11). */
    class ScreenInvalid(errors: List<String>) : Exception("Surface $SURFACE was not written: ${errors.joinToString(" ")}")

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

        private fun screens(state: BoardState): List<Dot> {
            val data = state.surfaceDoc(SURFACE)?.get(SurfaceFormat.DATA) as? JsonNode.Object ?: return emptyList()
            val array = data.map[SCREENS.removePrefix("/")] as? JsonNode.Array ?: return emptyList()
            return array.rga.entries().map { it.first.dot }
        }
    }
}
