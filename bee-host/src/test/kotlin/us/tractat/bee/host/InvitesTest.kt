package us.tractat.bee.host

import us.tractat.bee.core.InviteEnd
import us.tractat.bee.core.Redeemed
import us.tractat.kuilt.test.assertAll
import java.io.IOException
import java.nio.file.Files
import java.security.SecureRandom
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
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
 * how many one minter and the board may have, what a failed save does, and what a restart on the
 * same [InviteFile] keeps. The same rules at the host's door are [HostedInviteTest]'s.
 */
class InvitesTest {
    private val dir = Files.createTempDirectory("w215-invites-")
    private val file = dir.resolve(InviteFile.NAME)
    private var now = Instant.parse("2026-11-09T12:00:00Z")
    private val secrets = SecureSecrets(SecureRandom())
    private val keyStore = MemoryKeyStore()
    private val keys = HostKeys.boot(keyStore, secrets, KeyReset.Keep, JoinLink.Admits)

    /** How many device keys [keys] has saved. */
    private fun devices(): Int = keyStore.load()?.devices?.size ?: 0

    @AfterTest
    fun clean() {
        dir.toFile().deleteRecursively()
    }

    private fun boot(
        reset: KeyReset = KeyReset.Keep,
        maxRedemptions: Int = Invites.MAX_REDEMPTIONS,
        dailyRedemptions: Int = Invites.DAILY_REDEMPTIONS,
        store: InviteStore = InviteFile(file) { now },
    ) = Invites.boot(store, reset, { now }, secrets, keys, maxRedemptions = maxRedemptions, dailyRedemptions = dailyRedemptions)

    private fun Invites.minted(by: KeyHash = OWNER): String = assertIs<Mint.Minted>(mint(by)).secret.value

    private fun Invites.join(secret: String, name: String = "sam") = redeem(secret, name)

    @Test
    fun anInviteAdmitsUntilItsDayIsOverAndNotAfter() {
        val invites = boot()
        val secret = invites.minted()
        now += Invites.TTL - 1.nanoseconds
        val lastMoment = invites.join(secret)
        now += 1.nanoseconds
        val lapsed = invites.join(secret)
        assertAll(
            { assertTrue(keys.admits(assertIs<Redeemed.Key>(lastMoment).key), "its key is held like a paired one") },
            { assertEquals(Redeemed.Refused(InviteEnd.Lapsed), lapsed) },
        )
    }

    @Test
    fun anInviteAdmitsAsManyDevicesAsItsCapAndNoMore() {
        val invites = boot()
        val secret = invites.minted()
        val admitted = (1..Invites.MAX_REDEMPTIONS).map { invites.join(secret) }
        val past = invites.join(secret)
        assertAll(
            { assertEquals(Invites.MAX_REDEMPTIONS, admitted.filterIsInstance<Redeemed.Key>().map { it.key }.toSet().size, "each a key of its own") },
            { assertEquals(Redeemed.Refused(InviteEnd.Used), past) },
            { assertIs<Redeemed.Key>(invites.join(invites.minted()), "rig: another invite still admits") },
        )
    }

    /** An unknown secret costs a hash and a lookup, and the host keeps nothing of it, however many come. */
    @Test
    fun anUnknownSecretIsRefusedAndLeavesNothingBehind() {
        val invites = boot()
        val secret = invites.minted()
        val unknowns = (1..1_000).map { invites.join("guess-$it") }
        assertAll(
            { assertEquals(List(1_000) { Redeemed.Refused(InviteEnd.Unknown) }, unknowns) },
            { assertEquals(1, invites.holding(), "only the one real invite") },
            { assertIs<Redeemed.Key>(invites.join(secret), "the real secret still admits: guesses lock no one out") },
        )
    }

    @Test
    fun aNameAPersonCantTakeIsRefusedAndSpendsNothing() {
        val invites = boot(maxRedemptions = 1)
        val secret = invites.minted()
        val names = listOf("", "  ", "a:b", "a#b", "x".repeat(41), "line\nbreak", "ad‮min", "a‍b", "a b", "a b")
        val refusals = names.map { invites.join(secret, it) }
        assertAll(
            { assertEquals(List(names.size) { Redeemed.Refused(InviteEnd.BadName) }, refusals, "control, format (bidi, zero-width) and separator characters too") },
            { assertIs<Redeemed.Key>(invites.join(secret, " alex "), "the one use is still there") },
        )
    }

    @Test
    fun aLapsedInviteIsRememberedForADayThenForgotten() {
        val invites = boot()
        val secret = invites.minted()
        now += Invites.TTL + Invites.KEPT_LAPSED - 1.nanoseconds
        val remembered = invites.join(secret)
        now += 1.nanoseconds
        val forgotten = invites.join(secret)
        assertAll(
            { assertEquals(Redeemed.Refused(InviteEnd.Lapsed), remembered) },
            { assertEquals(Redeemed.Refused(InviteEnd.Unknown), forgotten) },
            { assertEquals(0, invites.holding()) },
            { assertFalse(Files.readString(file).lines().any { it.startsWith("invite ") }, "forgotten on disk too") },
        )
    }

    @Test
    fun oneMinterHasAtMostItsShareLiveAndTheBoardItsCap() {
        val invites = boot()
        repeat(Invites.MAX_LIVE_PER_MINTER) { invites.minted(OWNER) }
        val pastShare = invites.mint(OWNER)
        val minters = (1..Invites.MAX_LIVE / Invites.MAX_LIVE_PER_MINTER - 1).map { KeyHash.of("minter-$it") }
        minters.forEach { by -> repeat(Invites.MAX_LIVE_PER_MINTER) { invites.minted(by) } }
        val pastBoard = invites.mint(KeyHash.of("another"))
        now += Invites.TTL
        assertAll(
            { assertEquals(Mint.MinterFull, pastShare) },
            { assertEquals(Mint.BoardFull, pastBoard) },
            { assertIs<Mint.Minted>(invites.mint(OWNER), "once they lapse, the owner mints again") },
        )
    }

    @Test
    fun theBoardAdmitsAtMostItsDailyBudgetByInvite() {
        val invites = boot(dailyRedemptions = 3)
        val first = invites.minted()
        val second = invites.minted(KeyHash.of("another"))
        val admitted = listOf(invites.join(first), invites.join(first), invites.join(second))
        val past = invites.join(second)
        now += Invites.REDEMPTION_WINDOW
        assertAll(
            { assertTrue(admitted.all { it is Redeemed.Key }) },
            { assertEquals(Redeemed.Refused(InviteEnd.DailyLimit), past, "a budget for the board, across invites") },
            { assertIs<Redeemed.Key>(invites.join(invites.minted()), "the window passes") },
        )
    }

    /** A use the host couldn't save gives no key, so a restart can't find the count lower than the keys given. */
    @Test
    fun aRedemptionWhoseUseCantBeSavedGivesNoKey() {
        val store = FlakyStore()
        val invites = boot(maxRedemptions = 1, store = store)
        val secret = invites.minted()
        store.failing = true
        val failed = invites.join(secret)
        store.failing = false
        val retried = invites.join(secret)
        assertAll(
            { assertEquals(Redeemed.Refused(InviteEnd.Unavailable), failed) },
            { assertEquals(1, devices(), "only the retry added a key") },
            { assertIs<Redeemed.Key>(retried, "the failed try spent nothing") },
            { assertEquals(1, store.saved.single().redeemed, "and what was saved is the one use") },
        )
    }

    @Test
    fun aRestartKeepsEachInviteItsMinterAndItsCount() {
        val first = boot(maxRedemptions = 2)
        val secret = first.minted()
        first.join(secret)
        val second = boot(maxRedemptions = 2)
        val text = Files.readString(file)
        assertAll(
            { assertIs<Redeemed.Key>(second.join(secret), "the invite survived") },
            { assertEquals(Redeemed.Refused(InviteEnd.Used), second.join(secret), "and so did its count") },
            { assertTrue(OWNER.value in text, "its minter is kept, by its key's hash") },
            { assertFalse(secret in text, "the file holds the secret's hash, not the secret") },
            { assertTrue(KeyHash.of(secret).value in text, "rig: it does hold the hash") },
        )
    }

    @Test
    fun forgettingDevicesForgetsInvitesToo() {
        val secret = boot().minted()
        val kept = boot(KeyReset.NewLink).join(secret)
        val forgot = boot(KeyReset.ForgetDevices).join(secret)
        assertAll(
            { assertIs<Redeemed.Key>(kept, "a new join link keeps invites") },
            { assertEquals(Redeemed.Refused(InviteEnd.Unknown), forgot) },
        )
    }

    @Test
    fun aFileThatDoesntReadIsMovedAsideAndCostsTheInvitesNotTheBoot() {
        Files.writeString(file, "invite not-enough-fields\n")
        val invites = boot()
        val aside = dir.listDirectoryEntries().map { it.name }.filter { it.startsWith("${InviteFile.NAME}.unreadable-") }
        assertAll(
            { assertEquals(0, invites.holding()) },
            { assertEquals(listOf("${InviteFile.NAME}.unreadable-${now.epochSeconds}"), aside, "kept for whoever looks into it") },
            { assertEquals("invite not-enough-fields\n", Files.readString(dir.resolve(aside.single()))) },
            { assertIs<Mint.Minted>(invites.mint(OWNER), "it mints again, and the save writes a fresh file") },
            { assertEquals(1, boot().holding()) },
        )
    }

    @Test
    fun theSecretAndTheKeyNeverShowInAToString() {
        val minted = assertIs<Mint.Minted>(boot().mint(OWNER))
        val key = assertIs<Redeemed.Key>(boot().join(minted.secret.value))
        assertAll(
            { assertFalse(minted.secret.value in minted.toString()) },
            { assertFalse(key.key in key.toString()) },
        )
    }

    /** An [InviteStore] in memory whose saves fail while [failing]. */
    private class FlakyStore : InviteStore {
        @Volatile var failing = false
        var saved: List<StoredInvite> = emptyList()
            private set

        override fun load(): List<StoredInvite> = saved

        override fun save(invites: List<StoredInvite>) {
            if (failing) throw IOException("disk full")
            saved = invites
        }
    }

    private companion object {
        val OWNER = KeyHash.of("the-owner-key")
    }
}
