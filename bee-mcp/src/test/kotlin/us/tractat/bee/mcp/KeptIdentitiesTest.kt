package us.tractat.bee.mcp

import us.tractat.bee.core.PeerIdentity
import us.tractat.kuilt.test.assertAll
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** [KeptIdentities]: who gets a kept identity back after a host restart (bee#126), and who never does. */
class KeptIdentitiesTest {
    private val dir = Files.createTempDirectory("bee-kept-")
    private val file = KeptSessionFile(dir.resolve(KeptSessionFile.NAME))
    private val random = Random(126)
    private val alexKey = Caller.of("alexs-key")
    private var now = Instant.fromEpochSeconds(1_000)
    private val clock = { now }

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun aKeptSessionNobodyPicksUpIsUnclaimedOnceItsWindowPassesThenForgotten() {
        val alex = session("alex-claude", alexKey)
        lastHostHad(alex)
        val next = KeptIdentities(file, { emptySet() }, clock)
        val booted = now
        now += 19.minutes
        val early = next.unclaimed(now - 20.minutes)
        now += 2.minutes
        val due = next.unclaimed(now - 20.minutes)
        next.forget(alex)
        assertAll(
            { assertTrue(early.isEmpty(), "still inside its window at $now, booted $booted") },
            { assertEquals(listOf(alex), due, "its client never came back within the window") },
            { assertTrue(file.load().isEmpty(), "forgotten on disk too, so the next host doesn't wait for it again") },
            { assertNull(next.pickUp("alex-claude", alexKey), "and never offered") },
        )
    }

    /**
     * The window is the client's, not each boot's: a host restarted twice inside it still retires
     * the session at the first boot's deadline, so its claims are never held for good.
     */
    @Test
    fun restartsInsideTheWindowNeverRestartIt() {
        val alex = session("alex-claude", alexKey)
        lastHostHad(alex)
        KeptIdentities(file, { emptySet() }, clock)
        now += 10.minutes
        KeptIdentities(file, { emptySet() }, clock)
        now += 9.minutes
        val third = KeptIdentities(file, { emptySet() }, clock)
        val beforeDeadline = third.unclaimed(now - 20.minutes)
        now += 2.minutes
        assertAll(
            { assertTrue(beforeDeadline.isEmpty(), "19 minutes after the first boot, still waiting") },
            { assertEquals(listOf(alex), third.unclaimed(now - 20.minutes), "21 minutes after the first boot, three boots later, due") },
        )
    }

    /** A file from before the wait was saved: its sessions wait from this boot, and that is saved. */
    @Test
    fun aSessionSavedWithNoWaitWaitsFromThisBoot() {
        val alex = session("alex-claude", alexKey)
        file.save(listOf(StoredSession(alex, waitingSince = null)))
        val booted = now
        KeptIdentities(file, { emptySet() }, clock)
        assertEquals(listOf(StoredSession(alex, booted)), file.load(), "its wait starts now, on disk")
    }

    @Test
    fun aPickedUpSessionIsNeverUnclaimedAndAPutBackOneWaitsAfresh() {
        val alex = session("alex-claude", alexKey)
        lastHostHad(alex)
        val next = KeptIdentities(file, { emptySet() }, clock)
        val taken = requireNotNull(next.pickUp("alex-claude", alexKey)) { "rig: alex is kept" }
        now += 21.minutes
        val whileTaken = next.unclaimed(now - 20.minutes)
        next.putBack(taken)
        val justPutBack = next.unclaimed(now - 20.minutes)
        now += 21.minutes
        assertAll(
            { assertTrue(whileTaken.isEmpty(), "a session holds it: not the host's to retire") },
            { assertTrue(justPutBack.isEmpty(), "put back, its client has the whole window again") },
            { assertEquals(listOf(alex), next.unclaimed(now - 20.minutes), "until that passes too") },
        )
    }

    @Test
    fun aSessionLiveWhenItsHostStoppedIsOfferedOnceToTheSameNameAndKey() {
        val alex = session("alex-claude", alexKey)
        lastHostHad(alex)
        val next = KeptIdentities(file, { emptySet() }, clock)
        val strangerKey = next.pickUp("alex-claude", Caller.of("another-key"))
        val noKey = next.pickUp("alex-claude", Caller.Loopback)
        val otherName = next.pickUp("bea-claude", alexKey)
        val back = next.pickUp("alex-claude", alexKey)
        val again = next.pickUp("alex-claude", alexKey)
        assertAll(
            { assertNull(strangerKey, "another key at the same name is someone else") },
            { assertNull(noKey, "no key is not alex's key") },
            { assertNull(otherName, "another name with alex's key is another session") },
            { assertEquals(alex, back, "alex's name and key get alex's identities back") },
            { assertNull(again, "once: a second session never takes the one already back") },
        )
    }

    @Test
    fun twoKeptSessionsUnderOneNameAndKeyAreNobodys() {
        lastHostHad(session("alex-claude", alexKey), session("alex-claude", alexKey))
        val next = KeptIdentities(file, { emptySet() }, clock)
        assertNull(next.pickUp("alex-claude", alexKey), "can't tell which is whose, so neither is offered")
    }

    @Test
    fun anIdentityTheBoardRetiredIsNeverOffered() {
        val alex = session("alex-claude", alexKey)
        lastHostHad(alex)
        val next = KeptIdentities(file, { setOf(alex.current) }, clock)
        assertNull(next.pickUp("alex-claude", alexKey), "departed is permanent: its cards would read as unheard anyway")
    }

    @Test
    fun aPickUpWhoseSessionNeverStartedWaitsAgain() {
        val alex = session("alex-claude", alexKey)
        lastHostHad(alex)
        val next = KeptIdentities(file, { emptySet() }, clock)
        val first = requireNotNull(next.pickUp("alex-claude", alexKey)) { "rig: alex is kept" }
        next.putBack(first)
        assertEquals(alex, next.pickUp("alex-claude", alexKey), "Claude Code's refused first initialize doesn't use it up")
    }

    @Test
    fun aSessionIsKeptWhileLiveAndForgottenOnceItDeparts() {
        val kept = KeptIdentities(file, { emptySet() }, clock)
        val owner = Any()
        val alex = session("alex-claude", alexKey)
        kept.live(owner, alex)
        val whileLive = file.load().map { it.session }
        kept.drop(owner)
        val afterDepart = file.load()
        assertAll(
            { assertEquals(listOf(alex), whileLive, "on disk while live, so a host that dies keeps it too") },
            { assertTrue(afterDepart.isEmpty(), "a session that left is gone for good") },
        )
    }

    @Test
    fun theFileKeepsEveryIdentityASessionHeldAndNoKey() {
        val first = PeerIdentity.agent("alex-claude", random)
        val current = PeerIdentity.agent("alex-claude", random)
        val alex = KeptSession("alex-claude", alexKey, current, setOf(first, current))
        file.save(listOf(StoredSession(alex, Instant.fromEpochMilliseconds(42))))
        val text = Files.readString(dir.resolve(KeptSessionFile.NAME))
        assertAll(
            { assertEquals(listOf(StoredSession(alex, Instant.fromEpochMilliseconds(42))), file.load(), "what was saved reads back, held identities and wait and all") },
            { assertFalse("alexs-key" in text, "the key itself is never written") },
        )
    }

    @Test
    fun aFileThatDoesNotReadKeepsNothing() {
        Files.writeString(dir.resolve(KeptSessionFile.NAME), "not json")
        val notJson = file.load()
        Files.write(dir.resolve(KeptSessionFile.NAME), byteArrayOf(0xC3.toByte(), 0x28))
        val notUtf8 = file.load()
        assertAll(
            { assertTrue(notJson.isEmpty(), "sessions come back new, as before bee#126") },
            { assertTrue(notUtf8.isEmpty(), "nor does a file that isn't UTF-8 stop the host") },
        )
    }

    private fun session(name: String, caller: Caller): KeptSession {
        val identity = PeerIdentity.agent(name, random)
        return KeptSession(name, caller, identity, setOf(identity))
    }

    /** The store as a host leaves it that had [sessions] live when it stopped. */
    private fun lastHostHad(vararg sessions: KeptSession) {
        val last = KeptIdentities(file, { emptySet() }, clock)
        sessions.forEach { last.live(Any(), it) }
    }
}
