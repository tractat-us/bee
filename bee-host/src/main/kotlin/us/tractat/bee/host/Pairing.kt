package us.tractat.bee.host

import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.PeerKind
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
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

    /** [Pairing.MAX_PENDING] pairings are already waiting. */
    data object Busy : Opened
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

/**
 * Bluetooth-style passkey pairing (spec §5.7): the state machine, with no I/O.
 *
 * A keyless device [open]s a pairing and shows its code. A participant types the code on the
 * board, and each entry is an [attempt]. A person's matching entry, within [ttl] of the open and
 * before [maxWrong] wrong ones, mints a [DeviceKey], and [admits] holds it from then on.
 *
 * - **Only a person admits** (Iain: humans only, for now). An agent's entry is refused before its
 *   code is compared, so it neither spends a try nor learns whether it matched.
 * - **Codes and keys are compared in constant time**, so a timing side channel can't walk them.
 * - **Keys live in memory only.** A new host process re-pairs every device.
 *
 * Every method is synchronous and thread-safe; [clock] and [secrets] are its only inputs.
 */
class Pairing(
    private val clock: () -> Instant,
    private val secrets: PairingSecrets,
    private val ttl: Duration = TTL,
    private val maxWrong: Int = MAX_WRONG,
) {
    private class Pending(val code: String, val expiresAt: Instant, val wrong: Int)

    private val lock = Any()
    private val pending = mutableMapOf<PairingId, Pending>()
    private val keys = mutableListOf<DeviceKey>()

    /** Opens a pairing for a keyless device, or [Opened.Busy] if [MAX_PENDING] are already live. */
    fun open(): Opened = synchronized(lock) {
        val now = clock()
        if (pending.values.count { now < it.expiresAt } >= MAX_PENDING) return Opened.Busy
        val id = secrets.pairingId()
        check(id !in pending) { "pairing id $id minted twice" }
        val ticket = Opened.Ticket(id, secrets.code(), now + ttl)
        pending[id] = Pending(ticket.code, ticket.expiresAt, wrong = 0)
        ticket
    }

    /** [actor] typed [code] for pairing [id]. */
    fun attempt(id: PairingId, actor: PeerIdentity, code: String): Attempt = synchronized(lock) {
        val live = pending[id] ?: return Attempt.Unknown
        when {
            clock() >= live.expiresAt -> Attempt.Expired.also { pending.remove(id) }
            actor.kind != PeerKind.Human -> Attempt.NotHuman(actor)
            constantTimeEquals(code, live.code) -> {
                pending.remove(id)
                Attempt.Admitted(secrets.key().also { keys += it }, actor)
            }
            live.wrong + 1 >= maxWrong -> Attempt.Voided.also { pending.remove(id) }
            else -> {
                pending[id] = Pending(live.code, live.expiresAt, live.wrong + 1)
                Attempt.Wrong(triesLeft = maxWrong - live.wrong - 1)
            }
        }
    }

    /** Ends pairing [id] with no key: its device left, or its time ran out. False if it wasn't pending. */
    fun end(id: PairingId): Boolean = synchronized(lock) { pending.remove(id) != null }

    fun isPending(id: PairingId): Boolean = synchronized(lock) { id in pending }

    /** Mints a key with no pairing, for the join link `--lan` prints. Anyone holding the link is admitted. */
    fun mintJoinKey(): DeviceKey = synchronized(lock) { secrets.key().also { keys += it } }

    /**
     * Whether [presented] is a key this host minted. Every key is compared, in constant time, so
     * the answer takes as long whichever key matches, or none.
     */
    fun admits(presented: String?): Boolean {
        if (presented.isNullOrEmpty()) return false
        val held = synchronized(lock) { keys.toList() }
        return held.fold(false) { matched, key -> constantTimeEquals(presented, key.value) or matched }
    }

    companion object {
        /** How long a code works (§5.7). */
        val TTL: Duration = 2.minutes

        /** The wrong entry that voids a pairing (§5.7). */
        const val MAX_WRONG: Int = 3

        /** Pairings waiting at once. Each puts a request on the board, so a LAN device can't flood it. */
        const val MAX_PENDING: Int = 3

        private fun constantTimeEquals(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.encodeToByteArray(), b.encodeToByteArray())
    }
}
