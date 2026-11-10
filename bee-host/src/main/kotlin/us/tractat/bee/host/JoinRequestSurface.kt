package us.tractat.bee.host

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import us.tractat.bee.a2ui.A2uiBindings
import us.tractat.bee.a2ui.NOTIONS_CATALOG_ID
import us.tractat.bee.core.ActionRecord
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.PairingEnd
import us.tractat.bee.core.PairingSurfaces
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.RecordId
import us.tractat.bee.core.SurfaceMeta
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Instant

/** A keyless device asking to join: who dialled ([Caller]), and the room-screen name it gave (A7), if any. */
data class Device(val caller: Caller, val screen: String?) {
    /** Where it dialled from, as the join request names it. */
    val address: String get() = caller.address
}

/** How a pairing ended, for the device waiting on it. */
sealed interface Resolution {
    data class Admitted(val key: DeviceKey, val by: PeerIdentity) : Resolution

    data class Ended(val reason: PairingEnd) : Resolution
}

/**
 * The join request on the board (spec §5.7, step 2–3): the host writes one surface per pairing,
 * asking the participants to type the code the device shows, and handles the entries typed into it.
 *
 * - **The code is never written.** The surface names the device's address and screen name; the
 *   code exists only on the device's screen and in [Pairing]. A person's entry arrives as an action
 *   record (§6.2), so what they typed is in that record, and a matching code is spent at once.
 * - **[Pairing] decides.** Each entry goes to [Pairing.attempt], whose transition is atomic, so an
 *   entry and the device's timer racing to end one pairing write its outcome once.
 * - **The host handles these records without a claim.** It is their only handler, and the voter
 *   never claims (A2), so exactly-once holds by construction: each record is handled once per boot,
 *   and a pairing doesn't outlive the boot that opened it.
 * - **A request this host didn't post is closed.** One left open by a host that restarted (a
 *   spoke's `FullState` brings it back) can never be answered, since keys and codes live in memory.
 */
class JoinRequestSurface(
    private val relay: BoardRelay,
    private val pairing: Pairing,
    private val clock: () -> Instant,
) {
    /**
     * The requests this host posted and hasn't finished, each with its device's wait. It is the
     * one record of what is posted, so a request leaves it when its pairing ends, and a loop of
     * pairings leaves nothing behind here.
     */
    private val waiting = ConcurrentHashMap<PairingId, CompletableDeferred<Resolution>>()

    /** Records already handled. Only the collector [start] launches touches it. */
    private val handled = mutableSetOf<RecordId>()

    /** Handles every entry typed into a request, and closes left-over requests, until [scope] ends. */
    fun start(scope: CoroutineScope): Job = scope.launch { relay.state.collect(::handle) }

    /** Puts [ticket]'s join request on the board. The result completes when its pairing ends. */
    fun post(ticket: Opened.Ticket, device: Device): Deferred<Resolution> {
        val resolution = CompletableDeferred<Resolution>()
        waiting[ticket.id] = resolution
        val meta = SurfaceMeta(TITLE, NOTIONS_CATALOG_ID, HOST, clock())
        relay.mutate { it.openSurface(relay.replica, surfaceId(ticket.id), meta, components(device), initialData()) }
        return resolution
    }

    /** Ends pairing [id] with no key, for [reason], and closes its request. False if it had already ended. */
    fun end(id: PairingId, reason: PairingEnd): Boolean {
        if (!pairing.end(id)) return false
        finish(id, statusOf(reason), Resolution.Ended(reason))
        return true
    }

    /** The device for pairing [id] left before it was admitted: its request closes. */
    fun withdraw(id: PairingId) {
        if (pairing.end(id)) finish(id, "The device left before it was admitted.", resolution = null)
    }

    private fun handle(state: BoardState) {
        state.openSurfaces()
            .filter { it.startsWith(PREFIX) && !waiting.containsKey(PairingId(it.removePrefix(PREFIX))) }
            .forEach { stale -> relay.mutate { it.closeSurface(relay.replica, stale, clock()) } }
        waiting.keys.forEach { id ->
            state.records(surfaceId(id))
                .filter { (recordId, record) -> record.name == ADMIT_ACTION && handled.add(recordId) }
                .forEach { (_, record) -> handle(id, record) }
        }
    }

    private fun handle(id: PairingId, record: ActionRecord) {
        val typed = (record.context[CODE_KEY] as? JsonPrimitive)?.content.orEmpty()
        when (val attempt = pairing.attempt(id, record.actor, typed)) {
            is Attempt.Admitted -> finish(id, "Admitted by ${attempt.by}.", Resolution.Admitted(attempt.key, attempt.by))
            is Attempt.Wrong -> {
                val tries = if (attempt.triesLeft == 1) "1 try" else "${attempt.triesLeft} tries"
                show(id, "That code doesn't match. $tries left.")
            }
            Attempt.Voided -> finish(id, statusOf(PairingEnd.Voided), Resolution.Ended(PairingEnd.Voided))
            Attempt.Expired -> finish(id, statusOf(PairingEnd.Expired), Resolution.Ended(PairingEnd.Expired))
            is Attempt.NotHuman -> show(id, "${attempt.actor} can't admit a device: only a person can.")
            Attempt.Unknown -> Unit
        }
    }

    /**
     * Shows [status] on [id]'s request. The board's code stays empty: what a person types in the
     * obscured field stays on their page until Admit sends it in a record.
     */
    private fun show(id: PairingId, status: String) {
        relay.mutate { it.updateData(relay.replica, surfaceId(id), "", data(code = "", status = status)) }
    }

    private fun finish(id: PairingId, status: String, resolution: Resolution?) {
        show(id, status)
        relay.mutate { it.closeSurface(relay.replica, surfaceId(id), clock()) }
        val waiter = waiting.remove(id)
        if (resolution != null) waiter?.complete(resolution) else waiter?.cancel()
    }

    companion object {
        /**
         * Who the board shows opening the host's surfaces: `host:bee-host`. Its kind is a label
         * (bee#71): it isn't an agent, so it never departs or holds a claim. A snapshot that names it
         * as it was, `agent:bee-host#00000000`, still reads as it ([PeerIdentity.parse]).
         */
        val HOST: PeerIdentity = PeerIdentity.host("bee-host")

        const val ADMIT_ACTION: String = "admit"
        const val ADMIT_BUTTON: String = "admit-button"
        const val CODE_FIELD: String = "code-field"

        private const val PREFIX = PairingSurfaces.PREFIX
        private const val TITLE = "A device wants to join"
        private const val CODE_KEY = "code"
        private const val STATUS_KEY = "status"

        /** The surface id of pairing [id]'s request. */
        fun surfaceId(id: PairingId): String = "$PREFIX${id.value}"

        private fun statusOf(reason: PairingEnd): String = when (reason) {
            PairingEnd.Expired -> "Nobody typed the code in time. The device can ask for a new one."
            PairingEnd.Voided -> "Three wrong codes: this request is void."
            PairingEnd.NobodyHere -> "Nobody who can let this device in is here: the request is closed."
            PairingEnd.Busy, PairingEnd.TooSoon -> error("a request is never posted for a refused pairing")
        }

        private fun initialData() = data(code = "", status = "Waiting for someone to type the code.")

        private fun data(code: String, status: String) = buildJsonObject {
            put(CODE_KEY, code)
            put(STATUS_KEY, status)
        }

        private fun components(device: Device): List<JsonObject> = listOf(
            component("root", "Column") {
                put("children", JsonArray(listOf("heading", "from", CODE_FIELD, ADMIT_BUTTON, "status").map(::JsonPrimitive)))
            },
            component("heading", "Text") {
                put("variant", "h4")
                put("text", "$TITLE: enter the code it shows")
            },
            component("from", "Text") {
                val screen = device.screen?.let { ", as the screen \"$it\"" }.orEmpty()
                put("text", "From ${device.address}$screen.")
            },
            // Obscured, so the page keeps what is typed to itself: an obscured field never syncs
            // live (bee-web's LiveInputs), and the code leaves the page only in the admit record.
            component(CODE_FIELD, "TextField") {
                put("label", "Code")
                put("variant", "obscured")
                put("value", A2uiBindings.binding("/$CODE_KEY"))
            },
            component("admit-label", "Text") { put("text", "Admit") },
            component(ADMIT_BUTTON, "Button") {
                put("child", "admit-label")
                putJsonObject("action") {
                    putJsonObject("event") {
                        put("name", ADMIT_ACTION)
                        putJsonObject("context") { put(CODE_KEY, A2uiBindings.binding("/$CODE_KEY")) }
                    }
                }
            },
            component("status", "Text") {
                put("variant", "caption")
                put("text", A2uiBindings.binding("/$STATUS_KEY"))
            },
        )

        private fun component(id: String, type: String, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
            put("id", id)
            put("component", type)
            fields()
        }
    }
}
