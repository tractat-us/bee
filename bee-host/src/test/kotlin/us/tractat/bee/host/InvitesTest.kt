package us.tractat.bee.host

import us.tractat.bee.core.InviteEnd
import us.tractat.bee.core.Redeemed
import us.tractat.kuilt.test.assertAll
import java.nio.file.Files
import java.security.SecureRandom
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant

/**
 * A hosted board's invites (bee#215) on an injected clock: how long one admits, how many devices,
 * what an unknown secret costs, and what a restart on the same [InviteFile] keeps. The same rules
 * at the host's door are [HostedInviteTest]'s.
 */
class InvitesTest {
    private val dir = Files.createTempDirectory("w215-invites-")
    private val file = dir.resolve(InviteFile.NAME)
    private var now = Instant.parse("2026-11-09T12:00:00Z")
    private val secrets = SecureSecrets(SecureRandom())
    private val keys = memoryKeys(secrets)

    @AfterTest
    fun clean() {
        dir.toFile().deleteRecursively()
    }

    private fun boot(reset: KeyReset = KeyReset.Keep, maxRedemptions: Int = Invites.MAX_REDEMPTIONS) =
        Invites.boot(InviteFile(file), reset, { now }, secrets, keys, maxRedemptions = maxRedemptions)

    private fun Invites.minted(): String = assertIs<Mint.Minted>(mint()).secret.value

    private fun Invites.redeemFrom(address: String, secret: String, name: String = "sam") = redeem(Caller.forwarded(address), secret, name)

    @Test
    fun anInviteAdmitsUntilItsDayIsOverAndNotAfter() {
        val invites = boot()
        val secret = invites.minted()
        now += Invites.TTL - 1.nanoseconds
        val lastMoment = invites.redeemFrom(JOINER, secret)
        now += 1.nanoseconds
        val lapsed = invites.redeemFrom(JOINER, secret)
        assertAll(
            { assertTrue(keys.admits(assertIs<Redeemed.Key>(lastMoment).key), "its key is held like a paired one") },
            { assertEquals(Redeemed.Refused(InviteEnd.Lapsed), lapsed) },
        )
    }

    @Test
    fun anInviteAdmitsAsManyDevicesAsItsCapAndNoMore() {
        val invites = boot()
        val secret = invites.minted()
        val admitted = (1..Invites.MAX_REDEMPTIONS).map { invites.redeemFrom("203.0.113.${it + 100}", secret) }
        val past = invites.redeemFrom(JOINER, secret)
        assertAll(
            { assertEquals(Invites.MAX_REDEMPTIONS, admitted.filterIsInstance<Redeemed.Key>().map { it.key }.toSet().size, "each a key of its own") },
            { assertEquals(Redeemed.Refused(InviteEnd.Used), past) },
            { assertIs<Redeemed.Key>(invites.redeemFrom(JOINER, invites.minted()), "rig: another invite still admits") },
        )
    }

    @Test
    fun aSecretTheHostNeverMadeIsUnknownAndTooManyStopTheAddressAWhile() {
        val invites = boot()
        val secret = invites.minted()
        val unknowns = (1..Invites.MAX_UNKNOWN).map { invites.redeemFrom(GUESSER, "guess-$it") }
        val withTheRealOne = invites.redeemFrom(GUESSER, secret)
        val anotherAddress = invites.redeemFrom(JOINER, secret)
        now += Invites.UNKNOWN_WINDOW
        val later = invites.redeemFrom(GUESSER, secret)
        assertAll(
            { assertEquals(List(Invites.MAX_UNKNOWN) { Redeemed.Refused(InviteEnd.Unknown) }, unknowns) },
            { assertEquals(Redeemed.Refused(InviteEnd.TooManyTries), withTheRealOne, "refused unread, even the real secret") },
            { assertIs<Redeemed.Key>(anotherAddress, "another address is untouched") },
            { assertIs<Redeemed.Key>(later, "the window passes") },
        )
    }

    /** A real secret refused for its day or its cap is no guess, so it doesn't spend the address's tries. */
    @Test
    fun aLapsedOrUsedInviteSpendsNoTries() {
        val invites = boot(maxRedemptions = 1)
        val used = invites.minted().also { invites.redeemFrom(JOINER, it) }
        repeat(Invites.MAX_UNKNOWN) { assertEquals(Redeemed.Refused(InviteEnd.Used), invites.redeemFrom(GUESSER, used)) }
        assertIs<Redeemed.Key>(invites.redeemFrom(GUESSER, invites.minted()))
    }

    @Test
    fun aNameAPersonCantTakeIsRefusedAndSpendsNothing() {
        val invites = boot(maxRedemptions = 1)
        val secret = invites.minted()
        val refusals = listOf("", "  ", "a:b", "a#b", "x".repeat(41), "line\nbreak").map { invites.redeemFrom(JOINER, secret, it) }
        assertAll(
            { assertEquals(List(refusals.size) { Redeemed.Refused(InviteEnd.BadName) }, refusals) },
            { assertIs<Redeemed.Key>(invites.redeemFrom(JOINER, secret, " alex "), "the one use is still there") },
        )
    }

    @Test
    fun aLapsedInviteIsRememberedForADayThenForgotten() {
        val invites = boot()
        val secret = invites.minted()
        now += Invites.TTL + Invites.KEPT_LAPSED - 1.nanoseconds
        val remembered = invites.redeemFrom(JOINER, secret)
        now += 1.nanoseconds
        val forgotten = invites.redeemFrom(JOINER, secret)
        assertAll(
            { assertEquals(Redeemed.Refused(InviteEnd.Lapsed), remembered) },
            { assertEquals(Redeemed.Refused(InviteEnd.Unknown), forgotten) },
            { assertEquals(0, invites.holding()) },
            { assertFalse(Files.readString(file).lines().any { it.startsWith("invite ") }, "forgotten on disk too") },
        )
    }

    @Test
    fun noMoreThanTheCapAreLiveAtOnce() {
        val invites = boot()
        repeat(Invites.MAX_LIVE) { invites.minted() }
        val past = invites.mint()
        now += Invites.TTL
        assertAll(
            { assertEquals(Mint.TooMany, past) },
            { assertIs<Mint.Minted>(invites.mint(), "once they lapse, another may be minted") },
        )
    }

    @Test
    fun aRestartKeepsEachInviteAndItsCount() {
        val first = boot(maxRedemptions = 2)
        val secret = first.minted()
        first.redeemFrom(JOINER, secret)
        val second = boot(maxRedemptions = 2)
        val text = Files.readString(file)
        assertAll(
            { assertIs<Redeemed.Key>(second.redeemFrom("203.0.113.31", secret), "the invite survived") },
            { assertEquals(Redeemed.Refused(InviteEnd.Used), second.redeemFrom("203.0.113.32", secret), "and so did its count") },
            { assertFalse(secret in text, "the file holds the secret's hash, not the secret") },
            { assertTrue(KeyHash.of(secret).value in text, "rig: it does hold the hash") },
        )
    }

    @Test
    fun forgettingDevicesForgetsInvitesToo() {
        val secret = boot().minted()
        val kept = boot(KeyReset.NewLink).redeemFrom(JOINER, secret)
        val forgot = boot(KeyReset.ForgetDevices).redeemFrom("203.0.113.31", secret)
        assertAll(
            { assertIs<Redeemed.Key>(kept, "a new join link keeps invites") },
            { assertEquals(Redeemed.Refused(InviteEnd.Unknown), forgot) },
        )
    }

    @Test
    fun aFileThatDoesntReadCostsTheInvitesNotTheBoot() {
        Files.writeString(file, "invite not-enough-fields\n")
        val invites = boot()
        assertAll(
            { assertEquals(0, invites.holding()) },
            { assertIs<Mint.Minted>(invites.mint(), "it mints again, and the next save replaces the file") },
            { assertEquals(1, boot().holding()) },
        )
    }

    @Test
    fun theSecretAndTheKeyNeverShowInAToString() {
        val minted = assertIs<Mint.Minted>(boot().mint())
        val key = assertIs<Redeemed.Key>(boot().redeemFrom(JOINER, minted.secret.value))
        assertAll(
            { assertFalse(minted.secret.value in minted.toString()) },
            { assertFalse(key.key in key.toString()) },
        )
    }

    private companion object {
        const val JOINER = "203.0.113.30"
        const val GUESSER = "198.51.100.66"
    }
}
