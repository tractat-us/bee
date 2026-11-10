package us.tractat.bee.host

import io.github.oshai.kotlinlogging.KotlinLogging
import us.tractat.bee.core.PairingEnd
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.PeerKind
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** One pairing's id. It names the join request on the board, so it is not a secret. */
@JvmInline
value class PairingId(val value: String)

/** A device's key to the board and claims endpoints. Its [toString] never shows it, so a log can't leak it. */
@JvmInline
value class DeviceKey(val value: String) {
    override fun toString(): String = "DeviceKey(…)"
}

/** Where pairing gets its randomness. Production is [SecureSecrets]; tests script it. */
interface PairingSecrets {
    fun pairingId(): PairingId

    /** A six-digit code, leading zeros kept. */
    fun code(): String

    fun key(): DeviceKey
}

/** [PairingSecrets] from a [SecureRandom]: 64-bit pairing ids, uniform six-digit codes, 256-bit keys. */
class SecureSecrets(private val random: SecureRandom) : PairingSecrets {
    override fun pairingId(): PairingId = PairingId(random.nextLong().toULong().toString(16).padStart(16, '0'))

    override fun code(): String = random.nextInt(CODES).toString().padStart(CODE_DIGITS, '0')

    override fun key(): DeviceKey = DeviceKey(Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(KEY_BYTES).also(random::nextBytes)))

    private companion object {
        const val CODE_DIGITS = 6
        const val CODES = 1_000_000
        const val KEY_BYTES = 32
    }
}

/** What [Pairing.open] gave a device. */
sealed interface Opened {
    /** A pairing: [code] to show on the device, good until [expiresAt]. */
    data class Ticket(val id: PairingId, val code: String, val expiresAt: Instant) : Opened {
        override fun toString(): String = "Ticket(id=$id, code=…, expiresAt=$expiresAt)"
    }

    /** No pairing: the device may ask again later. [wire] is what the device is told. */
    sealed interface Refused : Opened {
        val wire: PairingEnd
    }

    /** [Pairing.MAX_PENDING] pairings are already waiting. */
    data object Busy : Refused {
        override val wire = PairingEnd.Busy
    }

    /** The [PairingBudget] is spent: as many pairings opened in its window as it allows. */
    data object OverBudget : Refused {
        override val wire = PairingEnd.Busy
    }

    /** A pairing from this address is already waiting. */
    data object AlreadyPairing : Refused {
        override val wire = PairingEnd.TooSoon
    }

    /** This address opened a pairing less than [Pairing.COOLDOWN] ago. */
    data object TooSoon : Refused {
        override val wire = PairingEnd.TooSoon
    }
}

/**
 * At most [requests] pairings open in any [per], from every limited address together. Each one
 * counts when it opens, whether or not its request reaches the board, so it bounds the join
 * requests written to the board in that window.
 */
data class PairingBudget(val requests: Int, val per: Duration = 1.hours) {
    init {
        require(requests >= 1) { "A pairing budget allows at least one request: $requests" }
        require(per.isPositive()) { "A pairing budget's window is positive: $per" }
    }

    companion object {
        /** Join requests an hour, by default. */
        const val DEFAULT_PER_HOUR: Int = 30
        val DEFAULT: PairingBudget = PairingBudget(DEFAULT_PER_HOUR)
    }
}

/** What one typed entry did to a pairing. */
sealed interface Attempt {
    /** The code matched, typed by a person: [key] admits the device. The pairing is over. */
    data class Admitted(val key: DeviceKey, val by: PeerIdentity) : Attempt

    /** A wrong code. After [triesLeft] more, the pairing is void. */
    data class Wrong(val triesLeft: Int) : Attempt

    /** The last allowed wrong code. The pairing is over. */
    data object Voided : Attempt

    /** The code's time ran out before this entry. The pairing is over. */
    data object Expired : Attempt

    /** [actor] isn't a person, so its entry counts for nothing: no try spent, no code compared. */
    data class NotHuman(val actor: PeerIdentity) : Attempt

    /** No pending pairing has that id: it was admitted, voided, expired or ended, or never was. */
    data object Unknown : Attempt
}

private val logger = KotlinLogging.logger {}

/**
 * Bluetooth-style passkey pairing (spec §5.7): the state machine, with no I/O.
 *
 * A keyless device [open]s a pairing and shows its code. A participant types the code on the
 * board, and each entry is an [attempt]. A person's matching entry, within [ttl] of the open and
 * before [maxWrong] wrong ones, mints a [DeviceKey], and [admits] holds it from then on: [keys]
 * keeps it, and the join link's, across restarts.
 *
 * - **Only a person admits** (Iain: humans only, for now). An agent's entry is refused before its
 *   code is compared, so it neither spends a try nor learns whether it matched. The kind is the
 *   one the record declares, never attested, so this stops a well-behaved agent only (§5.7, bee#64).
 * - **Codes and keys are compared in constant time**, so a timing side channel can't walk them.
 * - **One address can't crowd out the others.** An address has at most one pairing waiting, and
 *   opens at most one per [cooldown]; past that, only [MAX_PENDING] wait at once. An address is
 *   not an identity, though: a device that answers on many addresses gets a turn on each.
 * - **The total is bounded.** All limited addresses together open at most [budget]'s worth of
 *   pairings in its window, so however many addresses a device has, it writes at most that many
 *   join requests to the board in that time. No one address may spend more than a fifth of it
 *   (at least one), so a device can't hold off everyone else's pairing; the join link still
 *   admits. The host logs a warning when the budget runs out.
 * - A [Caller.local] caller is never limited: it reaches the board without a key, so it has
 *   nothing to pair for. One a proxy forwards is limited by the address the proxy names, whatever
 *   that address is ([ForwardedPeer]).
 *
 * Every method is synchronous and thread-safe; [clock], [secrets] and [keys] are its only inputs.
 */
class Pairing(
    private val clock: () -> Instant,
    private val secrets: PairingSecrets,
    private val keys: HostKeys,
    private val ttl: Duration = TTL,
    private val maxWrong: Int = MAX_WRONG,
    private val cooldown: Duration = COOLDOWN,
    private val budget: PairingBudget = PairingBudget.DEFAULT,
) {
    private class Pending(val from: String, val code: String, val expiresAt: Instant, val wrong: Int)

    private val lock = Any()

    /**
     * Every pairing opened and not yet ended, expired ones included. Only [attempt] and [end]
     * remove one, so a pairing still waiting on the board always has its entry until its owner
     * ends it.
     */
    private val pending = mutableMapOf<PairingId, Pending>()

    /** When each limited address last opened a pairing, within the last [cooldown]. */
    private val lastOpened = mutableMapOf<String, Instant>()

    /** When each limited pairing within the last [PairingBudget.per] opened, oldest first. */
    private val budgetSpent = ArrayDeque<Pair<Instant, String>>()

    /** Whether this window's running out of budget has been logged. */
    private var exhaustionLogged = false

    /** Opens a pairing for a keyless device, [caller], or says why not ([Opened.Refused]). */
    fun open(caller: Caller): Opened = synchronized(lock) {
        val from = caller.address
        val now = clock()
        lastOpened.values.removeIf { now >= it + cooldown }
        while (budgetSpent.firstOrNull()?.let { now >= it.first + budget.per } == true) budgetSpent.removeFirst()
        // An expired pairing holds no place, though it stays pending until its timer ends it.
        val live = pending.values.filter { now < it.expiresAt }
        if (budgetSpent.isEmpty()) exhaustionLogged = false
        val limited = !caller.local
        val overBudget = limited &&
            (budgetSpent.size >= budget.requests || budgetSpent.count { it.second == from } >= max(1, budget.requests / 5))
        if (overBudget && !exhaustionLogged) {
            exhaustionLogged = true
            logger.warn { "Pairing budget reached (${budgetSpent.size} of ${budget.requests} an hour); refusing $from" }
        }
        when {
            limited && live.any { it.from == from } -> Opened.AlreadyPairing
            limited && from in lastOpened -> Opened.TooSoon
            overBudget -> Opened.OverBudget
            live.size >= MAX_PENDING -> Opened.Busy
            else -> {
                val id = secrets.pairingId()
                check(id !in pending) { "pairing id $id minted twice" }
                val ticket = Opened.Ticket(id, secrets.code(), now + ttl)
                pending[id] = Pending(from, ticket.code, ticket.expiresAt, wrong = 0)
                if (limited) {
                    lastOpened[from] = now
                    budgetSpent.addLast(now to from)
                }
                ticket
            }
        }
    }

    /**
     * [actor] typed [code] for pairing [id]. An admitted key admits by the time this returns. A code
     * pasted from chat often carries spaces, a line break or a hyphen, so those are dropped before
     * it is compared ([entered]); anything else in it still makes it wrong (bee#212).
     */
    fun attempt(id: PairingId, actor: PeerIdentity, code: String): Attempt {
        val attempt = decide(id, actor, code)
        // Outside the lock: saving the key is file I/O, and no other pairing waits on it.
        if (attempt is Attempt.Admitted) keys.add(attempt.key)
        return attempt
    }

    private fun decide(id: PairingId, actor: PeerIdentity, code: String): Attempt = synchronized(lock) {
        val live = pending[id] ?: return Attempt.Unknown
        when {
            clock() >= live.expiresAt -> Attempt.Expired.also { pending.remove(id) }
            actor.kind != PeerKind.Human -> Attempt.NotHuman(actor)
            constantTimeEquals(entered(code), live.code) -> {
                pending.remove(id)
                Attempt.Admitted(secrets.key(), actor)
            }
            live.wrong + 1 >= maxWrong -> Attempt.Voided.also { pending.remove(id) }
            else -> {
                pending[id] = Pending(live.from, live.code, live.expiresAt, live.wrong + 1)
                Attempt.Wrong(triesLeft = maxWrong - live.wrong - 1)
            }
        }
    }

    /** Ends pairing [id] with no key: its device left, or its time ran out. False if it wasn't pending. */
    fun end(id: PairingId): Boolean = synchronized(lock) { pending.remove(id) != null }

    fun isPending(id: PairingId): Boolean = synchronized(lock) { id in pending }

    /** The key with no pairing, for the join link `--lan` prints. Anyone holding the link is admitted. */
    val joinKey: DeviceKey get() = keys.join

    /** Whether [presented] is a key this host minted and still holds, in constant time ([HostKeys.admits]). */
    fun admits(presented: String?): Boolean = keys.admits(presented)

    companion object {
        /** How long a code works (§5.7). */
        val TTL: Duration = 2.minutes

        /** The wrong entry that voids a pairing (§5.7). */
        const val MAX_WRONG: Int = 3

        /**
         * Pairings waiting at once, from every address together. It caps concurrent requests on
         * the board, not how many open over time: that is [COOLDOWN], [DWELL] and [PairingBudget]'s job.
         */
        const val MAX_PENDING: Int = 3

        /** How long after one pairing an address waits before it may open another. */
        val COOLDOWN: Duration = 10.seconds

        /**
         * How long a pairing waits after it opens before its request goes on the board. A device
         * that opens a pairing and never polls still gets its request posted at the dwell, and it
         * stays up until [PairingWire.IDLE] runs out ([PairingPolls]); holding it back until the
         * device's first poll is bee#211's second item.
         */
        val DWELL: Duration = 3.seconds

        /** [typed] without the spaces, line breaks and hyphens a paste brings along. */
        private fun entered(typed: String): String = typed.filterNot { it.isWhitespace() || it == '-' }

        private fun constantTimeEquals(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.encodeToByteArray(), b.encodeToByteArray())
    }
}
