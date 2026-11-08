@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.bee.host

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import us.tractat.bee.a2ui.toSurfaceView
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.PairingEnd
import us.tractat.bee.core.PairingMessage
import us.tractat.kuilt.test.assertAll
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * One device's pairing as the socket serves it (spec §5.7), on the in-memory relay under virtual
 * time, with the production [Pairing.DWELL], [Pairing.COOLDOWN] and [Pairing.TTL]. A [Session]
 * stands in for the socket: what the desk sends it, and a `left` its owner completes to hang up.
 */
class PairingDeskTest {
    private class Session(val sent: MutableList<PairingMessage>, val left: CompletableDeferred<Unit>)

    private class Rig(val test: TestScope, val star: BoardStar, val pairing: Pairing, val desk: PairingDesk) {
        val board: BoardState get() = star.relay.state.value
        private val dialled = mutableListOf<Session>()

        /** Every join request ever posted, open or closed. */
        val requests: List<String> get() = (board.openSurfaces() + board.closedSurfaces()).filter { it.startsWith("bee-pair-") }

        fun dial(address: String): Session {
            val session = Session(mutableListOf(), CompletableDeferred()).also { dialled += it }
            test.backgroundScope.launch { desk.serve(Device(address, screen = null), session.left) { session.sent += it } }
            test.runCurrent()
            return session
        }

        fun Session.hangUp() {
            left.complete(Unit)
            test.runCurrent()
        }

        /** Holds the socket just past the dwell, so the device's request goes on the board. */
        fun holdPastTheDwell() = test.advanceTimeBy(Pairing.DWELL + 1.milliseconds)

        /** Hangs up every device still on, so each withdraws its request before the relay closes. */
        fun hangUpAll() = dialled.forEach { it.hangUp() }

        fun status(surfaceId: String): String =
            requireNotNull(board.surfaceDoc(surfaceId)).toSurfaceView(surfaceId).data.getValue("status").jsonPrimitive.content
    }

    private fun rig(body: suspend Rig.() -> Unit) = runTest(StandardTestDispatcher()) {
        val star = BoardStar(this).apply { start() }
        val pairing = Pairing(star.clock, SecureSecrets(SecureRandom()))
        val requests = JoinRequestSurface(star.relay, pairing, star.clock)
        requests.start(backgroundScope)
        runCurrent()
        Rig(this, star, pairing, PairingDesk(pairing, CompletableDeferred(requests), Pairing.DWELL, star.clock)).apply {
            try {
                body()
            } finally {
                hangUpAll()
            }
        }
    }

    /**
     * A device that dials, reads its code and drops within the dwell writes nothing to the board.
     * It waits out the cooldown each time, so every one of its dials gets a code.
     */
    @Test
    fun dialAndDropWritesNothingToTheBoard() = rig {
        val sessions = List(CYCLES) {
            dial(LAN).also {
                it.hangUp()
                test.advanceTimeBy(Pairing.COOLDOWN)
            }
        }
        assertAll(
            { assertEquals(CYCLES, sessions.count { it.sent.singleOrNull() is PairingMessage.Code }, "rig: every dial got a code") },
            { assertEquals(emptyList(), requests) },
        )
    }

    /**
     * The worst a dialler on one address can do in a burst: each dial that gets a code holds on
     * past the dwell, so its request goes up, then hangs up and dials again.
     */
    @Test
    fun hundredCyclesFromOneAddressLeaveAtMostOneRequest() = rig {
        val sessions = List(CYCLES) {
            dial(LAN).also {
                if (it.sent.firstOrNull() is PairingMessage.Code) holdPastTheDwell()
                it.hangUp()
            }
        }
        val fromLan = requests
        val elsewhere = dial(OTHER_LAN)
        holdPastTheDwell()
        assertAll(
            { assertEquals(1, fromLan.size, "requests on the board from $LAN: $fromLan") },
            { assertEquals(CYCLES - 1, sessions.count { it.sent == listOf(PairingMessage.Ended(PairingEnd.Busy)) }) },
            { assertIs<PairingMessage.Code>(elsewhere.sent.single(), "another address pairs while $LAN is throttled") },
            { assertEquals(fromLan.size + 1, requests.size, "and its request goes up") },
        )
    }

    @Test
    fun secondSimultaneousPairingFromOneAddressIsRefused() = rig {
        val first = dial(LAN)
        holdPastTheDwell()
        // Past the cooldown, so only the waiting pairing can refuse the second.
        test.advanceTimeBy(Pairing.COOLDOWN)
        val second = dial(LAN)
        val elsewhere = dial(OTHER_LAN)
        assertAll(
            { assertIs<PairingMessage.Code>(first.sent.single()) },
            { assertEquals(listOf<PairingMessage>(PairingMessage.Ended(PairingEnd.Busy)), second.sent) },
            { assertIs<PairingMessage.Code>(elsewhere.sent.single()) },
        )
    }

    /** A pairing whose time runs out tells its device so, and its request says why it closed. */
    @Test
    fun expiryIsNotMistakenForTheDeviceLeaving() = rig {
        val device = dial(LAN)
        holdPastTheDwell()
        val request = requests.single()
        test.advanceTimeBy(Pairing.TTL)
        assertAll(
            { assertEquals(PairingMessage.Ended(PairingEnd.Expired), device.sent.last()) },
            { assertEquals("Nobody typed the code in time. The device can ask for a new one.", status(request)) },
            { assertTrue(request !in board.openSurfaces()) },
        )
    }

    /**
     * Another device opening a pairing at the very moment one expires, before that one's timer
     * has run, must not take its ending away from it: the timer still closes the request and tells
     * its device.
     */
    @Test
    fun anOpenAtTheMomentOfExpiryLeavesTheTimerItsPairing() = rig {
        val device = dial(LAN)
        holdPastTheDwell()
        val request = requests.single()
        // Up to the expiry instant: the timer for it is due, and hasn't run.
        test.advanceTimeBy(Pairing.TTL - Pairing.DWELL - 1.milliseconds)
        assertIs<Opened.Ticket>(pairing.open(OTHER_LAN))
        test.runCurrent()
        assertAll(
            { assertEquals(PairingMessage.Ended(PairingEnd.Expired), device.sent.last()) },
            { assertTrue(request !in board.openSurfaces(), "the request closed") },
        )
    }

    private companion object {
        const val CYCLES = 100
        const val LAN = "192.168.1.20"
        const val OTHER_LAN = "192.168.1.30"
    }
}
