package us.tractat.bee.host

import us.tractat.bee.core.PeerIdentity
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The pairing state machine (spec §5.7), under a hand-moved clock and scripted secrets. Every
 * code it mints is `11111n` for the n-th pairing, so a test knows the code a device would show.
 */
class PairingTest {
    private val iain = PeerIdentity.human("iain")
    private val ada = PeerIdentity.agent("ada", Random(1))

    private var now = Instant.parse("2026-11-07T12:00:00Z")
    private val pairing = Pairing(clock = { now }, secrets = ScriptedSecrets())

    private fun advance(by: Duration) {
        now += by
    }

    private fun ticket(): Opened.Ticket = assertIs<Opened.Ticket>(pairing.open())

    @Test
    fun matchingCodeFromHumanAdmits() {
        val ticket = ticket()
        val attempt = pairing.attempt(ticket.id, iain, ticket.code)
        val admitted = assertIs<Attempt.Admitted>(attempt)
        assertAll(
            { assertEquals(iain, admitted.by) },
            { assertTrue(pairing.admits(admitted.key.value), "the minted key admits") },
            { assertFalse(pairing.isPending(ticket.id), "an admitted pairing is over") },
            { assertIs<Attempt.Unknown>(pairing.attempt(ticket.id, iain, ticket.code), "a code admits once") },
        )
    }

    /**
     * An agent's entry is refused even with the right code, and it neither spends a try nor
     * voids the pairing: a person can still admit the device after it.
     */
    @Test
    fun agentEntryNeverAdmits() {
        val ticket = ticket()
        val refused = List(3) { pairing.attempt(ticket.id, ada, ticket.code) }
        val after = pairing.attempt(ticket.id, iain, ticket.code)
        assertAll(
            { refused.forEach { assertEquals(Attempt.NotHuman(ada), it) } },
            { assertIs<Attempt.Admitted>(after, "the agent's entries left the pairing open") },
        )
    }

    @Test
    fun codeExpiresAfterTwoMinutes() {
        val early = ticket()
        val late = ticket()
        advance(2.minutes - 1.milliseconds)
        val justInTime = pairing.attempt(early.id, iain, early.code)
        advance(1.milliseconds)
        val expired = pairing.attempt(late.id, iain, late.code)
        assertAll(
            { assertIs<Attempt.Admitted>(justInTime, "a code is good for its whole two minutes") },
            { assertEquals(Attempt.Expired, expired) },
            { assertFalse(pairing.isPending(late.id), "an expired pairing is over") },
            { assertEquals(Instant.parse("2026-11-07T12:02:00Z"), late.expiresAt) },
        )
    }

    @Test
    fun thirdWrongEntryVoidsPairing() {
        val ticket = ticket()
        val wrong = List(3) { pairing.attempt(ticket.id, iain, "000000") }
        val afterwards = pairing.attempt(ticket.id, iain, ticket.code)
        assertAll(
            { assertEquals(listOf(Attempt.Wrong(triesLeft = 2), Attempt.Wrong(triesLeft = 1), Attempt.Voided), wrong) },
            { assertIs<Attempt.Unknown>(afterwards, "the right code after the third wrong one admits nothing") },
            { assertFalse(pairing.isPending(ticket.id)) },
        )
    }

    /** Each admission mints its own key, so revoking or leaking one device's key touches no other. */
    @Test
    fun admittedKeyIsSingleDevice() {
        val first = ticket()
        val second = ticket()
        val one = assertIs<Attempt.Admitted>(pairing.attempt(first.id, iain, first.code)).key
        val two = assertIs<Attempt.Admitted>(pairing.attempt(second.id, iain, second.code)).key
        assertAll(
            { assertNotEquals(one, two) },
            { assertNotEquals(first.code, second.code, "rig: each pairing shows its own code") },
            { assertTrue(pairing.admits(one.value)) },
            { assertTrue(pairing.admits(two.value)) },
            { assertFalse(pairing.admits("not-a-key")) },
            { assertFalse(pairing.admits(null), "no key admits nothing") },
            { assertFalse(pairing.admits(""), "an empty key admits nothing") },
        )
    }

    @Test
    fun keyNeverShowsInItsString() {
        val ticket = ticket()
        val key = assertIs<Attempt.Admitted>(pairing.attempt(ticket.id, iain, ticket.code)).key
        assertFalse(key.value in key.toString(), "a logged key would leak it: $key")
    }

    /** A device that leaves, or whose two minutes run out on the host's timer, ends its pairing. */
    @Test
    fun endedPairingAdmitsNothing() {
        val ticket = ticket()
        val ended = pairing.end(ticket.id)
        assertAll(
            { assertTrue(ended) },
            { assertFalse(pairing.end(ticket.id), "a pairing ends once") },
            { assertIs<Attempt.Unknown>(pairing.attempt(ticket.id, iain, ticket.code)) },
        )
    }

    /** Pending pairings are capped, so a device on the LAN can't fill the board with join requests. */
    @Test
    fun pendingPairingsAreCapped() {
        val open = List(Pairing.MAX_PENDING) { ticket() }
        val refused = pairing.open()
        pairing.end(open.first().id)
        val afterOneEnds = pairing.open()
        advance(2.minutes)
        val afterExpiry = List(Pairing.MAX_PENDING) { pairing.open() }
        assertAll(
            { assertEquals(Opened.Busy, refused) },
            { assertIs<Opened.Ticket>(afterOneEnds) },
            { afterExpiry.forEach { assertIs<Opened.Ticket>(it, "expired pairings hold no place") } },
        )
    }

    @Test
    fun joinKeyAdmits() {
        val key = pairing.mintJoinKey()
        assertTrue(pairing.admits(key.value))
    }

    /** Pairing ids `p1`, `p2`…, codes `111111`, `111112`…, keys `key-1`, `key-2`… */
    private class ScriptedSecrets : PairingSecrets {
        private var pairings = 0
        private var keys = 0

        override fun pairingId(): PairingId = PairingId("p${++pairings}")

        override fun code(): String = (111_110 + pairings).toString()

        override fun key(): DeviceKey = DeviceKey("key-${++keys}")
    }
}
