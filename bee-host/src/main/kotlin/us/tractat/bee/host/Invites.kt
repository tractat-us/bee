package us.tractat.bee.host

import io.github.oshai.kotlinlogging.KotlinLogging
import us.tractat.bee.core.InviteEnd
import us.tractat.bee.core.InviteWire
import us.tractat.bee.core.Redeemed
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/** An invite's secret: whoever holds it may redeem it. Its [toString] never shows it, so a log can't leak it. */
@JvmInline
value class InviteSecret(val value: String) {
    override fun toString(): String = "InviteSecret(…)"
}

/**
 * What a host keeps of one invite between boots: its secret's SHA-256 [hash], never the secret;
 * [mintedBy], the hash of the key that minted it, never the key; when it [expiresAt]; and how many
 * devices it has [redeemed] a key for. [mintedBy] caps each minter's live invites, and leaves room
 * for an eviction to void the invites its person made (#173).
 */
data class StoredInvite(val hash: KeyHash, val mintedBy: KeyHash, val expiresAt: Instant, val redeemed: Int)

/** Where a host keeps its invites. Production is [InviteFile]. */
interface InviteStore {
    /** The invites last saved; none if none ever were. */
    fun load(): List<StoredInvite>

    /** Replaces what [load] returns with [invites], whole. Throws [IOException] if it can't. */
    fun save(invites: List<StoredInvite>)
}

/** What [Invites.mint] gave. */
sealed interface Mint {
    /** A new invite: [secret], good until [expiresAt]. */
    data class Minted(val secret: InviteSecret, val expiresAt: Instant) : Mint

    /** [Invites.MAX_LIVE] invites are live on the board already; the oldest lapses within [Invites.TTL]. */
    data object BoardFull : Mint

    /** This minter has [Invites.MAX_LIVE_PER_MINTER] live already, so one key can't take every place. */
    data object MinterFull : Mint
}

/**
 * A hosted board's invites (bee#215): links that admit whoever opens them, with no one on the
 * board to admit them. The rule, settled on #215 (2026-11-09), superseding the 2026-11-08 one that
 * a hosted join link admits no one:
 *
 * - **Anyone admitted may [mint] one**, as anyone admitted may invite. Its secret is 256 bits from
 *   [secrets], and the host keeps only its SHA-256 ([KeyHash]), and the hash of the key that
 *   minted it, in [store], so a host restart (every deploy, on Fly) keeps every live invite.
 * - **Reusable for [ttl], a day.** Any device holding the secret [redeem]s it for a device key of
 *   its own, minted as pairing mints one and held in [keys] as a paired key is: the same storage,
 *   and the same revocation.
 * - **Bounded at every level.** One invite admits [maxRedemptions] devices; one minter has at most
 *   [MAX_LIVE_PER_MINTER] live; the board, [MAX_LIVE]; and the board admits at most
 *   [dailyRedemptions] devices by invite in any [REDEMPTION_WINDOW], so devices an invite let in
 *   can't mint their way to an unbounded key file. That last count is held in memory, and starts
 *   again at a restart.
 * - **A redemption is saved before its key is given.** If the save fails the redemption fails
 *   ([InviteEnd.Unavailable]), so a restart can never find a count lower than the keys it gave.
 * - **Guessing gets nowhere, and costs the host nothing.** A secret is looked up by its digest, so
 *   a lookup's timing says something about a digest, never about a secret, and an unknown one
 *   leaves nothing behind. The 256 bits are the defence: no per-address limit, which an IPv6
 *   caller would sidestep with a fresh address per guess and the host would pay to keep.
 * - An invite past its day, or used up, is kept [KEPT_LAPSED] longer, so its page hears it
 *   [InviteEnd.Lapsed] or [InviteEnd.Used], not [InviteEnd.Unknown]; then it is forgotten. With
 *   [MAX_LIVE] live at once, the host holds a few hundred at most, so sweeping them is cheap.
 *
 * Every method is synchronous and thread-safe; [clock], [secrets], [store] and [keys] are its only inputs.
 */
class Invites private constructor(
    private val clock: () -> Instant,
    private val secrets: PairingSecrets,
    private val keys: HostKeys,
    private val store: InviteStore,
    kept: List<StoredInvite>,
    private val ttl: Duration,
    private val maxRedemptions: Int,
    private val dailyRedemptions: Int,
) {
    private val lock = Any()

    /** Every invite the host holds, by its secret's hash: bounded by [MAX_LIVE] and [KEPT_LAPSED]. */
    private val invites: MutableMap<KeyHash, StoredInvite> = kept.associateByTo(mutableMapOf()) { it.hash }

    /** When each redemption within the last [REDEMPTION_WINDOW] was, oldest first: at most [dailyRedemptions]. */
    private val redemptions = ArrayDeque<Instant>()

    /** A new invite minted by the key whose hash is [by], or why not. */
    fun mint(by: KeyHash): Mint = synchronized(lock) {
        val now = clock()
        forgetLapsed(now)
        val live = invites.values.filter { now < it.expiresAt }
        when {
            live.size >= MAX_LIVE -> Mint.BoardFull.also { logger.warn { "Refused an invite: $MAX_LIVE are live on the board already." } }
            live.count { it.mintedBy == by } >= MAX_LIVE_PER_MINTER -> Mint.MinterFull.also { logger.warn { "Refused an invite: its minter has $MAX_LIVE_PER_MINTER live already." } }
            else -> {
                val secret = InviteSecret(secrets.key().value)
                val minted = StoredInvite(KeyHash.of(secret.value), by, now + ttl, redeemed = 0)
                invites[minted.hash] = minted
                // A mint that isn't saved still works until this host stops: a lost invite costs a new link.
                trySave("An invite wasn't saved; it works until this host stops.")
                logger.info { "Minted an invite, good until ${minted.expiresAt}; ${live.size + 1} live." }
                Mint.Minted(secret, minted.expiresAt)
            }
        }
    }

    /** Redeems [secret] for a person named [name]: a device key, which admits by the time this returns, or why not. */
    fun redeem(secret: String, name: String): Redeemed {
        val (outcome, key) = decide(secret, name)
        // Outside the lock: saving the key is file I/O, and no other invite waits on it.
        if (key != null) keys.add(key)
        return outcome
    }

    private fun decide(secret: String, name: String): Pair<Redeemed, DeviceKey?> = synchronized(lock) {
        val now = clock()
        forgetLapsed(now)
        val person = InviteWire.nameOrNull(name) ?: return refused(InviteEnd.BadName)
        val held = invites[KeyHash.of(secret)]
        when {
            held == null -> refused(InviteEnd.Unknown)
            now >= held.expiresAt -> refused(InviteEnd.Lapsed)
            held.redeemed >= maxRedemptions -> refused(InviteEnd.Used)
            redemptions.size >= dailyRedemptions -> refused(InviteEnd.DailyLimit).also {
                logger.warn { "Refused a redemption: $dailyRedemptions devices joined by invite in the last $REDEMPTION_WINDOW." }
            }
            else -> {
                val used = held.copy(redeemed = held.redeemed + 1)
                invites[held.hash] = used
                // The count is saved before the key is given, and a count that can't be saved gives
                // no key: a restart never finds fewer uses spent than keys handed out.
                try {
                    store.save(invites.values.toList())
                } catch (failure: IOException) {
                    invites[held.hash] = held
                    logger.error(failure) { "Couldn't save an invite's use, so it gave no key." }
                    return refused(InviteEnd.Unavailable)
                }
                redemptions.addLast(now)
                logger.info { "An invite admitted a device as human:$person (${used.redeemed} of $maxRedemptions)." }
                val key = secrets.key()
                Redeemed.Key(key.value) to key
            }
        }
    }

    private fun refused(reason: InviteEnd): Pair<Redeemed, DeviceKey?> = Redeemed.Refused(reason) to null

    /** Forgets invites [KEPT_LAPSED] past their day, and redemptions older than [REDEMPTION_WINDOW]: both bounded, so cheap. */
    private fun forgetLapsed(now: Instant) {
        while (redemptions.firstOrNull()?.let { now >= it + REDEMPTION_WINDOW } == true) redemptions.removeFirst()
        if (invites.values.removeIf { now >= it.expiresAt + KEPT_LAPSED }) trySave("Forgotten invites weren't saved; the next save drops them.")
    }

    private fun trySave(failed: String) {
        try {
            store.save(invites.values.toList())
        } catch (failure: IOException) {
            logger.error(failure) { failed }
        }
    }

    /** How many invites the host holds, lapsed ones it still remembers included. */
    internal fun holding(): Int = synchronized(lock) { invites.size }

    companion object {
        /** How long an invite admits, from its minting (#215). */
        val TTL: Duration = 24.hours

        /**
         * Devices one invite admits. A team's meeting is well under this, so a link shared to one
         * group never runs out, and one forwarded past it adds at most this many keys to the key file.
         */
        const val MAX_REDEMPTIONS: Int = 25

        /**
         * Invites live on the board at once. A page mints one when its Invite first opens and
         * keeps it for the tab, so a board's people mint a handful a day.
         */
        const val MAX_LIVE: Int = 100

        /** Invites one key may have live at once, so one key, the owner's included, can't take all of [MAX_LIVE]. */
        const val MAX_LIVE_PER_MINTER: Int = 10

        /**
         * Devices the whole board admits by invite in any [REDEMPTION_WINDOW]. Four full invites'
         * worth: more than a small team's day, and a bound on the keys a day of invites adds
         * whatever the devices they let in mint.
         */
        const val DAILY_REDEMPTIONS: Int = 100

        val REDEMPTION_WINDOW: Duration = 24.hours

        /** How long a lapsed or used-up invite is remembered, so its page hears why. */
        val KEPT_LAPSED: Duration = 24.hours

        /**
         * The invites [store] holds, after [reset]. [KeyReset.ForgetDevices] forgets them too,
         * unread, since every key they gave stops admitting: a live invite would let anyone back in.
         */
        @Suppress("LongParameterList")
        fun boot(
            store: InviteStore,
            reset: KeyReset,
            clock: () -> Instant,
            secrets: PairingSecrets,
            keys: HostKeys,
            ttl: Duration = TTL,
            maxRedemptions: Int = MAX_REDEMPTIONS,
            dailyRedemptions: Int = DAILY_REDEMPTIONS,
        ): Invites {
            val kept = when (reset) {
                KeyReset.Keep, KeyReset.NewLink -> store.load()
                KeyReset.ForgetDevices -> emptyList<StoredInvite>().also(store::save)
            }
            return Invites(clock, secrets, keys, store, kept, ttl, maxRedemptions, dailyRedemptions)
        }
    }
}

/**
 * Invites in one file, [path], beside the key file and written as it is ([writeOwnerOnly]): a
 * comment, then an `invite <hash> <minted-by> <expiresAt> <redeemed>` line per invite. A file of
 * its own, not lines in the key file, so a host rolled back to before invites still reads its keys.
 *
 * A file that doesn't read is moved aside, to `invites.unreadable-<time>` by [clock], and logged,
 * and the host boots with no invites: an unreadable file can only revoke access, never grant it,
 * and refusing to boot would cost the hosted board for what one new link replaces.
 */
class InviteFile(private val path: Path, private val clock: () -> Instant) : InviteStore {
    override fun load(): List<StoredInvite> {
        val lines = try {
            Files.readAllLines(path)
        } catch (_: NoSuchFileException) {
            return emptyList()
        } catch (failure: AccessDeniedException) {
            return unread(failure)
        } catch (failure: CharacterCodingException) {
            return unread(failure)
        }
        return try {
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.map(::parse)
        } catch (failure: IllegalArgumentException) {
            unread(failure)
        }
    }

    override fun save(invites: List<StoredInvite>) {
        writeOwnerOnly(
            path,
            buildString {
                appendLine("# bee-host invites: a SHA-256 of each invite's secret and of its minter's key, when it lapses, and how many devices it admitted. Owner-only.")
                invites.forEach { appendLine("$INVITE ${it.hash.value} ${it.mintedBy.value} ${it.expiresAt} ${it.redeemed}") }
            },
        )
    }

    private fun parse(line: String): StoredInvite {
        val fields = line.split(' ')
        require(fields.size == FIELDS && fields[0] == INVITE) { "not an invite line" }
        return StoredInvite(KeyHash(fields[1]), KeyHash(fields[2]), Instant.parse(fields[3]), fields[4].toInt().also { require(it >= 0) })
    }

    /** Moves the unreadable file aside, so the next save can't overwrite what went wrong, and boots with none. */
    private fun unread(failure: Exception): List<StoredInvite> {
        val aside = path.resolveSibling("${path.fileName}.unreadable-${clock().epochSeconds}")
        try {
            Files.move(path, aside)
            logger.error(failure) { "$path doesn't read as bee-host invites: moved it to $aside. Every invite in it stops working." }
        } catch (moving: IOException) {
            logger.error(failure) { "$path doesn't read as bee-host invites, and couldn't be moved aside (${moving.message}). Every invite in it stops working." }
        }
        return emptyList()
    }

    companion object {
        /** The invite file's name in the host's data dir, beside [KeyFile.NAME]. */
        const val NAME: String = "invites"

        private const val INVITE = "invite"
        private const val FIELDS = 5
    }
}
