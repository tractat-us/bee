@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.bee.host

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import us.tractat.bee.a2ui.NOTIONS_CATALOG_ID
import us.tractat.bee.a2ui.SurfaceValidator
import us.tractat.bee.a2ui.toSurfaceView
import us.tractat.bee.core.ActionRecord
import us.tractat.bee.core.Basis
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.PairingEnd
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.SurfaceMeta
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The join request on the board (spec §5.7), on the in-memory relay under virtual time. A person's
 * entry is an action record on the request surface, appended here through the host's replica, as
 * a browser's would arrive.
 */
class JoinRequestSurfaceTest {
    private val iain = PeerIdentity.human("iain")
    private val ada = PeerIdentity.agent("ada", Random(1))
    private val device = Device(address = "192.168.1.20", screen = "room")

    private class Rig(val test: TestScope, val star: BoardStar, val pairing: Pairing, val requests: JoinRequestSurface) {
        val relay get() = star.relay
        val board: BoardState get() = relay.state.value

        fun data(surfaceId: String): JsonObject = requireNotNull(board.surfaceDoc(surfaceId)).toSurfaceView(surfaceId).data

        fun status(surfaceId: String): String = data(surfaceId).getValue("status").jsonPrimitive.content

        fun isOpen(surfaceId: String): Boolean = surfaceId in board.openSurfaces()

        fun type(surfaceId: String, actor: PeerIdentity, code: String) {
            relay.mutate { state ->
                val record = ActionRecord(
                    surfaceId = surfaceId,
                    componentId = JoinRequestSurface.ADMIT_BUTTON,
                    actor = actor,
                    name = JoinRequestSurface.ADMIT_ACTION,
                    context = buildJsonObject { put("code", code) },
                    basis = Basis.capture(state, surfaceId),
                    at = star.clock(),
                )
                state.appendRecord(relay.replica, record)
            }
            test.runCurrent()
        }
    }

    private fun rig(body: suspend Rig.() -> Unit) = runTest(StandardTestDispatcher()) {
        val star = BoardStar(this).apply { start() }
        val pairing = Pairing(star.clock, FixedSecrets(CODE))
        val requests = JoinRequestSurface(star.relay, pairing, star.clock)
        requests.start(backgroundScope)
        runCurrent()
        Rig(this, star, pairing, requests).body()
    }

    private fun Rig.post(): Pair<Opened.Ticket, Deferred<Resolution>> {
        val ticket = assertIs<Opened.Ticket>(pairing.open(device.address))
        val resolution = requests.post(ticket, device)
        test.runCurrent()
        return ticket to resolution
    }

    @Test
    fun requestNamesTheDeviceButNeverItsCode() = rig {
        val (ticket, _) = post()
        val id = JoinRequestSurface.surfaceId(ticket.id)
        val wire = WIRE.encodeToString(BoardState.serializer(), board)
        val shown = data(id).toString() + requireNotNull(board.surfaceDoc(id)).toSurfaceView(id).components.toString()
        assertAll(
            { assertTrue(isOpen(id)) },
            { assertTrue("192.168.1.20" in shown, "the request names the device's address: $shown") },
            { assertTrue("room" in shown, "and its screen name: $shown") },
            { assertFalse(CODE in wire, "the code is nowhere on the board") },
        )
    }

    /**
     * The code field is A2UI's `obscured` input, which bee-web's live typing never syncs, so what
     * a person types stays on their page until Admit puts it in a record (e2e/second-screen).
     */
    @Test
    fun codeFieldIsObscured() = rig {
        val (ticket, _) = post()
        val id = JoinRequestSurface.surfaceId(ticket.id)
        val field = requireNotNull(board.surfaceDoc(id)).toSurfaceView(id).components.getValue(JoinRequestSurface.CODE_FIELD)
        assertEquals("obscured", field.getValue("variant").jsonPrimitive.content)
    }

    @Test
    fun requestSurfaceIsValid() = rig {
        val (ticket, _) = post()
        val id = JoinRequestSurface.surfaceId(ticket.id)
        assertEquals(emptyList(), SurfaceValidator.errors(requireNotNull(board.surfaceDoc(id)).toSurfaceView(id)))
    }

    @Test
    fun personTypingTheCodeAdmitsAndClosesTheRequest() = rig {
        val (ticket, resolution) = post()
        val id = JoinRequestSurface.surfaceId(ticket.id)
        type(id, iain, CODE)
        val admitted = assertIs<Resolution.Admitted>(resolution.await())
        assertAll(
            { assertEquals(iain, admitted.by) },
            { assertTrue(pairing.admits(admitted.key.value)) },
            { assertFalse(isOpen(id), "the request closes") },
            { assertEquals("Admitted by human:iain.", status(id)) },
        )
    }

    @Test
    fun agentEntryIsRefusedAndNotedOnTheSurface() = rig {
        val (ticket, resolution) = post()
        val id = JoinRequestSurface.surfaceId(ticket.id)
        type(id, ada, CODE)
        assertAll(
            { assertFalse(resolution.isCompleted, "an agent's entry admits nothing") },
            { assertTrue(isOpen(id)) },
            { assertEquals("$ada can't admit a device: only a person can.", status(id)) },
            { assertTrue(pairing.isPending(ticket.id)) },
        )
    }

    @Test
    fun wrongEntriesCountDownAndTheThirdVoids() = rig {
        val (ticket, resolution) = post()
        val id = JoinRequestSurface.surfaceId(ticket.id)
        type(id, iain, "000000")
        val afterOne = status(id)
        val codeAfterOne = data(id).getValue("code")
        type(id, iain, "000001")
        type(id, iain, "000002")
        val ended = resolution.await()
        assertAll(
            { assertEquals("That code doesn't match. 2 tries left.", afterOne) },
            { assertEquals(JsonPrimitive(""), codeAfterOne, "the field is cleared for the next try") },
            { assertEquals(Resolution.Ended(PairingEnd.Voided), ended) },
            { assertFalse(isOpen(id)) },
            { assertEquals("Three wrong codes: this request is void.", status(id)) },
        )
    }

    @Test
    fun expiryClosesTheRequest() = rig {
        val (ticket, resolution) = post()
        val id = JoinRequestSurface.surfaceId(ticket.id)
        requests.end(ticket.id, PairingEnd.Expired)
        test.runCurrent()
        val ended = resolution.await()
        assertAll(
            { assertEquals(Resolution.Ended(PairingEnd.Expired), ended) },
            { assertFalse(isOpen(id)) },
            { assertFalse(pairing.isPending(ticket.id)) },
        )
    }

    /** A request this host didn't post, from before a restart, can never be answered, so it closes. */
    @Test
    fun leftOverRequestIsClosed() = rig {
        val stale = JoinRequestSurface.surfaceId(PairingId("from-last-boot"))
        relay.mutate {
            it.openSurface(
                relay.replica,
                stale,
                SurfaceMeta("A device wants to join", NOTIONS_CATALOG_ID, JoinRequestSurface.HOST, Instant.fromEpochMilliseconds(0)),
                listOf(buildJsonObject { put("id", "root"); put("component", "Text"); put("text", "old") }),
                buildJsonObject { put("status", "") },
            )
        }
        test.runCurrent()
        assertFalse(isOpen(stale))
    }

    private class FixedSecrets(private val code: String) : PairingSecrets {
        private var n = 0

        override fun pairingId(): PairingId = PairingId("p${++n}")

        override fun code(): String = code

        override fun key(): DeviceKey = DeviceKey("key-$n")
    }

    private companion object {
        const val CODE = "730519"

        // The board keys maps by dot, which JSON allows only as structured keys.
        val WIRE = Json { allowStructuredMapKeys = true }
    }
}
