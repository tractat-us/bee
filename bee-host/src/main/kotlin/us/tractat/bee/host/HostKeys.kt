package us.tractat.bee.host

import io.github.oshai.kotlinlogging.KotlinLogging
import us.tractat.bee.mcp.PresentedKey
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermission.OWNER_READ
import java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Base64

private val logger = KotlinLogging.logger {}

/**
 * A device key's SHA-256, base64url. The host keeps this, not the key: a key is 256 random bits,
 * so its hash needs no salt, and reading the key file gives no one a key that admits.
 */
@JvmInline
value class KeyHash(val value: String) {
    companion object {
        fun of(key: DeviceKey): KeyHash = of(key.value)

        internal fun of(presented: String): KeyHash =
            KeyHash(Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(presented.encodeToByteArray())))
    }
}

/**
 * What a host keeps of its keys between boots: the join link's [join] key as it is, since the host
 * prints the link on every start, and the [KeyHash] of each paired device's key.
 */
data class StoredKeys(val join: DeviceKey, val devices: Set<KeyHash>)

/** Where a host keeps its [StoredKeys]. Production is [KeyFile]. */
interface KeyStore {
    /** The keys last saved, or null if none ever were. */
    fun load(): StoredKeys?

    /** Replaces what [load] returns with [keys], whole: a crash leaves the old keys or the new, never a mix. */
    fun save(keys: StoredKeys)
}

/** What a boot does to the keys the data dir holds (`--new-link`, `--forget-devices`). */
enum class KeyReset {
    /** The default: every key the host minted still admits, so the same join link works. */
    Keep,

    /** A new join key, so the old link stops admitting. Paired devices keep their keys. */
    NewLink,

    /**
     * No key survives: every paired device's key stops admitting, and so does the join link's. A
     * device that came by the link, and every bridge on another machine, holds the join key, so
     * forgetting devices without a new link would leave those connected.
     */
    ForgetDevices,
}

/**
 * Every key that admits a device to this host: the join link's, and one per paired device
 * (spec §5.7, bee#106). Invariant: **a key admits only once its hash is saved**, so a key the host
 * handed out admits after a restart; the one exception is a save that fails, which is logged
 * ([add]). Revoking a key is explicit, a [KeyReset] at boot.
 *
 * [admits] reads a snapshot and never waits on a save. [add]s queue on their own lock, held while
 * one saves, so a slow disk delays only the next admission, never a socket's key check.
 */
class HostKeys private constructor(private val store: KeyStore, kept: StoredKeys) {
    /** The join link's key: anyone holding the link is admitted. */
    val join: DeviceKey = kept.join

    private val joinHash = KeyHash.of(join)

    @Volatile
    private var devices: Set<KeyHash> = kept.devices

    private val saving = Any()

    /**
     * Saves [key]'s hash, then admits it. If the save fails, [key] still admits until this host
     * stops, and the failure is logged: the person admitting the device is told nothing, and the
     * device would have no better way in.
     */
    fun add(key: DeviceKey): Unit = synchronized(saving) {
        val next = devices + KeyHash.of(key)
        try {
            store.save(StoredKeys(join, next))
        } catch (failure: IOException) {
            logger.error(failure) { "Couldn't save a paired device's key; it admits until this host stops, and then pairs again." }
        }
        devices = next
    }

    /**
     * Whether [presented] is one of these keys. Its hash is compared with every key's, in constant
     * time, so the answer takes as long whichever key matches, or none.
     */
    fun admits(presented: String?): Boolean {
        if (presented.isNullOrEmpty()) return false
        val hash = KeyHash.of(presented).value.encodeToByteArray()
        return (devices + joinHash).fold(false) { matched, held -> MessageDigest.isEqual(hash, held.value.encodeToByteArray()) or matched }
    }

    /**
     * Which key [presented] is: none, the join link's (compared in constant time), or another. The
     * gate has already admitted the call, so off loopback another key is a paired device's own.
     */
    fun kindOf(presented: String?): PresentedKey = when {
        presented.isNullOrEmpty() -> PresentedKey.None
        MessageDigest.isEqual(KeyHash.of(presented).value.encodeToByteArray(), joinHash.value.encodeToByteArray()) -> PresentedKey.Join
        else -> PresentedKey.Device
    }

    companion object {
        /**
         * The keys [store] holds, after [reset], saved back before any admits. A store with none
         * gets a join key from [secrets]: the first boot, or the first since the file was moved aside.
         * [KeyReset.ForgetDevices] keeps nothing, so it never reads [store]: it boots past a key
         * file that doesn't read.
         */
        fun boot(store: KeyStore, secrets: PairingSecrets, reset: KeyReset): HostKeys {
            val kept = when (reset) {
                KeyReset.Keep -> store.load()
                KeyReset.NewLink -> store.load()?.copy(join = secrets.key())
                KeyReset.ForgetDevices -> null
            } ?: StoredKeys(secrets.key(), devices = emptySet())
            store.save(kept)
            when (reset) {
                KeyReset.Keep -> {}
                KeyReset.NewLink -> logger.info { "A new join link: the old one no longer admits. Paired devices keep their keys." }
                KeyReset.ForgetDevices -> logger.info { "Forgot every device, paired or joined by the link. Each one pairs again, or takes the new link." }
            }
            return HostKeys(store, kept)
        }
    }
}

/**
 * [StoredKeys] in one file, [path], readable and writable only by its owner where the file system
 * has POSIX permissions. A save writes a temporary file beside it, forces it to disk, and moves it
 * over [path] atomically. The file is text: a comment, a `join <key>` line, and a
 * `device <hash>` line per paired device.
 */
class KeyFile(private val path: Path) : KeyStore {
    override fun load(): StoredKeys? {
        val lines = try {
            Files.readAllLines(path)
        } catch (_: NoSuchFileException) {
            return null
        } catch (failure: AccessDeniedException) {
            throw IllegalStateException(unreadable(), failure)
        } catch (failure: CharacterCodingException) {
            throw IllegalStateException(unreadable(), failure)
        }
        val fields = lines.filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split(' ') }
        val joins = fields.filter { it.first() == JOIN }
        val unread = fields.filterNot { it.size == 2 && it.first() in setOf(JOIN, DEVICE) }
        check(unread.isEmpty() && joins.size == 1, ::unreadable)
        return StoredKeys(DeviceKey(joins.single()[1]), fields.filter { it.first() == DEVICE }.map { KeyHash(it[1]) }.toSet())
    }

    override fun save(keys: StoredKeys) {
        val dir = path.toAbsolutePath().parent
        Files.createDirectories(dir)
        val text = buildString {
            appendLine("# bee-host keys: the join link's key, and a SHA-256 of each paired device's key. Owner-only.")
            appendLine("$JOIN ${keys.join.value}")
            keys.devices.forEach { appendLine("$DEVICE ${it.value}") }
        }
        val temp = Files.createTempFile(dir, ".${path.fileName}-", ".tmp", *ownerOnly(dir))
        try {
            FileChannel.open(temp, WRITE).use { channel ->
                channel.write(ByteBuffer.wrap(text.encodeToByteArray()))
                channel.force(true)
            }
            Files.move(temp, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun unreadable(): String =
        "$path doesn't read as bee-host keys. Start the host with --forget-devices, or move the file aside, to forget every key: " +
            "the host prints a new join link, and every device pairs again."

    companion object {
        /** The key file's name in the host's data dir, beside `board/` and `claims/`. */
        const val NAME: String = "keys"

        private const val JOIN = "join"
        private const val DEVICE = "device"

        private fun ownerOnly(dir: Path): Array<FileAttribute<*>> =
            if ("posix" in dir.fileSystem.supportedFileAttributeViews()) {
                arrayOf(PosixFilePermissions.asFileAttribute(setOf(OWNER_READ, OWNER_WRITE)))
            } else {
                emptyArray()
            }
    }
}
