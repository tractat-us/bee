package us.tractat.bee.mcp

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import us.tractat.bee.core.PeerIdentity
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.Base64
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * One MCP-over-HTTP session's identities, as its bridge holds them: [current], and every identity
 * it [held], whose surfaces are still its own. [name] and [caller] are what its client presents
 * on every call: the session name `?name=` gives, and whose key it came with ([Caller]).
 */
data class KeptSession(val name: String, val caller: Caller, val current: PeerIdentity, val held: Set<PeerIdentity>) {
    init {
        require(current in held) { "A session's current identity is among those it holds." }
    }
}

/**
 * Whose key an MCP call came with: the SHA-256 of its `?k=`, base64url, or [Loopback] for none.
 * The host keeps this, never the key.
 */
@JvmInline
value class Caller private constructor(val value: String) {
    companion object {
        val Loopback: Caller = Caller("")

        fun of(key: String?): Caller =
            if (key.isNullOrEmpty()) Loopback else Caller(Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key.encodeToByteArray())))

        internal fun read(value: String): Caller = Caller(value)
    }
}

/** Which of the host's keys an MCP call presented, as the host tells them apart (bee#126). */
enum class PresentedKey {
    /** None: the call came on the host's own machine, which needs no key. */
    None,

    /** The join link's key, which everyone holding the link shares. */
    Join,

    /** Any other key: off the laptop, one paired device's own. */
    Device,
}

/**
 * A [session] as a host keeps it: waiting for its client since [waitingSince], or live when the
 * host saved it (null), so its wait starts at the next boot.
 */
data class StoredSession(val session: KeptSession, val waitingSince: Instant?)

/** Where a host keeps its [KeptSession]s between boots. Production is [KeptSessionFile]. */
interface KeptSessionStore {
    /** The sessions last saved, or none if none ever were. */
    fun load(): List<StoredSession>

    /** Replaces what [load] returns with [sessions], whole. */
    fun save(sessions: List<StoredSession>)
}

/**
 * The identities of the MCP-over-HTTP sessions alive when the last host on this data dir stopped,
 * or died (bee#126). Invariant: **each kept identity is acted as by at most one session, and a
 * session takes one only when its client presents the same name and key, and no other session
 * the last host had could have presented them.**
 *
 * A session's identities are kept from the moment it is on the board ([live]) until it departs
 * ([drop]): a host that stops, or dies, departs none of them, as a laptop's bridge isn't departed
 * when its host restarts. The next host offers each to the one session that comes back with its
 * name and key ([pickUp]). One that no session picks up is the host's to retire ([unclaimed],
 * [forget]): no live peer may post for it again, so the host's own quiet rule can't see it.
 *
 * - **Ambiguous is nobody's.** Two kept sessions under one name and key can't be told apart, so
 *   neither is picked up: a session taking the other's surfaces would be worse than a new identity.
 * - **A retired identity is never offered.** One the board lists as departed ([retired]) is dropped.
 * - **A pick-up is held until the session starts.** A session that never initializes puts its
 *   pick-up back ([putBack]) unsaved, and it waits afresh from then, so its client has the whole
 *   window again. A host that dies between pick-up and start usually leaves the store as it was;
 *   if another session saved in that window, the pick-up is gone from it, and its client comes
 *   back as a new identity, as before bee#126.
 * - **A waiting identity is nobody else's to judge.** Until it is picked up or [unclaimed], the
 *   host's own departures leave it be ([waitingIdentities]): its only presence entry is the one a
 *   surviving peer pushed back, whose node and silence are the last host's.
 *
 * A save that fails is logged: the sessions still work, and come back new after a restart.
 */
class KeptIdentities(private val store: KeptSessionStore, private val retired: () -> Set<PeerIdentity>, private val clock: () -> Instant) {
    private val lock = Any()

    /**
     * Each session the last host kept that no session has picked up, and since when it has waited:
     * saved, so a host restarted inside the window doesn't restart it. One that was live when its
     * host stopped, or that a file from before the time was saved names, waits from this boot.
     */
    private val waiting: MutableMap<KeptSession, Instant>
    private val live = mutableMapOf<Any, KeptSession>()

    init {
        val now = clock()
        val loaded = store.load()
        waiting = loaded.associateTo(mutableMapOf()) { it.session to (it.waitingSince ?: now) }
        // A wait that starts now is saved now, or the next boot would start it again.
        if (loaded.any { it.waitingSince == null }) save()
    }

    /** The one session the last host kept for [name] and [caller], taken out of waiting; or null. */
    fun pickUp(name: String, caller: Caller): KeptSession? = synchronized(lock) {
        val gone = retired()
        waiting.keys.removeAll { it.current in gone }
        val matching = waiting.keys.filter { it.name == name && it.caller == caller }
        matching.singleOrNull()?.also { waiting -= it }
    }

    /** A [pickUp] whose session never started: it waits again, from now. */
    fun putBack(session: KeptSession): Unit = synchronized(lock) { waiting[session] = clock() }

    /** The current identity of each kept session no session has picked up: its client may yet return. */
    fun waitingIdentities(): Set<PeerIdentity> = synchronized(lock) { waiting.keys.map { it.current }.toSet() }

    /** The kept sessions nobody has picked up since [before]: their clients aren't coming back. */
    fun unclaimed(before: Instant): List<KeptSession> = synchronized(lock) { waiting.filterValues { it <= before }.keys.toList() }

    /** [session], [unclaimed] and now departed, is kept no more, unless a session picked it up meanwhile. */
    fun forget(session: KeptSession): Unit = synchronized(lock) {
        if (waiting.remove(session) != null) save()
    }

    /**
     * Ends the wait of every waiting session [which] picks, so the next check finds it [unclaimed]:
     * its client can't come back, as when its key no longer admits (`--forget-devices`).
     */
    fun endWaits(which: (KeptSession) -> Boolean): Unit = synchronized(lock) {
        val ended = waiting.keys.filter(which)
        if (ended.isEmpty()) return
        ended.forEach { waiting[it] = Instant.DISTANT_PAST }
        save()
    }

    /** [owner]'s session is on the board as [session]: kept from now on, replacing what it was. */
    fun live(owner: Any, session: KeptSession): Unit = synchronized(lock) {
        live[owner] = session
        save()
    }

    /** [owner]'s session departed: nothing of it is kept. */
    fun drop(owner: Any): Unit = synchronized(lock) {
        if (live.remove(owner) != null) save()
    }

    private fun save() {
        try {
            store.save(waiting.map { (session, since) -> StoredSession(session, since) } + live.values.map { StoredSession(it, waitingSince = null) })
        } catch (failure: IOException) {
            logger.error(failure) { "Couldn't save the HTTP sessions' identities; a host restart gives them new ones." }
        }
    }
}

/**
 * [KeptSession]s in one JSON file, [path]. A save writes a temporary file beside it, forces it to
 * disk, and moves it over [path] atomically. A file that doesn't read keeps nothing, and says so:
 * the sessions it held come back as new identities, which is what they did before bee#126.
 */
class KeptSessionFile(private val path: Path) : KeptSessionStore {
    override fun load(): List<StoredSession> {
        val text = try {
            Files.readString(path)
        } catch (_: NoSuchFileException) {
            return emptyList()
        } catch (failure: CharacterCodingException) {
            logger.warn(failure) { "$path doesn't read as HTTP sessions; they come back as new identities." }
            return emptyList()
        }
        return try {
            json.decodeFromString<List<Stored>>(text).map(Stored::session)
        } catch (failure: SerializationException) {
            logger.warn(failure) { "$path doesn't read as HTTP sessions; they come back as new identities." }
            emptyList()
        } catch (failure: IllegalArgumentException) {
            logger.warn(failure) { "$path doesn't read as HTTP sessions; they come back as new identities." }
            emptyList()
        }
    }

    override fun save(sessions: List<StoredSession>) {
        val dir = path.toAbsolutePath().parent
        Files.createDirectories(dir)
        val temp = Files.createTempFile(dir, ".${path.fileName}-", ".tmp")
        try {
            FileChannel.open(temp, WRITE).use { channel ->
                channel.write(ByteBuffer.wrap(json.encodeToString(sessions.map(Stored::of)).encodeToByteArray()))
                channel.force(true)
            }
            Files.move(temp, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    @Serializable
    private data class Stored(
        val name: String,
        val caller: String,
        val current: String,
        val held: List<String>,
        /** Epoch milliseconds; absent while the session was live, and in a file from before it was saved. */
        val waitingSince: Long? = null,
    ) {
        fun session() = StoredSession(
            KeptSession(name, Caller.read(caller), PeerIdentity.parse(current), held.map(PeerIdentity::parse).toSet()),
            waitingSince?.let(Instant::fromEpochMilliseconds),
        )

        companion object {
            fun of(stored: StoredSession) = with(stored.session) {
                Stored(name, caller.value, current.toString(), held.map { it.toString() }.sorted(), stored.waitingSince?.toEpochMilliseconds())
            }
        }
    }

    companion object {
        /** The file's name in the host's data dir, beside `board/`, `claims/` and `keys`. */
        const val NAME: String = "http-sessions.json"

        private val json = Json { ignoreUnknownKeys = true }
    }
}
