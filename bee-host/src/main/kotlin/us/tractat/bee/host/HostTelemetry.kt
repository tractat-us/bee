package us.tractat.bee.host

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseBodyReadyForSend
import io.ktor.server.application.install
import io.ktor.server.request.path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import us.tractat.bee.core.BoardId
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.PairingWire
import us.tractat.bee.telemetry.Attr
import us.tractat.bee.telemetry.BeeTelemetry
import us.tractat.bee.telemetry.event
import us.tractat.kuilt.core.PeerId

private val log = KotlinLogging.logger {}

/** One thing the board shows happened: an event name and its attributes. */
data class BoardChange(val name: String, val attributes: Map<String, String>)

/**
 * What changed from [before] to [after], as events: surfaces opened, updated, closed and reopened,
 * action records appended, peers attached and departed, claims committed. Ids, names and counts
 * only: never a surface's components or data, nor a record's context.
 */
fun boardChanges(before: BoardState, after: BoardState): List<BoardChange> = buildList {
    surfaceChanges(before, after)
    if (after.records != before.records) {
        val seen = before.records().map { it.first }.toSet()
        after.records().filterNot { it.first in seen }.forEach { (_, record) ->
            add(
                BoardChange(
                    "action.record",
                    mapOf(Attr.SURFACE to record.surfaceId, "bee.component" to record.componentId, "bee.action" to record.name, "bee.actor" to record.actor.toString()),
                ),
            )
        }
    }
    if (after.presence != before.presence) {
        val attached = before.latestPresence().map { it.identity }.toSet()
        after.latestPresence().filterNot { it.identity in attached }.forEach {
            add(BoardChange("presence.attach", mapOf(Attr.SUBJECT to it.identity.toString())))
        }
    }
    (after.departures() - before.departures()).forEach {
        add(BoardChange("presence.depart", mapOf(Attr.SUBJECT to it.identity.toString(), Attr.REASON to it.reason.name, "bee.log.index" to it.logIndex.toString())))
    }
    if (after.claims != before.claims) {
        after.claims.entries.filter { (target, view) -> before.claims[target] != view }.forEach { (target, view) ->
            add(
                BoardChange(
                    "claim.commit",
                    buildMap {
                        put("bee.claim", target)
                        put("bee.claim.status", view.status)
                        view.holder?.let { put("bee.claim.holder", it) }
                        put("bee.log.index", view.logIndex.toString())
                    },
                ),
            )
        }
    }
}

private fun MutableList<BoardChange>.surfaceChanges(before: BoardState, after: BoardState) {
    if (after.surfaces == before.surfaces) return
    for (id in after.surfaces.keys) {
        val was = before.meta(id)
        val now = after.meta(id) ?: continue
        val name = when {
            was == null -> "surface.open"
            was.closedAt == null && now.closedAt != null -> "surface.close"
            was.closedAt != null && now.closedAt == null -> "surface.reopen"
            before.surfaceDoc(id) != after.surfaceDoc(id) -> "surface.update"
            else -> continue
        }
        add(BoardChange(name, mapOf(Attr.SURFACE to id, "bee.catalog" to now.catalogId, "bee.opened.by" to now.openedBy.toString())))
    }
}

/**
 * Records the host's board as it changes ([boardChanges]), and which spokes are on its relay. The
 * first board seen is the one the host booted with, recorded as `board.restored`. A change that
 * can't be read is logged once and skipped: watching never stops the board.
 */
internal fun CoroutineScope.watchBoard(board: StateFlow<BoardState>, spokes: StateFlow<Set<PeerId>>, self: PeerId, telemetry: BeeTelemetry): Job = launch {
    launch {
        var was = emptySet<PeerId>()
        spokes.collect { now ->
            (now - was - self).forEach { telemetry.event("socket.join", Attr.SUBJECT to it.value, Attr.PATH to BoardRelay.PATH) }
            (was - now - self).forEach { telemetry.event("socket.leave", Attr.SUBJECT to it.value, Attr.PATH to BoardRelay.PATH) }
            was = now
        }
    }
    var last: BoardState? = null
    var unreadable = false
    board.collect { now ->
        val before = last
        last = now
        try {
            if (before == null) {
                telemetry.event("board.restored", "bee.surfaces" to now.surfaces.keys.size, "bee.records" to now.records.size)
            } else {
                boardChanges(before, now).forEach { telemetry.event(it.name, it.attributes) }
            }
            unreadable = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") bug: Exception) {
            if (!unreadable) log.warn(bug) { "Telemetry couldn't read a board change; it skips each one it can't." }
            unreadable = true
        }
    }
}

/**
 * Records each socket dialled and each call refused: path, whether the caller is on loopback,
 * whether it presented a key and named this [board]. Never the key, nor the query. A socket's
 * end isn't recorded here: Ktor reports its `101` only as the socket closes, and not at all when
 * the host stops first. A spoke leaving the relay is [watchBoard]'s `socket.leave`. Installed
 * before [mountEndpoints], so it sees the site gate's refusals as well as the socket gate's.
 */
internal fun Application.recordCalls(telemetry: BeeTelemetry, board: BoardId, peers: PeerAddress) {
    fun record(name: String, call: ApplicationCall) {
        val query = call.request.queryParameters
        telemetry.event(
            name,
            Attr.PATH to call.request.path(),
            "bee.loopback" to isLoopback(peers.of(call)),
            "bee.key.presented" to (query[PairingWire.KEY_PARAM] != null),
            "bee.board.named" to (query[BoardId.PARAM] == board.value),
        )
    }
    install(
        createApplicationPlugin("BeeCallTelemetry") {
            onCall { call -> if (call.request.headers[HttpHeaders.Upgrade] != null) record("socket.dial", call) }
            // Before the refusal is sent, so a host stopping straight after can't lose it.
            on(ResponseBodyReadyForSend) { call, content ->
                if ((content.status ?: call.response.status()) == HttpStatusCode.Forbidden) record("gate.refused", call)
            }
        },
    )
}
