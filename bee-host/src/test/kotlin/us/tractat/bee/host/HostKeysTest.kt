package us.tractat.bee.host

import us.tractat.kuilt.test.assertAll
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The host's keys on disk (bee#106), each boot a [HostKeys.boot] on the same [KeyFile]: what a
 * host restart on the same data dir does to them. The socket-level restart, a bridge on the join
 * link redialling, is [BridgeJoinLinkTest]'s.
 */
class HostKeysTest {
    private val dir = Files.createTempDirectory("w106-host-keys-")
    private val file = dir.resolve(KeyFile.NAME)
    private val secrets = SecureSecrets(SecureRandom())

    @AfterTest
    fun clean() {
        dir.toFile().deleteRecursively()
    }

    private fun boot(reset: KeyReset = KeyReset.Keep) = HostKeys.boot(KeyFile(file), secrets, reset)

    @Test
    fun aRestartOnTheSameDataDirStillAdmitsThePairedKeyAndTheJoinKey() {
        val first = boot()
        val paired = secrets.key().also(first::add)
        val second = boot()
        assertAll(
            { assertEquals(first.join, second.join, "the same join link") },
            { assertTrue(second.admits(first.join.value), "the join key") },
            { assertTrue(second.admits(paired.value), "the paired device's key") },
            { assertFalse(second.admits(secrets.key().value), "rig: a key never added is refused") },
        )
    }

    @Test
    fun newLinkStopsTheOldJoinKeyAdmittingAndKeepsPairedDevices() {
        val first = boot()
        val paired = secrets.key().also(first::add)
        val rotated = boot(KeyReset.NewLink)
        assertAll(
            { assertNotEquals(first.join, rotated.join) },
            { assertFalse(rotated.admits(first.join.value), "the old link") },
            { assertTrue(rotated.admits(rotated.join.value), "the new link") },
            { assertTrue(rotated.admits(paired.value), "a paired device keeps its key") },
            { assertFalse(boot().admits(first.join.value), "the rotation is saved: the next plain boot refuses the old link too") },
        )
    }

    @Test
    fun forgetDevicesStopsPairedKeysAndTheOldJoinKeyAdmitting() {
        val first = boot()
        val paired = secrets.key().also(first::add)
        val forgot = boot(KeyReset.ForgetDevices)
        assertAll(
            { assertFalse(forgot.admits(paired.value), "the paired device") },
            { assertFalse(forgot.admits(first.join.value), "the old link, which a bridge on another machine holds") },
            { assertTrue(forgot.admits(forgot.join.value), "the new link") },
            { assertFalse(boot().admits(paired.value), "the forgetting is saved") },
        )
    }

    @Test
    fun theKeyFileHoldsNoRawDeviceKey() {
        val keys = boot()
        val paired = secrets.key().also(keys::add)
        val text = Files.readString(file)
        assertAll(
            { assertFalse(paired.value in text, "the paired key, raw") },
            { assertTrue(KeyHash.of(paired).value in text, "rig: its hash is what the file holds") },
            { assertTrue(keys.join.value in text, "rig: the join key is kept raw, to print the link") },
        )
    }

    @Test
    fun theKeyFileIsReadableOnlyByItsOwnerAndNoTemporaryFileStays() {
        val keys = boot()
        secrets.key().also(keys::add)
        assertAll(
            { assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file))) },
            { assertEquals(listOf(KeyFile.NAME), Files.list(dir).use { files -> files.map { it.fileName.toString() }.toList() }) },
        )
    }

    /** A key file that doesn't parse stops the boot, rather than start over and drop every key. */
    @Test
    fun aKeyFileThatDoesntReadStopsTheBoot() {
        Files.writeString(file, "join one\njoin two\n")
        val stopped = assertFailsWith<IllegalStateException> { boot() }
        assertTrue(file.toString() in stopped.message.orEmpty(), "the message names the file: ${stopped.message}")
    }
}
