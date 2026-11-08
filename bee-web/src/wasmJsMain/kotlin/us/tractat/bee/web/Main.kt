package us.tractat.bee.web

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.browser.document
import kotlinx.browser.sessionStorage
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.w3c.dom.Element
import org.w3c.dom.EventSource
import org.w3c.dom.url.URLSearchParams
import us.tractat.bee.core.ActionRecord
import us.tractat.bee.core.BoardClient
import us.tractat.bee.core.BoardId
import us.tractat.bee.core.BoardPin
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.PairingEnd
import us.tractat.bee.core.PairingMessage
import us.tractat.bee.core.PairingWire
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.Presence
import us.tractat.bee.core.SpokeLoom
import us.tractat.bee.core.pinnedTo
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

/** Where this tab keeps the key pairing gave it. A tab's own storage: a new tab on a LAN device pairs again. */
private const val KEY_STORAGE = "bee.lan.key"

/** How this page may reach the host's board (spec §5.7). */
private sealed interface Access {
    /** The page came from this machine: the host needs no key from it. */
    data object Loopback : Access

    /** The page holds [key], from a join link's `?k=` or from pairing. */
    data class Keyed(val key: String) : Access

    /** The page came over the network with no key: it pairs before it sees anything. */
    data object NeedsPairing : Access
}

/**
 * The bee page: this tab's replica of the board, behind the stock renderer.
 *
 * - **Who.** `?screen=<name>` makes a room screen, the human peer `human:<name>` (A7). Otherwise
 *   the person is `human:<name>`, from `?name=` (A7's `localStorage` name is still to come). The
 *   page has no claims client, so neither ever proposes a claim; a screen must stay that way when
 *   the page gains one.
 * - **Where.** Every socket goes back to the host and scheme the page came from, so a page opened
 *   as `http://tractatus.local:8233/` dials `ws://tractatus.local:8233/…`.
 * - **The key gate.** A page from a non-loopback address with no key opens the pairing channel and
 *   shows its code full screen, and dials the board only once a person typing the code there gives
 *   it a key (§5.7).
 *
 * The CRDT author is fresh per page load, `web-<uuid>`. The page shows each open surface as a card,
 * who is attached ([BoardChrome]), and whether it is online, and why not; its person shows as
 * attached too, through its own presence, which names no claims node. Under "Details" it lists
 * the board's action records with what each one's basis saw.
 *
 * The page starts from this browser's copy of the board ([BrowserStore]), for the board the host
 * names at [BoardId.PATH]. The copy holds what a tab did while the host was down: close that tab,
 * and the next page opened once the host is back starts from the copy and sends it on. The host
 * serves this page and bee has no service worker, so with the host down a reload shows the
 * browser's error page, not the board. `#store` says whether this tab is the one keeping the copy
 * (`data-writer`).
 *
 * Every board socket names the board the page's replica holds ([BoardPin], bee#46). A host that
 * keeps another board refuses it, and the page reloads onto the host's board
 * ([reloadOnAnotherBoard]): a tab left open across a wipe never pushes its old board in.
 */
fun main() {
    val params = URLSearchParams(window.location.search.toJsString())
    val actor = actorOf(params)
    val http = HttpClient { install(WebSockets) }
    when (val access = accessOf(params)) {
        Access.Loopback, is Access.Keyed -> startBoard(http, actor, access)
        Access.NeedsPairing -> pair(params.get(PairingWire.SCREEN_PARAM)) { key -> startBoard(http, actor, Access.Keyed(key)) }
    }
}

private fun actorOf(params: URLSearchParams): PeerIdentity {
    val screen = params.get(PairingWire.SCREEN_PARAM)?.takeIf { it.isNotBlank() }
    val name = screen ?: params.get("name")?.takeIf { it.isNotBlank() } ?: "guest"
    return PeerIdentity.human(name)
}

/**
 * A join link's key moves into this tab's storage and out of the address bar at once, so a
 * screenshot, a shared screen or a copied URL doesn't carry it on, and a reload still has it.
 */
private fun accessOf(params: URLSearchParams): Access {
    params.get(PairingWire.KEY_PARAM)?.takeIf { it.isNotEmpty() }?.let {
        sessionStorage.setItem(KEY_STORAGE, it)
        window.history.replaceState(null, "", window.location.pathname + searchWithoutKey() + window.location.hash)
    }
    val key = sessionStorage.getItem(KEY_STORAGE)
    return when {
        key != null -> Access.Keyed(key)
        isLoopbackHost(window.location.hostname) -> Access.Loopback
        else -> Access.NeedsPairing
    }
}

/** Whether [hostname] (as `location.hostname` gives it) names this machine. */
private fun isLoopbackHost(hostname: String): Boolean =
    hostname == "localhost" || hostname.endsWith(".localhost") || hostname == "[::1]" ||
        Regex("127\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}").matches(hostname)

/** `ws:` or `wss:`, after the page's own scheme, to the host the page came from, at [path]. */
private fun socketUrl(path: String, query: String): String {
    val scheme = if (window.location.protocol == "https:") "wss" else "ws"
    return "$scheme://${window.location.host}$path${if (query.isEmpty()) "" else "?$query"}"
}

private fun param(name: String, value: String): String = "$name=${encodeURIComponent(value)}"

private fun encodeURIComponent(value: String): String = js("encodeURIComponent(value)")

/**
 * Shows the pairing code full screen until a person types it on the board, then hands the key to
 * [admitted]. Nothing from the board reaches this page before then: the pairing channel carries
 * only the code and the key.
 *
 * The channel is an `EventSource`, closed on the host's last word: left open, a browser would dial
 * it again when the host ends the stream, and open a new pairing.
 */
private fun pair(screen: String?, admitted: (String) -> Unit) {
    val panel = document.body!!.appendElement("section", "pairing")
    panel.setAttribute("style", "position:fixed;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;font-family:system-ui,sans-serif;background:var(--bg);color:var(--ink)")
    val prompt = panel.appendElement("p", "pairing-prompt")
    val code = panel.appendElement("p", "pairing-code")
    code.setAttribute("style", "font-size:20vmin;letter-spacing:0.1em;margin:0;font-variant-numeric:tabular-nums")
    val again = panel.appendElement("button", "pairing-again")
    again.textContent = "Show a new code"
    again.setAttribute("hidden", "")
    val query = screen?.let { "?${param(PairingWire.SCREEN_PARAM, it)}" }.orEmpty()

    fun ended(why: String) {
        prompt.textContent = why
        code.textContent = ""
        again.removeAttribute("hidden")
    }

    fun request() {
        again.setAttribute("hidden", "")
        prompt.textContent = "Asking the host for a code…"
        val channel = EventSource(PairingWire.PATH + query)
        channel.onmessage = { event ->
            when (val message = Json.decodeFromString(PairingMessage.serializer(), event.data.toString())) {
                is PairingMessage.Code -> {
                    prompt.textContent = "Enter this code on the board to join"
                    code.textContent = message.code
                }
                is PairingMessage.Key -> {
                    channel.close()
                    sessionStorage.setItem(KEY_STORAGE, message.key)
                    panel.remove()
                    admitted(message.key)
                }
                is PairingMessage.Ended -> {
                    channel.close()
                    ended(endedText(message.reason))
                }
            }
        }
        // A browser can't say why a channel failed, only that it did.
        channel.onerror = {
            channel.close()
            ended("Lost the host.")
        }
    }
    again.addEventListener("click", { request() })
    request()
}

private fun endedText(reason: PairingEnd): String = when (reason) {
    PairingEnd.Expired -> "Nobody typed the code in time."
    PairingEnd.Voided -> "Three wrong codes were typed."
    PairingEnd.Busy -> "The host is busy pairing other devices. Try again later."
    PairingEnd.TooSoon -> "Wait a moment, then ask for a new code."
}

@OptIn(ExperimentalUuidApi::class)
private fun startBoard(http: HttpClient, actor: PeerIdentity, access: Access) {
    val root = document.getElementById("board") ?: error("index.html has no #board element")
    val shell = PageShell.around(root)
    val status = shell.status
    val problem = shell.problem
    val store = shell.store
    val records = shell.records

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
    val query = when (access) {
        is Access.Keyed -> param(PairingWire.KEY_PARAM, access.key)
        else -> ""
    }
    val rendezvous = Rendezvous.Existing(WebSocketAdvertisement(socketUrl(BOARD_PATH, query), PeerId("bee-host"), "board"))
    scope.launch {
        val answer = askBoardId()
        val (copy, initial) = restore(answer, author, store)
        // The board the replica starts from: its copy's, else the one the host named. A page with
        // neither starts empty, and belongs to the first board the host names.
        val pin = (copy?.board ?: (answer as? HostAnswer.Named)?.board)?.let(BoardPin::to)
            ?: BoardPin.learnedFrom { (askBoardId() as? HostAnswer.Named)?.board ?: error("The host didn't name its board.") }
        val client = BoardClient(
            author = author,
            loom = SpokeLoom(http, Dispatchers.Default, scope, author.value, Random.Default, clock).pinnedTo(pin),
            rendezvous = rendezvous,
            scope = scope,
            clock = clock,
            initial = initial,
        )
        val onProblem: (String) -> Unit = { problem.textContent = it }
        val inputs = LiveInputs(root, client, author, scope, onProblem)
        val chrome = BoardChrome(root, shell.peers)
        BoardDriver(client, A2uiRenderer(root, chrome::place), root, author, actor, inputs, clock, onProblem).start(scope)
        HistoryStrip(client, author, document.body!!, onProblem).start(scope)
        val presence = Presence(client, author, emptyFlow(), clock, scope) { failure -> log.warn(failure) { "Presence tracking failed." } }
        chrome.start(scope, client.state, presence.roster)
        launch { presence.update(actor, status = null, lastActedAt = clock(), waiting = false) }
        if (access is Access.Keyed && !isLoopbackHost(window.location.hostname)) offerRepair(scope, client)
        reloadOnAnotherBoard(scope, client, pin)

        scope.launch {
            combine(client.online, client.lastFailure) { online, failure ->
                when {
                    online -> "online as $actor"
                    failure != null -> "offline: ${failure.message ?: failure::class.simpleName}"
                    else -> "offline"
                }
            }.collect {
                status.textContent = it
                status.setAttribute("data-online", client.online.value.toString())
            }
        }
        scope.launch { client.state.collect { showRecords(records, it) } }
        client.connect()
        if (copy != null) keep(copy, client.state, scope, store)
    }
}

/** What asking the host for its board came to. */
private sealed interface HostAnswer {
    /** The host named its board. */
    data class Named(val board: BoardId) : HostAnswer

    /** The fetch itself failed: no host answered, so the last-known board is the best there is. */
    data object Silent : HostAnswer

    /** A host answered, but named nothing (a non-OK status, or no text): its board is not known. */
    data object Declined : HostAnswer
}

/**
 * The board the host names at [BoardId.PATH]. A plain `fetch` that settles either way: Ktor's
 * wasm engine reports a refused fetch as a `kotlin.Error`, which no `catch (Exception)` sees.
 */
private suspend fun askBoardId(): HostAnswer {
    val answer = CompletableDeferred<Pair<String?, Boolean>>()
    fetchText(BoardId.PATH) { text, rejected -> answer.complete(text to rejected) }
    val (text, rejected) = answer.await()
    return when {
        rejected -> HostAnswer.Silent
        text.isNullOrBlank() -> HostAnswer.Declined
        else -> HostAnswer.Named(BoardId(text))
    }
}

/**
 * Fetches [path] past every cache and calls [onDone] with its text, or null for a non-OK status;
 * `rejected` is true only when the fetch itself failed.
 */
@JsFun(
    """(path, onDone) => {
        fetch(path, { cache: 'no-store' })
            .then((r) => (r.ok ? r.text() : null))
            .then((text) => onDone(text, false), () => onDone(null, true));
    }""",
)
private external fun fetchText(path: String, onDone: (String?, Boolean) -> Unit)

/** The copy this tab reads and may keep: one [board]'s, in [store]. */
private class Copy(val store: BrowserStore, val board: BoardId)

/**
 * This browser's copy of the board the host named ([answer]), and the board it holds, under
 * [author]. A browser that can't keep one (no IndexedDB, no Web Locks, or no board known) starts
 * from an empty board, and [line] says why: the host still has the board. A copy that doesn't
 * read is logged and kept, to be replaced by the writer.
 */
private suspend fun restore(answer: HostAnswer, author: ReplicaId, line: Element): Pair<Copy?, BoardState> {
    if (answer is HostAnswer.Declined) return notKept(line, "the host didn't name its board")
    val served = (answer as? HostAnswer.Named)?.board
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

/**
 * A browser can't see why a socket was refused, so after each failed dial the page asks the host
 * which board it keeps. Another board than [pin]'s means the host was wiped, or its claims log
 * moved aside, and refuses this page's sockets for good. The page reloads: that drops its replica
 * of the old board, and the new page starts from the host's board, whose copy the writer tab keeps
 * in place of the old one's. A host that doesn't answer is down, and the page waits for it.
 */
private fun reloadOnAnotherBoard(scope: CoroutineScope, client: BoardClient, pin: BoardPin) {
    scope.launch {
        client.lastFailure.filterNotNull().collect {
            val pinned = pin.board.value ?: return@collect
            val answer = askBoardId()
            if (answer is HostAnswer.Named && answer.board != pinned) {
                log.info { "The host keeps ${answer.board} now, not $pinned: reloading onto it." }
                window.location.reload()
            }
        }
    }
}

/**
 * A browser can't see why a socket was refused, so a key the host no longer holds (it restarted)
 * looks like any outage. While offline, a keyed LAN page offers to drop its key and pair again.
 */
private fun offerRepair(scope: CoroutineScope, client: BoardClient) {
    val repair = document.body!!.appendElement("button", "pair-again")
    repair.textContent = "Pair this screen again"
    repair.addEventListener("click", {
        sessionStorage.removeItem(KEY_STORAGE)
        window.location.search = searchWithoutKey()
    })
    scope.launch {
        client.online.collect { online -> if (online) repair.setAttribute("hidden", "") else repair.removeAttribute("hidden") }
    }
}

/** The page's query with any key taken out: `?…`, or empty when nothing is left. */
private fun searchWithoutKey(): String {
    val params = URLSearchParams(window.location.search.toJsString()).apply { delete(PairingWire.KEY_PARAM) }
    return params.toString().let { if (it.isEmpty()) "" else "?$it" }
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
