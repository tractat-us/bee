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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/** An invite's secret: whoever holds it may redeem it. Its [toString] never shows it, so a log can't leak it. */
@JvmInline
value class InviteSecret(val value: String) {
    override fun toString(): String = "InviteSecret(…)"
}

/**
 * What a host keeps of one invite between boots: its secret's SHA-256 [hash], never the secret,
 * when it [expiresAt], and how many devices it has [redeemed] a key for.
 */
data class StoredInvite(val hash: KeyHash, val expiresAt: Instant, val redeemed: Int)

/** Where a host keeps its invites. Production is [InviteFile]. */
interface InviteStore {
    /** The invites last saved; none if none ever were. */
    fun load(): List<StoredInvite>

    /** Replaces what [load] returns with [invites], whole. */
    fun save(invites: List<StoredInvite>)
}

/** What [Invites.mint] gave. */
sealed interface Mint {
    /** A new invite: [secret], good until [expiresAt]. */
    data class Minted(val secret: InviteSecret, val expiresAt: Instant) : Mint

    /** [Invites.MAX_LIVE] invites are live already; the oldest lapses within [Invites.TTL]. */
    data object TooMany : Mint
}

/**
 * A hosted board's invites (bee#215): links that admit whoever opens them, with no one on the
 * board to admit them. The rule, settled on #215 (2026-11-09), superseding the 2026-11-08 one that
 * a hosted join link admits no one:
 *
 * - **Anyone admitted may [mint] one**, as anyone admitted may invite. Its secret is 256 bits from
 *   [secrets], and the host keeps only its SHA-256 ([KeyHash]), in [store], so a host restart
 *   (every deploy, on Fly) keeps every live invite.
 * - **Reusable for [ttl], a day.** Any device holding the secret [redeem]s it for a device key of
 *   its own, minted as pairing mints one and held in [keys] as a paired key is: the same storage,
 *   and the same revocation, so one person can be evicted alone (#173).
 * - **At most [maxRedemptions] devices per invite**, so a forwarded link can't fill the key file.
 *   At most [MAX_LIVE] invites live at once, so neither can a page that mints in a loop.
 * - **Guessing gets nowhere.** A secret is looked up by its digest, so a lookup's timing says
 *   something about a digest, never about a secret. An address that sends [MAX_UNKNOWN] secrets
 *   the host never made within [UNKNOWN_WINDOW] is refused, without a lookup, until the window
 *   passes. The 256 bits are what stops a guess; the limit keeps the endpoint from being an oracle
 *   for one, and bounds the work.
 * - An invite past its day, or used up, is kept [KEPT_LAPSED] longer, so its page hears it
 *   [InviteEnd.Lapsed] or [InviteEnd.Used], not [InviteEnd.Unknown]; then it is forgotten.
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
) {
    private val lock = Any()

    /** Every invite the host holds, by its secret's hash. */
    private val invites: MutableMap<KeyHash, StoredInvite> = kept.associateByTo(mutableMapOf()) { it.hash }

    /** When each address sent a secret the host never made, within [UNKNOWN_WINDOW], oldest first. */
    private val unknowns = mutableMapOf<String, ArrayDeque<Instant>>()

    /** A new invite, or [Mint.TooMany]. */
    fun mint(): Mint = synchronized(lock) {
        val now = clock()
        forgetLapsed(now)
        val live = invites.values.count { now < it.expiresAt }
        if (live >= MAX_LIVE) {
            logger.warn { "Refused an invite: $MAX_LIVE are live already." }
            return Mint.TooMany
        }
        val secret = InviteSecret(secrets.key().value)
        val minted = StoredInvite(KeyHash.of(secret.value), now + ttl, redeemed = 0)
        invites[minted.hash] = minted
        save()
        logger.info { "Minted an invite, good until ${minted.expiresAt}; ${live + 1} live." }
        Mint.Minted(secret, minted.expiresAt)
    }

    /**
     * [caller] redeems [secret] for a person named [name]: a device key, which admits by the time
     * this returns, or why not.
     */
    fun redeem(caller: Caller, secret: String, name: String): Redeemed {
        val (outcome, key) = decide(caller, secret, name)
        // Outside the lock: saving the key is file I/O, and no other invite waits on it.
        if (key != null) keys.add(key)
        return outcome
    }

    private fun decide(caller: Caller, secret: String, name: String): Pair<Redeemed, DeviceKey?> = synchronized(lock) {
        val now = clock()
        forgetLapsed(now)
        val person = InviteWire.nameOrNull(name) ?: return refused(InviteEnd.BadName)
        val tries = unknowns[caller.address]
        if (tries != null && tries.size >= MAX_UNKNOWN) return refused(InviteEnd.TooManyTries)
        val held = invites[KeyHash.of(secret)]
        when {
            held == null -> {
                unknowns.getOrPut(caller.address, ::ArrayDeque).addLast(now)
                refused(InviteEnd.Unknown)
            }
            now >= held.expiresAt -> refused(InviteEnd.Lapsed)
            held.redeemed >= maxRedemptions -> refused(InviteEnd.Used)
            else -> {
                val used = held.copy(redeemed = held.redeemed + 1)
                invites[held.hash] = used
                // The count is saved before the key: a crash between leaves a use spent and no key, never a key the cap missed.
                save()
                logger.info { "An invite admitted a device as human:$person (${used.redeemed} of $maxRedemptions)." }
                val key = secrets.key()
                Redeemed.Key(key.value) to key
            }
        }
    }

    private fun refused(reason: InviteEnd): Pair<Redeemed, DeviceKey?> = Redeemed.Refused(reason) to null

    /** Forgets invites [KEPT_LAPSED] past their day, and unknown secrets older than [UNKNOWN_WINDOW]. */
    private fun forgetLapsed(now: Instant) {
        if (invites.values.removeIf { now >= it.expiresAt + KEPT_LAPSED }) save()
        unknowns.values.forEach { tries -> while (tries.firstOrNull()?.let { now >= it + UNKNOWN_WINDOW } == true) tries.removeFirst() }
        unknowns.values.removeIf { it.isEmpty() }
    }

    /**
     * Saves every invite. If the save fails, the invites still work until this host stops, and the
     * failure is logged: a lost invite costs a new link, not the board.
     */
    private fun save() {
        try {
            store.save(invites.values.toList())
        } catch (failure: IOException) {
            logger.error(failure) { "Couldn't save the board's invites; they work until this host stops." }
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
         * Invites live at once. A page mints one when its Invite opens and keeps it, so a board's
         * people mint a handful a day; with [MAX_REDEMPTIONS], this bounds the keys a day of
         * invites can add.
         */
        const val MAX_LIVE: Int = 100

        /** How long a lapsed or used-up invite is remembered, so its page hears why. */
        val KEPT_LAPSED: Duration = 24.hours

        /** Unknown secrets one address may send in [UNKNOWN_WINDOW] before it is refused unread. */
        const val MAX_UNKNOWN: Int = 10

        val UNKNOWN_WINDOW: Duration = 10.minutes

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
        ): Invites {
            val kept = when (reset) {
                KeyReset.Keep, KeyReset.NewLink -> store.load()
                KeyReset.ForgetDevices -> emptyList<StoredInvite>().also(store::save)
            }
            return Invites(clock, secrets, keys, store, kept, ttl, maxRedemptions)
        }
    }
}

/**
 * Invites in one file, [path], beside the key file and written as it is ([writeOwnerOnly]): a
 * comment, then an `invite <hash> <expiresAt> <redeemed>` line per invite. A file of its own, not
 * lines in the key file, so a host rolled back to before invites still reads its keys.
 *
 * A file that doesn't read is logged and replaced at the next save: every invite in it stops
 * working, which costs a new link, where refusing to boot would cost the hosted board.
 */
class InviteFile(private val path: Path) : InviteStore {
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
                appendLine("# bee-host invites: a SHA-256 of each invite's secret, when it lapses, and how many devices it admitted. Owner-only.")
                invites.forEach { appendLine("$INVITE ${it.hash.value} ${it.expiresAt} ${it.redeemed}") }
            },
        )
    }

    private fun parse(line: String): StoredInvite {
        val fields = line.split(' ')
        require(fields.size == FIELDS && fields[0] == INVITE) { "not an invite line" }
        return StoredInvite(KeyHash(fields[1]), Instant.parse(fields[2]), fields[3].toInt().also { require(it >= 0) })
    }

    private fun unread(failure: Exception): List<StoredInvite> {
        logger.error(failure) { "$path doesn't read as bee-host invites; every invite in it stops working." }
        return emptyList()
    }

    companion object {
        /** The invite file's name in the host's data dir, beside [KeyFile.NAME]. */
        const val NAME: String = "invites"

        private const val INVITE = "invite"
        private const val FIELDS = 4
    }
}
