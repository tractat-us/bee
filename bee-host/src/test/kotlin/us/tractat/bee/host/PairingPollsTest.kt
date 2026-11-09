@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.bee.host

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import us.tractat.bee.core.ActionRecord
import us.tractat.bee.core.Basis
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.PairingEnd
import us.tractat.bee.core.PairingMessage
import us.tractat.bee.core.PairingPoll
import us.tractat.bee.core.PairingWire
import us.tractat.bee.core.PeerIdentity
import us.tractat.kuilt.test.assertAll
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Pairing by poll (bee#209), on the in-memory relay under virtual time, with the production
 * [Pairing.DWELL], [Pairing.TTL] and [PairingPolls.IDLE]: no stream anywhere, only [PairingPolls.open]
 * and [PairingPolls.poll], as a device behind a proxy that holds a stream back pairs.
 */
class PairingPollsTest {
    private val iain = PeerIdentity.human("iain")
    private val device = Device(Caller.byPeer("192.168.1.20"), screen = "room")

    private class Rig(val test: TestScope, val star: BoardStar, val pairing: Pairing, val polls: PairingPolls) {
        val board: BoardState get() = star.relay.state.value

        /** Polls [token] every [PairingWire.POLL_EVERY] for [span], as the page does, and answers the last reply. */
        fun keepPolling(token: String, span: Duration): PairingMessage? {
            var last: PairingMessage? = null
            repeat((span / PairingWire.POLL_EVERY).toInt()) {
                test.advanceTimeBy(PairingWire.POLL_EVERY)
                test.runCurrent()
                last = polls.poll(token)
            }
            return last
        }

        /** [actor] types [code] into pairing [id]'s join request, as a browser's admit record arrives. */
        fun type(id: PairingId, actor: PeerIdentity, code: String) {
            val surfaceId = JoinRequestSurface.surfaceId(id)
            star.relay.mutate { state ->
                val record = ActionRecord(
                    surfaceId = surfaceId,
                    componentId = JoinRequestSurface.ADMIT_BUTTON,
                    actor = actor,
                    name = JoinRequestSurface.ADMIT_ACTION,
                    context = buildJsonObject { put("code", code) },
                    basis = Basis.capture(state, surfaceId),
                    at = star.clock(),
                )
                state.appendRecord(star.relay.replica, record)
            }
            test.runCurrent()
        }
    }

    private fun rig(body: suspend Rig.() -> Unit) = runTest(StandardTestDispatcher()) {
        val star = BoardStar(this).apply { start() }
        val pairing = Pairing(star.clock, SecureSecrets(SecureRandom()), memoryKeys())
        val requests = JoinRequestSurface(star.relay, pairing, star.clock)
        requests.start(backgroundScope)
        runCurrent()
        val desk = PairingDesk(pairing, CompletableDeferred(requests), Pairing.DWELL, star.clock, Admitters.WHOEVER_COMES)
        Rig(this, star, pairing, PairingPolls(desk, backgroundScope, SecureRandom())).body()
    }

    private suspend fun Rig.openCode(): Pair<PairingPoll, PairingMessage.Code> {
        val opened = polls.open(device)
        return opened to assertIs<PairingMessage.Code>(opened.message)
    }

    @Test
    fun aPollGetsTheCodeThenTheKeyOnceAPersonTypesIt() = rig {
        val (opened, code) = openCode()
        val beforeTheDwell = polls.poll(opened.token)
        val pastTheDwell = keepPolling(opened.token, Pairing.DWELL + PairingWire.POLL_EVERY)
        val id = PairingId(code.pairing)
        val requestUp = JoinRequestSurface.surfaceId(id) in board.openSurfaces()
        type(id, iain, code.code)
        val key = assertIs<PairingMessage.Key>(polls.poll(opened.token))
        assertAll(
            { assertEquals(code, beforeTheDwell, "a poll before anyone types gets the code again") },
            { assertEquals(code, pastTheDwell) },
            { assertTrue(requestUp, "rig: the request went up after the dwell, the device polling") },
            { assertTrue(pairing.admits(key.key), "the key admits the device") },
        )
    }

    /** The pairing id is on the board for all to read, so it, or any other token, fetches nothing. */
    @Test
    fun onlyTheTokenTheDeviceWasGivenFetchesItsPairing() = rig {
        val (opened, code) = openCode()
        keepPolling(opened.token, Pairing.DWELL + PairingWire.POLL_EVERY)
        type(PairingId(code.pairing), iain, code.code)
        assertAll(
            { assertNull(polls.poll(code.pairing), "the pairing id is no token") },
            { assertNull(polls.poll("not-a-token")) },
            { assertNull(polls.poll("")) },
            { assertIs<PairingMessage.Key>(polls.poll(opened.token), "the positive control: the device's own token") },
        )
    }

    /** A code nobody types in time ends the pairing; the device is told, and then its token stops answering. */
    @Test
    fun anExpiredPairingSaysSoThenItsTokenAnswersNothing() = rig {
        val (opened, code) = openCode()
        val last = keepPolling(opened.token, Pairing.TTL + PairingWire.POLL_EVERY)
        test.advanceTimeBy(PairingPolls.IDLE + 1.milliseconds)
        test.runCurrent()
        assertAll(
            { assertEquals(PairingMessage.Ended(PairingEnd.Expired), last) },
            { assertFalse(pairing.isPending(PairingId(code.pairing))) },
            { assertNull(polls.poll(opened.token), "a token for a pairing that's over answers nothing") },
        )
    }

    /** A device that stops asking has gone, as a dropped stream has: its request is withdrawn, and a late code admits nothing. */
    @Test
    fun aDeviceThatStopsPollingEndsItsPairingAndWithdrawsItsRequest() = rig {
        val (opened, code) = openCode()
        keepPolling(opened.token, Pairing.DWELL + PairingWire.POLL_EVERY)
        val id = PairingId(code.pairing)
        val request = JoinRequestSurface.surfaceId(id)
        val upWhilePolling = request in board.openSurfaces()
        test.advanceTimeBy(PairingPolls.IDLE + 1.milliseconds)
        test.runCurrent()
        assertAll(
            { assertTrue(upWhilePolling, "rig: the request was up while the device polled") },
            { assertFalse(pairing.isPending(id), "the pairing ended") },
            { assertFalse(request in board.openSurfaces(), "its request was withdrawn") },
            { assertNull(polls.poll(opened.token)) },
        )
    }
}
