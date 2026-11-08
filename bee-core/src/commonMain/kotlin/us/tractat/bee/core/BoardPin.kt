package us.tractat.bee.core

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.websocket.WebSocketAdvertisement

/**
 * The board a replica belongs to, named on every socket it dials ([pinnedTo], bee#46): known when
 * the replica is built from that board's copy, or learned at its first dial and kept.
 *
 * A replica built empty, before its client knew any board (a bridge started before its host, a
 * page with no copy and no host), belongs to the first board it learns, since it holds nothing of
 * any other. From then on the pin never moves: a replica can't follow its host to a new board and
 * push the old one into it. The host refuses it instead, and its client starts a new replica.
 */
class BoardPin private constructor(pinned: BoardId?, private val ask: suspend () -> BoardId) {
    private val mutex = Mutex()
    private val pinned = MutableStateFlow(pinned)

    /** The pinned board, or null until the first dial learns it. */
    val board: StateFlow<BoardId?> = this.pinned.asStateFlow()

    /** The pinned board, asking for it first if none is pinned yet. A failed ask pins nothing, and throws. */
    suspend fun resolve(): BoardId = mutex.withLock { pinned.value ?: ask().also { pinned.value = it } }

    companion object {
        /** Pinned to [board], whose state the replica was built from. */
        fun to(board: BoardId): BoardPin = BoardPin(board) { board }

        /** Pinned to whatever board [ask] names first. [ask] throws while it can't name one. */
        fun learnedFrom(ask: suspend () -> BoardId): BoardPin = BoardPin(null, ask)
    }
}

/**
 * The board the host at [host] keeps, as it names it at [BoardId.PATH], over [HostAddress.http].
 * That path is open to every caller, so the ask carries no key.
 *
 * @throws IllegalStateException if the host answers, but names no board
 */
suspend fun HttpClient.askBoardId(host: HostAddress): BoardId {
    val response = get(host.http + BoardId.PATH)
    check(response.status == HttpStatusCode.OK) { "The host at $host didn't name its board: ${response.status}" }
    return BoardId(response.bodyAsText().trim())
}

/**
 * This loom, with every socket it dials naming [pin]'s board: `?board=<id>` on the
 * [WebSocketAdvertisement]'s URL. A dial that can't learn the board fails like any other, and is
 * redialled like any other.
 */
fun Loom.pinnedTo(pin: BoardPin): Loom = PinnedLoom(this, pin)

/**
 * Delegation forwards [Loom]'s default methods to [inner] too, where they would call [inner]'s
 * [weave], not this one, and dial without the board. So every way to dial is overridden here:
 * kuilt's cluster client joins by [join].
 */
private class PinnedLoom(private val inner: Loom, private val pin: BoardPin) : Loom by inner {
    override suspend fun weave(rendezvous: Rendezvous): Seam = inner.weave(rendezvous.naming(pin.resolve()))

    override suspend fun join(tag: Tag): Seam = weave(Rendezvous.Existing(tag))

    override suspend fun host(pattern: Pattern): Seam = weave(Rendezvous.New(pattern))
}

/** [this] rendezvous with [board] in its socket's query. Only a WebSocket's can carry one. */
internal fun Rendezvous.naming(board: BoardId): Rendezvous {
    val advertisement = (this as? Rendezvous.Existing)?.tag as? WebSocketAdvertisement
    requireNotNull(advertisement) { "Only a WebSocket rendezvous names a board: $this" }
    val separator = if ('?' in advertisement.url) '&' else '?'
    return Rendezvous.Existing(advertisement.copy(url = "${advertisement.url}$separator${BoardId.PARAM}=$board"))
}
