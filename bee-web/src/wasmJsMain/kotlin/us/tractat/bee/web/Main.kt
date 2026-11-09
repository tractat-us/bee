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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.w3c.dom.Element
import org.w3c.dom.url.URLSearchParams
import us.tractat.bee.core.ActionRecord
import us.tractat.bee.core.BoardClient
import us.tractat.bee.core.BoardId
import us.tractat.bee.core.BoardPin
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.ClaimsConnection
import us.tractat.bee.core.ClaimsLink
import us.tractat.bee.core.HostAddress
import us.tractat.bee.core.InviteLinks
import us.tractat.bee.core.InviteWire
import us.tractat.bee.core.MintedInvite
import us.tractat.bee.core.OrphanAnswers
import us.tractat.bee.core.PairingEnd
import us.tractat.bee.core.PairingMessage
import us.tractat.bee.core.PairingPoll
import us.tractat.bee.core.PairingWire
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.Presence
import us.tractat.bee.core.SpokeLoom
import us.tractat.bee.core.pinnedTo
import us.tractat.kuilt.cluster.ClusterEndpoints
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.VersionVector
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.websocket.KtorClientLoom
import us.tractat.kuilt.websocket.WebSocketAdvertisement
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val log = KotlinLogging.logger("us.tractat.bee.web.Main")

/** Where this tab keeps the key pairing gave it. A tab's own storage: a new tab on a LAN device pairs again. */
internal const val KEY_STORAGE = "bee.lan.key"

/** How this page may reach the host's board (spec §5.7). */
internal sealed interface Access {
    /** The page may dial the board now. */
    sealed interface Admitted : Access

    /** The page came from this machine: the host needs no key from it. */
    data object Loopback : Admitted

    /** The page holds [key], from a join link's `?k=` or from pairing. */
    data class Keyed(val key: String) : Admitted

    /** The page came over the network with no key: it pairs before it sees anything. */
    data object NeedsPairing : Access

    /**
     * The page came with a hosted board's invite, `#i=<secret>`, and no key (bee#215): it asks a
     * name and redeems [secret] for a key before it sees anything ([JoinByInvite]).
     */
    data class Invited(val secret: String) : Access {
        override fun toString(): String = "Invited(…)"
    }
}

/**
 * The bee page: this tab's replica of the board, behind the stock renderer.
 *
 * - **Who.** `?screen=<name>` makes a room screen, the human peer `human:<name>` (A7). Otherwise
 *   the person is `human:<name>`, from `?name=` (A7's `localStorage` name is still to come). A
 *   person's page opens a claims link, to propose its person's answers to orphan decisions
 *   ([OrphanAnswering]). A screen's page opens none, so it never proposes a claim, and its answers
 *   are refused (spec §6.4).
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
    val screen = params.get(PairingWire.SCREEN_PARAM)?.isNotBlank() == true
    val http = HttpClient { install(WebSockets) }
    when (val access = accessOf(params)) {
        is Access.Admitted -> startBoard(http, actor, viewerOf(params), screen, access)
        Access.NeedsPairing -> pair(params.get(PairingWire.SCREEN_PARAM)) { key -> startBoard(http, actor, viewerOf(params), screen, Access.Keyed(key)) }
        // The person names themselves: the board, their line and their presence carry that name.
        is Access.Invited -> JoinByInvite(access.secret, params.get("name"), pairByCode = { admitted -> pair(null, admitted) }) { name, key ->
            startBoard(http, PeerIdentity.human(name ?: GUEST), name?.let(Viewer::Named) ?: Viewer.Unnamed, screen = false, Access.Keyed(key))
        }.ask()
    }
}

/** Who views this page, by the `?name=` it was opened with. */
private fun viewerOf(params: URLSearchParams): Viewer =
    params.get("name")?.takeIf { it.isNotBlank() }?.let(Viewer::Named) ?: Viewer.Unnamed

/** Who a person who gave no name is. */
private const val GUEST = "guest"

private fun actorOf(params: URLSearchParams): PeerIdentity {
    val screen = params.get(PairingWire.SCREEN_PARAM)?.takeIf { it.isNotBlank() }
    val name = screen ?: params.get("name")?.takeIf { it.isNotBlank() } ?: GUEST
    return PeerIdentity.human(name)
}

/**
 * A join link's key moves into this tab's storage and out of the address bar at once, so a
 * screenshot, a shared screen or a copied URL doesn't carry it on, and a reload still has it.
 *
 * The key comes in the query, `?k=`, as a LAN join link has it, or in the fragment, `#k=`, as a
 * hosted board's owner link has it (bee#145). A browser never sends a fragment, so a key there
 * never reaches the host or a proxy's log; this runs before the page's first fetch. The browser's
 * own history of visited pages may still hold the URL as it was opened: `replaceState` rewrites
 * the tab's entry, not that history.
 */
private fun accessOf(params: URLSearchParams): Access {
    val fragment = URLSearchParams(window.location.hash.removePrefix("#").toJsString())
    val given = params.get(PairingWire.KEY_PARAM)?.takeIf { it.isNotEmpty() } ?: fragment.get(PairingWire.KEY_PARAM)?.takeIf { it.isNotEmpty() }
    // A hosted board's invite (bee#215) rides in the fragment too, and leaves the address bar the same way.
    val invite = fragment.get(InviteWire.SECRET_PARAM)?.takeIf { it.isNotEmpty() }
    given?.let { sessionStorage.setItem(KEY_STORAGE, it) }
    invite?.let { sessionStorage.setItem(INVITE_STORAGE, it) }
    if (given != null || invite != null) window.history.replaceState(null, "", window.location.pathname + searchWithoutKey() + hashWithoutKey())
    val key = sessionStorage.getItem(KEY_STORAGE)
    val invited = sessionStorage.getItem(INVITE_STORAGE)
    return when {
        key != null -> Access.Keyed(key).also { sessionStorage.removeItem(INVITE_STORAGE) }
        invited != null -> Access.Invited(invited)
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

/** The host this page came from, with the page's key if it has one: what the board socket is dialled through. */
private fun pageHost(access: Access.Admitted): HostAddress = HostAddress.parse(
    when (access) {
        is Access.Keyed -> "${window.location.origin}/?${param(PairingWire.KEY_PARAM, access.key)}"
        Access.Loopback -> window.location.origin
    },
)

internal fun param(name: String, value: String): String = "$name=${encodeURIComponent(value)}"

internal fun encodeURIComponent(value: String): String = js("encodeURIComponent(value)")

/**
 * Shows the pairing code full screen until a person types it on the board, then hands the key to
 * [admitted]. Nothing from the board reaches this page before then: pairing carries only the code
 * and the key.
 */
private fun pair(screen: String?, admitted: (String) -> Unit) {
    PairingPanel(screen, admitted).request()
}

/**
 * The pairing panel, pairing by poll (bee#209): `POST` [PairingWire.PATH] gets the code and a
 * token, then a `POST` to [PairingWire.POLL_PATH] every [PairingWire.POLL_EVERY] asks what came of
 * it. Each is a plain request and a complete response, so a proxy that holds an event stream back
 * until it ends (a TLS-inspecting one, at work) can't leave the page waiting. Each request gives up
 * after [PairingWire.REQUEST_TIMEOUT], and the page says when it can't get a code or lost the host,
 * rather than wait for ever.
 */
private class PairingPanel(screen: String?, private val admitted: (String) -> Unit) {
    private val panel = document.body!!.appendElement("section", "pairing").apply {
        setAttribute("style", "position:fixed;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;font-family:system-ui,sans-serif;background:var(--bg);color:var(--ink)")
    }
    private val prompt = panel.appendElement("p", "pairing-prompt")
    private val code = panel.appendElement("p", "pairing-code").apply {
        setAttribute("style", "font-size:20vmin;letter-spacing:0.1em;margin:0;font-variant-numeric:tabular-nums")
    }
    private val again = panel.appendElement("button", "pairing-again").apply {
        textContent = "Show a new code"
        setAttribute("hidden", "")
        addEventListener("click", { request() })
    }
    private val query = screen?.let { "?${param(PairingWire.SCREEN_PARAM, it)}" }.orEmpty()

    /** Asks the host for a new code. */
    fun request() {
        again.setAttribute("hidden", "")
        prompt.textContent = "Asking the host for a code…"
        postText(PairingWire.PATH + query, "", REQUEST_TIMEOUT_MS) { status, text ->
            when (val opening = text?.takeIf { status == HTTP_OK }?.let { decodeOrNull(PairingPoll.serializer(), it) }) {
                is PairingPoll.Opened -> show(opening.code, opening.token)
                is PairingPoll.Refused -> ended(endedText(opening.ended.reason))
                null -> ended("Couldn't get a code from the host.")
            }
        }
    }

    /** Shows [message]; while it is a code, asks again in a moment with [token]. */
    private fun show(message: PairingMessage, token: String) {
        when (message) {
            is PairingMessage.Code -> {
                prompt.textContent = "Enter this code on the board to join"
                code.textContent = message.code
                poll(token, answeredAt = Clock.System.now())
            }
            is PairingMessage.Key -> {
                sessionStorage.setItem(KEY_STORAGE, message.key)
                panel.remove()
                admitted(message.key)
            }
            is PairingMessage.Ended -> ended(endedText(message.reason))
        }
    }

    /**
     * Asks after [token]'s pairing in a moment. A lost ask, a timeout or an answer that isn't the
     * host's (a proxy's own page, say) is asked again, until [PairingWire.IDLE] has passed since the
     * host last answered, at [answeredAt]: by then the host has let the pairing go too.
     */
    private fun poll(token: String, answeredAt: Instant) {
        window.setTimeout({
            postText(PairingWire.POLL_PATH, token, REQUEST_TIMEOUT_MS) { status, text ->
                val answer = text?.takeIf { status == HTTP_OK }?.let { decodeOrNull(PairingMessage.serializer(), it) }
                when {
                    answer != null -> show(answer, token)
                    // The host holds no pairing for this token: it ended while the page wasn't asking.
                    status == HTTP_NOT_FOUND -> ended("This code stopped working. Ask for a new one.")
                    Clock.System.now() - answeredAt >= PairingWire.IDLE -> ended("Lost the host.")
                    else -> poll(token, answeredAt)
                }
            }
            null
        }, PairingWire.POLL_EVERY.inWholeMilliseconds.toInt())
    }

    private fun ended(why: String) {
        prompt.textContent = why
        code.textContent = ""
        again.removeAttribute("hidden")
    }
}

private fun endedText(reason: PairingEnd): String = when (reason) {
    PairingEnd.Expired -> "Nobody typed the code in time."
    PairingEnd.Voided -> "Three wrong codes were typed."
    PairingEnd.Busy -> "The host is busy pairing other devices. Try again later."
    PairingEnd.TooSoon -> "Wait a moment, then ask for a new code."
    PairingEnd.NobodyHere -> "Nobody is here to let you in. Try again when someone is on the board."
}

@OptIn(ExperimentalUuidApi::class)
private fun startBoard(http: HttpClient, actor: PeerIdentity, viewer: Viewer, screen: Boolean, access: Access.Admitted) {
    val root = document.getElementById("board") ?: error("index.html has no #board element")
    // Who views this page, for a Notion that marks the viewer's own choice (bee-notions' Poll).
    root.setAttribute("data-viewer", actor.toString())
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
        // The page shows only the message; the stack goes to the console, for whoever debugs it.
        log.error { "The page stopped.\n${failure.stackTraceToString()}" }
        status.textContent = "stopped"
        problem.textContent = "The page stopped: ${failure.message ?: failure::class.simpleName}"
    }
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + crashed)
    val query = when (access) {
        is Access.Keyed -> param(PairingWire.KEY_PARAM, access.key)
        Access.Loopback -> ""
    }
    val rendezvous = pageHost(access).boardRendezvous()
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
        // A room screen has no person to bring a session, and its key is the room's own: never shown.
        if (!screen) chrome.addToHint(BringYourClaude(shell, McpCommand.of(window.location.origin, access, viewer)).opener())
        // The host says who may invite: the owner on a LAN host's own machine, or any admitted
        // page on a public host (bee#145). A screen never asks.
        if (!screen) offerInvite(shell, query)
        val answering = if (screen) {
            OrphanAnswering.Refused
        } else {
            val claims = claimsConnection(http, pin, query, scope, clock)
            OrphanAnswering.ByPerson(OrphanAnswers({ claims.link.value?.client }, actor))
        }
        BoardDriver(client, A2uiRenderer(root, chrome::place), root, author, actor, inputs, answering, clock, onProblem).start(scope)
        HistoryStrip(client, author, document.body!!, onProblem).start(scope)
        val presence = Presence(client, author, emptyFlow(), clock, scope) { failure -> log.warn(failure) { "Presence tracking failed." } }
        chrome.start(scope, client.state, presence.roster, client.online)
        launch { presence.update(actor, status = null, lastActedAt = clock()) }
        if (access is Access.Keyed && !isLoopbackHost(window.location.hostname)) offerRepair(scope, client)
        reloadOnAnotherBoard(scope, client, pin)

        scope.launch {
            combine(client.online, client.lastFailure) { online, failure -> StatusLine.of(online, actor, failure) }
                .distinctUntilChanged()
                .collect { line ->
                    status.textContent = line.text
                    if (line.detail.isEmpty()) {
                        status.removeAttribute("title")
                    } else {
                        status.setAttribute("title", line.detail)
                        log.info { "Offline: ${line.detail}" }
                    }
                    status.setAttribute("data-online", client.online.value.toString())
                }
        }
        scope.launch { client.state.collect { showRecords(records, it) } }
        client.connect()
        if (copy != null) keep(copy, client.state, scope, store)
    }
}

/**
 * This page's claims, through the host's voter at [ClaimsLink.PATH], with the same key [query]
 * and board [pin] as its board socket. Each link dials under a fresh Raft node, as kuilt requires;
 * the claimant is the person, the same across tabs.
 */
@OptIn(ExperimentalUuidApi::class)
private fun claimsConnection(http: HttpClient, pin: BoardPin, query: String, scope: CoroutineScope, clock: () -> Instant): ClaimsConnection =
    ClaimsConnection(
        scope = scope,
        nextNode = { NodeId("web-${Uuid.random()}") },
        open = { node, linkScope ->
            ClaimsLink.open(
                node = node,
                loomFor = { peer -> KtorClientLoom(http, selfPeerId = peer).pinnedTo(pin) },
                endpoints = ClusterEndpoints(listOf(WebSocketAdvertisement(socketUrl(ClaimsLink.PATH, query), ClaimsLink.SERVER, ClaimsLink.SESSION))),
                scope = linkScope,
                raftConfig = RaftConfig(),
                clock = clock,
                random = Random.Default,
            )
        },
        onFailure = { failure -> log.warn(failure) { "The claims link failed; it is rebuilt." } },
        retryDelay = ClaimsConnection.jitteredBackoff(Random.Default),
    )

/**
 * An Invite control in the bar, once the host gives this page its join links at
 * [InviteLinks.PATH], asked with the page's key [query] if it has one: a `--lan` host answers its
 * own machine, and a public host any key it admits. Anything else (a 404, a 403, no host) leaves
 * the bar as it is. A public host's link is minted, by `POST` to the same path with the same key,
 * when the panel opens (bee#215).
 */
private fun offerInvite(shell: PageShell, query: String) {
    val path = InviteLinks.PATH + if (query.isEmpty()) "" else "?$query"
    fetchText(path) { text, _ ->
        val links = text?.let { decodeOrNull(InviteLinks.serializer(), it) } ?: return@fetchText
        val source = when (links.joins) {
            InviteLinks.Joins.OnThisNetwork -> InviteSource.Fixed(links)
            InviteLinks.Joins.Anywhere -> InviteSource.Minted { done -> mintInvite(path, done) }
        }
        Invite(shell, source)
    }
}

/**
 * This tab's invite from the host at [path]. The page sends the secret of the one it holds, if
 * any, in the body: the host gives that one back while it still works, so a reload doesn't spend
 * one of the minter's live invites, and otherwise mints a new one. A link that stopped working
 * (used up, lapsed, or forgotten by a host that forgot its devices) is so replaced, and never
 * shown again. Calls [done] with it, or with why not in words for the panel: the host's own,
 * when it says too many are live.
 */
private fun mintInvite(path: String, done: (Result<MintedInvite>) -> Unit) {
    val held = sessionStorage.getItem(MINTED_STORAGE)?.let { decodeOrNull(MintedInvite.serializer(), it) }
    val secret = held?.link?.substringAfter("#${InviteWire.SECRET_PARAM}=", missingDelimiterValue = "").orEmpty()
    postText(path, secret, REQUEST_TIMEOUT_MS) { status, text ->
        val minted = text?.takeIf { status == HTTP_OK }?.let { decodeOrNull(MintedInvite.serializer(), it) }
        // Once the host answers, the tab holds only what it gave now: a refusal means the old link
        // didn't work either, or the host would have given it back. No answer (status 0) keeps it.
        when {
            minted != null -> sessionStorage.setItem(MINTED_STORAGE, Json.encodeToString(MintedInvite.serializer(), minted))
            status != 0 -> sessionStorage.removeItem(MINTED_STORAGE)
        }
        done(
            when {
                minted != null -> Result.success(minted)
                status == HTTP_TOO_MANY && !text.isNullOrBlank() -> Result.failure(IllegalStateException(text))
                else -> Result.failure(IllegalStateException("Couldn't make a link. Close this and try again."))
            },
        )
    }
}

private const val HTTP_TOO_MANY = 429

/** Where this tab keeps the invite it minted, as its key is kept: the tab's own storage (bee#215). */
private const val MINTED_STORAGE = "bee.invite.minted"

/** Where this tab keeps an invite's secret until it is redeemed or refused, so a reload still has it (bee#215). */
internal const val INVITE_STORAGE = "bee.invite"

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

internal val REQUEST_TIMEOUT_MS = PairingWire.REQUEST_TIMEOUT.inWholeMilliseconds.toInt()

/**
 * [text] as [serializer]'s type, or null if it isn't one. A proxy can answer 200 with a page of
 * its own, so a body is the host's only once it decodes.
 */
internal fun <T> decodeOrNull(serializer: KSerializer<T>, text: String): T? = try {
    Json.decodeFromString(serializer, text)
} catch (_: IllegalArgumentException) {
    // kotlinx.serialization's SerializationException is an IllegalArgumentException.
    null
}

internal const val HTTP_OK = 200
private const val HTTP_NOT_FOUND = 404

/**
 * `POST`s [body] to [path] past every cache, giving up after [timeoutMs], and calls [onDone] with
 * the status and the text: status 0 and no text when the fetch itself failed or timed out.
 */
@JsFun(
    """(path, body, timeoutMs, onDone) => {
        fetch(path, { method: 'POST', body, cache: 'no-store', signal: AbortSignal.timeout(timeoutMs) })
            .then((r) => r.text().then((text) => [r.status, text]))
            .then(([status, text]) => onDone(status, text), () => onDone(0, null));
    }""",
)
internal external fun postText(path: String, body: String, timeoutMs: Int, onDone: (Int, String?) -> Unit)

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
 * A browser can't see why a socket was refused, so a key the host no longer holds (it forgot its
 * devices, bee#106) looks like any outage. While offline, a keyed LAN page offers to drop its key and pair again.
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

/**
 * The page's fragment with any key or invite taken out: `#…`, or empty when nothing is left. A
 * fragment with neither stays as it was.
 */
private fun hashWithoutKey(): String {
    val params = URLSearchParams(window.location.hash.removePrefix("#").toJsString())
    val secrets = listOf(PairingWire.KEY_PARAM, InviteWire.SECRET_PARAM)
    if (secrets.none { params.has(it) }) return window.location.hash
    secrets.forEach { params.delete(it) }
    return params.toString().let { if (it.isEmpty()) "" else "#$it" }
}

internal fun Element.appendElement(tag: String, id: String): Element =
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
