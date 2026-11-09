package us.tractat.bee.host

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE

/**
 * A host's hold on its data dir: an exclusive OS lock on its [NAME] file, which holds the pid of
 * the host holding it. Invariant: **one host at a time opens a data dir.** Two on the same dir
 * would each save over the other's board, claims log and keys ([BeeHost.start] takes this first).
 *
 * The lock is the OS's, so it goes when the process does, however it ends; [close] lets it go
 * sooner. The file stays: deleting it would let a third host lock a new file while the second
 * still holds the old one.
 */
class DataDirLock private constructor(private val channel: FileChannel) : AutoCloseable {
    /** Lets the data dir go. Closing the channel releases its lock; closing it again does nothing. */
    override fun close() = channel.close()

    companion object {
        /** The lock file's name in the host's data dir, beside `board/`, `claims/` and `keys`. */
        const val NAME: String = "lock"

        /**
         * Locks [dataDir], creating it if need be, and writes this process's pid into its [NAME] file.
         *
         * @throws DataDirInUse if another host holds it, in this process or another
         */
        fun take(dataDir: Path): DataDirLock {
            Files.createDirectories(dataDir)
            // Not TRUNCATE_EXISTING: a host that fails to take the lock must leave the holder's pid.
            val channel = FileChannel.open(dataDir.resolve(NAME), CREATE, READ, WRITE)
            try {
                val held = try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null // This process holds it already, through another channel: a second host in one JVM.
                }
                if (held == null) throw DataDirInUse(dataDir, readPid(channel))
                channel.truncate(0)
                channel.write(ByteBuffer.wrap("${spikePid()}\n".encodeToByteArray()), 0)
                channel.force(true)
                return DataDirLock(channel)
            } catch (failure: Throwable) {
                channel.close()
                throw failure
            }
        }

        private fun readPid(channel: FileChannel): Long? {
            val buffer = ByteBuffer.allocate(PID_BYTES)
            channel.read(buffer, 0)
            return buffer.array().decodeToString(endIndex = buffer.position()).trim().toLongOrNull()
        }

        private const val PID_BYTES = 32
    }
}

/** Another bee host holds [dataDir] ([DataDirLock]): [pid] when its lock file names one. */
class DataDirInUse(val dataDir: Path, val pid: Long?) : IllegalStateException(
    "Another bee host${pid?.let { " (pid $it)" }.orEmpty()} is using the data dir $dataDir. " +
        "Stop it, or start this one with its own --data-dir.",
)

// SPIKE (docs/spikes/2026-11-bee-app.md): Android has no java.lang.ProcessHandle.
internal fun spikePid(): Long = try { ProcessHandle.current().pid() } catch (_: LinkageError) { -1 }
