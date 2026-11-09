package us.tractat.bee.host

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import us.tractat.bee.core.PairingMessage
import us.tractat.bee.core.PairingPoll
import us.tractat.bee.core.PairingWire
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Pairing by poll (bee#209): the same pairing [desk] serves on the event stream, over plain
 * requests and complete responses, for a device behind a proxy that holds a stream back until it
 * ends. [open] starts a pairing and answers its first message with a secret token; [poll] with
 * that token answers the newest message, until the pairing's device stops asking.
 *
 * **Not asking is hanging up.** A device that no poll has come from for [idle] has left, as a
 * dropped stream has: its pairing is cancelled, which ends it and withdraws a posted request
 * ([PairingDesk.serve]), and its token stops answering. A pairing that ended keeps its last message
 * until then, so a device whose poll was lost still gets its key on the next one.
 *
 * Unlike the stream, a device that opens and never asks again leaves its request up from the dwell
 * until [idle] runs out, since [idle] outlasts [Pairing.DWELL] to ride out a slow or lost poll. The
 * cooldown and the pairing budget bound those as they bound every pairing.
 *
 * **The token is the capability.** The pairing id is on the board, so it fetches nothing here: only
 * the token [open] gave the device does. Sessions are found by the token's SHA-256, so a lookup's
 * timing says something about a digest, never about the token.
 *
 * @param scope where each pairing runs between polls; the host's own, cancelled when it stops
 */
class PairingPolls(
    private val desk: PairingDesk,
    private val scope: CoroutineScope,
    private val random: SecureRandom,
    private val idle: Duration = IDLE,
) {
    private class Session(@Volatile var seen: Instant) {
        @Volatile var latest: PairingMessage? = null
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    /** The desk's clock, so a pairing's polls and its expiry keep one time. */
    private val clock: () -> Instant = desk.clock

    /** Opens a pairing for [device], and answers what the desk told it first, with the token to poll by. */
    suspend fun open(device: Device): PairingPoll {
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(TOKEN_BYTES).also(random::nextBytes))
        val digest = digest(token)
        val session = Session(seen = clock())
        val first = CompletableDeferred<PairingMessage>()
        sessions[digest] = session
        scope.launch {
            try {
                coroutineScope {
                    val serving = launch {
                        desk.serve(device) {
                            session.latest = it
                            first.complete(it)
                        }
                    }
                    serving.invokeOnCompletion { first.completeExceptionally(IllegalStateException("The pairing ended before its first message.", it)) }
                    awaitSilence(session)
                    serving.cancel()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logger.warn(failure) { "A pairing by poll failed; its device is told the host lost it." }
            } finally {
                sessions.remove(digest)
            }
        }
        return PairingPoll(token, first.await())
    }

    /** The newest message for the pairing [token] opened, or null if the host holds none for it. Counts as the device still being here. */
    fun poll(token: String): PairingMessage? = sessions[digest(token)]?.let { session ->
        session.seen = clock()
        session.latest
    }

    /** Returns once [session]'s device has gone [idle] without a poll. */
    private suspend fun awaitSilence(session: Session) {
        while (true) {
            val left = session.seen + idle - clock()
            if (!left.isPositive()) return
            delay(left)
        }
    }

    companion object {
        /** How long a device may go without polling before its pairing ends, as a hang-up would end it. */
        val IDLE: Duration = PairingWire.POLL_EVERY * 5

        private const val TOKEN_BYTES = 32

        private fun digest(token: String): String =
            Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(token.encodeToByteArray()))
    }
}
