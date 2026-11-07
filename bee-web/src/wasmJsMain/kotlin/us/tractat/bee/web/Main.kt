package us.tractat.bee.web

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.url.URLSearchParams
import us.tractat.bee.core.ActionRecord
import us.tractat.bee.core.BoardClient
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

/** The host's board relay route (bee-host's `BoardRelay.PATH`). */
private const val BOARD_PATH = "/ws/board"

/**
 * The bee page: this tab's replica of the board, behind the stock renderer.
 *
 * The person is `human:<name>`, from `?name=` (A7's `localStorage` name comes with join, Task 7).
 * The CRDT author is fresh per page load, `web-<uuid>`. The page shows whether it is online, and
 * why not, and lists the board's action records with what each one's basis saw.
 */
@OptIn(ExperimentalUuidApi::class)
fun main() {
    val root = document.getElementById("board") ?: error("index.html has no #board element")
    val status = document.body!!.appendElement("p", "status")
    val problem = document.body!!.appendElement("p", "problem")
    val records = document.body!!.appendElement("ol", "records")

    val name = URLSearchParams(window.location.search.toJsString()).get("name")?.takeIf { it.isNotBlank() } ?: "guest"
    val actor = PeerIdentity.human(name)
    val author = ReplicaId("web-${Uuid.random()}")
    val clock = { Clock.System.now() }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val http = HttpClient { install(WebSockets) }
    val rendezvous = Rendezvous.Existing(
        WebSocketAdvertisement("ws://${window.location.host}$BOARD_PATH", PeerId("bee-host"), "board"),
    )
    val client = BoardClient(
        author = author,
        loom = SpokeLoom(http, Dispatchers.Default, scope, author.value, Random.Default, clock),
        rendezvous = rendezvous,
        scope = scope,
        clock = clock,
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
