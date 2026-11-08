package us.tractat.bee.web

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.url.URLSearchParams
import us.tractat.bee.core.ActionRecord
import us.tractat.bee.core.BoardClient
import us.tractat.bee.core.BoardId
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.SpokeLoom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.VersionVector
import us.tractat.kuilt.websocket.WebSocketAdvertisement
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val log = KotlinLogging.logger("us.tractat.bee.web.Main")

/** The host's board relay route (bee-host's `BoardRelay.PATH`). */
private const val BOARD_PATH = "/ws/board"

/**
 * The bee page: this tab's replica of the board, behind the stock renderer.
 *
 * The person is `human:<name>`, from `?name=` (A7's `localStorage` name comes with join, Task 7).
 * The CRDT author is fresh per page load, `web-<uuid>`. The page shows whether it is online, and
 * why not, and lists the board's action records with what each one's basis saw.
 *
 * The page starts from this browser's copy of the board ([BrowserStore]), for the board the host
 * names at [BoardId.PATH]. The copy holds what a tab did while the host was down: close that tab,
 * and the next page opened once the host is back starts from the copy and sends it on. The host
 * serves this page and bee has no service worker, so with the host down a reload shows the
 * browser's error page, not the board. `#store` says whether this tab is the one keeping the copy
 * (`data-writer`).
 */
@OptIn(ExperimentalUuidApi::class)
fun main() {
    val root = document.getElementById("board") ?: error("index.html has no #board element")
    val status = document.body!!.appendElement("p", "status")
    val problem = document.body!!.appendElement("p", "problem")
    val store = document.body!!.appendElement("p", "store")
    val records = document.body!!.appendElement("ol", "records")

    val name = URLSearchParams(window.location.search.toJsString()).get("name")?.takeIf { it.isNotBlank() } ?: "guest"
    val actor = PeerIdentity.human(name)
    val author = ReplicaId("web-${Uuid.random()}")
    val clock = { Clock.System.now() }
    // A page whose rendering died must never go on saying "online": a coroutine that fails stops
    // the whole page, and the page says why.
    lateinit var scope: CoroutineScope
    val crashed = CoroutineExceptionHandler { _, failure ->
        scope.cancel()
        status.textContent = "stopped"
        problem.textContent = "The page stopped: ${failure.message ?: failure::class.simpleName}"
    }
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + crashed)
    val http = HttpClient { install(WebSockets) }
    val rendezvous = Rendezvous.Existing(
        WebSocketAdvertisement("ws://${window.location.host}$BOARD_PATH", PeerId("bee-host"), "board"),
    )
    scope.launch {
        val (copy, initial) = restore(askBoardId(), author, store)
        val client = BoardClient(
            author = author,
            loom = SpokeLoom(http, Dispatchers.Default, scope, author.value, Random.Default, clock),
            rendezvous = rendezvous,
            scope = scope,
            clock = clock,
            initial = initial,
        )
        val onProblem: (String) -> Unit = { problem.textContent = it }
        val inputs = LiveInputs(root, client, author, scope, onProblem)
        BoardDriver(client, A2uiRenderer(root), root, author, actor, inputs, clock, onProblem).start(scope)

        scope.launch {
            combine(client.online, client.lastFailure) { online, failure ->
                when {
                    online -> "online as $actor"
                    failure != null -> "offline: ${failure.message ?: failure::class.simpleName}"
                    else -> "offline"
                }
            }.collect { status.textContent = it }
        }
        scope.launch { client.state.collect { showRecords(records, it) } }
        client.connect()
        if (copy != null) keep(copy, client.state, scope, store)
    }
}

/**
 * The board the host names at [BoardId.PATH], or null if the host didn't answer. A plain `fetch`
 * that settles either way: Ktor's wasm engine reports a refused fetch as a `kotlin.Error`, which
 * no `catch (Exception)` sees.
 */
private suspend fun askBoardId(): BoardId? {
    val answer = CompletableDeferred<String?>()
    fetchText(BoardId.PATH) { answer.complete(it) }
    return answer.await()?.takeIf { it.isNotBlank() }?.let(::BoardId)
}

/** Fetches [path] past every cache and calls [onDone] with its text, or null if it failed. */
@JsFun(
    """(path, onDone) => {
        fetch(path, { cache: 'no-store' })
            .then((r) => (r.ok ? r.text() : null))
            .then((text) => onDone(text), () => onDone(null));
    }""",
)
private external fun fetchText(path: String, onDone: (String?) -> Unit)

/** The copy this tab reads and may keep: one [board]'s, in [store]. */
private class Copy(val store: BrowserStore, val board: BoardId)

/**
 * This browser's copy of the board the host named ([served]), and the board it holds, under
 * [author]. A browser that can't keep one (no IndexedDB, no Web Locks, or no board known) starts
 * from an empty board, and [line] says why: the host still has the board. A copy that doesn't
 * read is logged and kept, to be replaced by the writer.
 */
private suspend fun restore(served: BoardId?, author: ReplicaId, line: Element): Pair<Copy?, BoardState> {
    val copy = try {
        BrowserStore.open().let { store -> store.board(served)?.let { Copy(store, it) } }
            ?: return notKept(line, "the host didn't name its board")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        return notKept(line, failure.message ?: failure::class.simpleName.orEmpty())
    }
    val initial = try {
        copy.store.load(copy.board, author) ?: BoardState.EMPTY
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (unreadable: Exception) {
        log.warn(unreadable) { "This browser's copy of ${copy.board} didn't read. The page starts empty, and the next save replaces it." }
        BoardState.EMPTY
    }
    return copy to initial
}

private fun notKept(line: Element, why: String): Pair<Copy?, BoardState> {
    line.setAttribute("data-writer", "false")
    line.textContent = "not kept in this browser: $why"
    return null to BoardState.EMPTY
}

/** Waits to be the tab that keeps [copy], then keeps it for as long as the page lives. */
private suspend fun keep(copy: Copy, board: StateFlow<BoardState>, scope: CoroutineScope, line: Element) {
    line.setAttribute("data-writer", "false")
    line.textContent = "another tab keeps the board in this browser"
    val saver = copy.store.keep(copy.board, board, scope)
    line.setAttribute("data-writer", "true")
    saver.lastFailure.collect { failure ->
        line.textContent = when (failure) {
            null -> "this tab keeps the board in this browser"
            else -> "not saved in this browser: ${failure.message ?: failure::class.simpleName}"
        }
    }
}

private fun Element.appendElement(tag: String, id: String): Element =
    document.createElement(tag).also {
        it.id = id
        appendChild(it)
    }

/** Every record on the board, in sequence order, each with what its basis saw. */
private fun showRecords(list: Element, state: BoardState) {
    val shown = state.records.entries().map { it.second }
    while (list.childElementCount > shown.size) list.lastElementChild?.remove()
    shown.forEachIndexed { index, record ->
        val item = list.children.item(index) ?: list.appendElement("li", "record-$index")
        item.setAttribute("data-surface-id", record.surfaceId)
        item.textContent = describe(record)
    }
}

/** `human:alex press on press-button, having seen /options 1, records 0`. */
private fun describe(record: ActionRecord): String {
    val basis = record.basis
    val arrays = (basis.surfaceDots.keys + basis.surfaceFloors.keys).sorted().map { pointer ->
        "$pointer ${seen(basis.surfaceDots[pointer].orEmpty(), basis.surfaceFloors[pointer] ?: VersionVector.EMPTY)}"
    }
    val saw = (arrays + "records ${seen(basis.recordDots, basis.recordFloor)}").joinToString(", ")
    return "${record.actor} ${record.name} on ${record.componentId}, having seen $saw"
}

/** How many entries a basis names: everything up to the floor (gap-free from 1), plus the dots above it. */
private fun seen(dots: Set<Dot>, floor: VersionVector): Long = floor.entries.values.sum() + dots.size
