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
import kotlin.time.Duration.Companion.hours
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

    private var addresses = 0

    /** A LAN address no pairing here has used yet. */
    private fun fresh(): String = "192.168.1.${++addresses}"

    private fun ticket(from: String = fresh()): Opened.Ticket = assertIs<Opened.Ticket>(pairing.open(from))

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

    /** Pending pairings are capped, from every address together. */
    @Test
    fun pendingPairingsAreCapped() {
        val open = List(Pairing.MAX_PENDING) { ticket() }
        val refused = pairing.open(fresh())
        pairing.end(open.first().id)
        val afterOneEnds = pairing.open(fresh())
        advance(2.minutes)
        val afterExpiry = List(Pairing.MAX_PENDING) { pairing.open(fresh()) }
        assertAll(
            { assertEquals(Opened.Busy, refused) },
            { assertIs<Opened.Ticket>(afterOneEnds) },
            { afterExpiry.forEach { assertIs<Opened.Ticket>(it, "expired pairings hold no place") } },
        )
    }

    /**
     * An expired pairing holds no place, but it stays pending until its owner ends it: the host's
     * timer for it may not have fired yet, and that timer is what closes its request on the board.
     */
    @Test
    fun expiredPairingStaysUntilItsOwnerEndsIt() {
        val expired = ticket()
        advance(2.minutes)
        val next = pairing.open(fresh())
        assertAll(
            { assertIs<Opened.Ticket>(next) },
            { assertTrue(pairing.isPending(expired.id), "another open left the expired pairing to its owner") },
            { assertTrue(pairing.end(expired.id), "its owner's end finds it") },
        )
    }

    @Test
    fun secondPairingFromOneAddressIsRefusedWhileTheFirstWaits() {
        val first = ticket(LAN)
        advance(Pairing.COOLDOWN)
        val second = pairing.open(LAN)
        val elsewhere = pairing.open(OTHER_LAN)
        pairing.end(first.id)
        val afterTheFirstEnds = pairing.open(LAN)
        assertAll(
            { assertEquals(Opened.AlreadyPairing, second) },
            { assertIs<Opened.Ticket>(elsewhere, "another address isn't held up") },
            { assertIs<Opened.Ticket>(afterTheFirstEnds) },
        )
    }

    @Test
    fun anAddressOpensOncePerCooldown() {
        pairing.end(ticket(LAN).id)
        val again = pairing.open(LAN)
        val elsewhere = pairing.open(OTHER_LAN)
        advance(Pairing.COOLDOWN - 1.milliseconds)
        val justBefore = pairing.open(LAN)
        advance(1.milliseconds)
        val after = pairing.open(LAN)
        assertAll(
            { assertEquals(Opened.TooSoon, again) },
            { assertIs<Opened.Ticket>(elsewhere, "another address pairs while one is throttled") },
            { assertEquals(Opened.TooSoon, justBefore) },
            { assertIs<Opened.Ticket>(after) },
        )
    }

    /** Loopback reaches the board without a key, so limiting its pairing would protect nothing. */
    @Test
    fun loopbackIsLimitedOnlyByTheCap() {
        val opened = listOf("127.0.0.1", "127.0.0.1", "::1").map { pairing.open(it) }
        val past = pairing.open("127.0.0.1")
        assertAll(
            { opened.forEach { assertIs<Opened.Ticket>(it) } },
            { assertEquals(Opened.Busy, past) },
        )
    }

    /**
     * However many addresses ask, together they open at most the budget's worth of pairings in its
     * window, ended or not. The window slides: each pairing frees its place an hour after it opened.
     */
    @Test
    fun theBudgetBoundsPairingsAcrossEveryAddress() {
        val budgeted = Pairing(clock = { now }, secrets = ScriptedSecrets(), budget = PairingBudget(3))
        val spent = List(3) {
            assertIs<Opened.Ticket>(budgeted.open(fresh())).also { ticket ->
                budgeted.end(ticket.id)
                advance(1.minutes)
            }
        }
        val overBudget = budgeted.open(fresh())
        val loopback = budgeted.open("127.0.0.1")
        // The first pairing opened 3 minutes ago; its place frees an hour after it opened.
        advance(1.hours - 3.minutes - 1.milliseconds)
        val justBefore = budgeted.open(fresh())
        advance(1.milliseconds)
        val afterTheFirstFrees = budgeted.open(fresh())
        val thenSpentAgain = budgeted.open(fresh())
        assertAll(
            { assertEquals(3, spent.size) },
            { assertEquals(Opened.OverBudget, overBudget) },
            { assertIs<Opened.Ticket>(loopback, "loopback spends no budget") },
            { assertEquals(Opened.OverBudget, justBefore) },
            { assertIs<Opened.Ticket>(afterTheFirstFrees) },
            { assertEquals(Opened.OverBudget, thenSpentAgain) },
        )
    }

    @Test
    fun joinKeyAdmits() {
        val key = pairing.mintJoinKey()
        assertTrue(pairing.admits(key.value))
    }

    private companion object {
        const val LAN = "192.168.1.200"
        const val OTHER_LAN = "192.168.1.201"
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
