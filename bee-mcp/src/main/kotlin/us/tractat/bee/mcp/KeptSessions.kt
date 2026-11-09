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

/** Where a host keeps its [KeptSession]s between boots. Production is [KeptSessionFile]. */
interface KeptSessionStore {
    /** The sessions last saved, or none if none ever were. */
    fun load(): List<KeptSession>

    /** Replaces what [load] returns with [sessions], whole. */
    fun save(sessions: List<KeptSession>)
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
 *   pick-up back ([putBack]) unsaved, so a host that dies between leaves the store as it was; and
 *   it waits afresh from then, so its client has the whole window again.
 *
 * A save that fails is logged: the sessions still work, and come back new after a restart.
 */
class KeptIdentities(private val store: KeptSessionStore, private val retired: () -> Set<PeerIdentity>, private val clock: () -> Instant) {
    private val lock = Any()

    /** Each session the last host kept that no session has picked up, and since when it has waited. */
    private val waiting: MutableMap<KeptSession, Instant> = clock().let { now -> store.load().associateWithTo(mutableMapOf()) { now } }
    private val live = mutableMapOf<Any, KeptSession>()

    /** The one session the last host kept for [name] and [caller], taken out of waiting; or null. */
    fun pickUp(name: String, caller: Caller): KeptSession? = synchronized(lock) {
        val gone = retired()
        waiting.keys.removeAll { it.current in gone }
        val matching = waiting.keys.filter { it.name == name && it.caller == caller }
        matching.singleOrNull()?.also { waiting -= it }
    }

    /** A [pickUp] whose session never started: it waits again, from now. */
    fun putBack(session: KeptSession): Unit = synchronized(lock) { waiting[session] = clock() }

    /** The kept sessions nobody has picked up since [before]: their clients aren't coming back. */
    fun unclaimed(before: Instant): List<KeptSession> = synchronized(lock) { waiting.filterValues { it <= before }.keys.toList() }

    /** [session], [unclaimed] and now departed, is kept no more, unless a session picked it up meanwhile. */
    fun forget(session: KeptSession): Unit = synchronized(lock) {
        if (waiting.remove(session) != null) save()
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
            store.save(waiting.keys + live.values)
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
    override fun load(): List<KeptSession> {
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

    override fun save(sessions: List<KeptSession>) {
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
    private data class Stored(val name: String, val caller: String, val current: String, val held: List<String>) {
        fun session() = KeptSession(name, Caller.read(caller), PeerIdentity.parse(current), held.map(PeerIdentity::parse).toSet())

        companion object {
            fun of(session: KeptSession) = Stored(session.name, session.caller.value, session.current.toString(), session.held.map { it.toString() }.sorted())
        }
    }

    companion object {
        /** The file's name in the host's data dir, beside `board/`, `claims/` and `keys`. */
        const val NAME: String = "http-sessions.json"

        private val json = Json { ignoreUnknownKeys = true }
    }
}
