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

/** [KeptIdentities]: who gets a kept identity back after a host restart (bee#126), and who never does. */
class KeptIdentitiesTest {
    private val dir = Files.createTempDirectory("bee-kept-")
    private val file = KeptSessionFile(dir.resolve(KeptSessionFile.NAME))
    private val random = Random(126)
    private val andrewKey = Caller.of("andrews-key")

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun aSessionLiveWhenItsHostStoppedIsOfferedOnceToTheSameNameAndKey() {
        val andrew = session("andrew-claude", andrewKey)
        lastHostHad(andrew)
        val next = KeptIdentities(file) { emptySet() }
        val strangerKey = next.pickUp("andrew-claude", Caller.of("another-key"))
        val noKey = next.pickUp("andrew-claude", Caller.Loopback)
        val otherName = next.pickUp("bea-claude", andrewKey)
        val back = next.pickUp("andrew-claude", andrewKey)
        val again = next.pickUp("andrew-claude", andrewKey)
        assertAll(
            { assertNull(strangerKey, "another key at the same name is someone else") },
            { assertNull(noKey, "no key is not andrew's key") },
            { assertNull(otherName, "another name with andrew's key is another session") },
            { assertEquals(andrew, back, "andrew's name and key get andrew's identities back") },
            { assertNull(again, "once: a second session never takes the one already back") },
        )
    }

    @Test
    fun twoKeptSessionsUnderOneNameAndKeyAreNobodys() {
        lastHostHad(session("andrew-claude", andrewKey), session("andrew-claude", andrewKey))
        val next = KeptIdentities(file) { emptySet() }
        assertNull(next.pickUp("andrew-claude", andrewKey), "can't tell which is whose, so neither is offered")
    }

    @Test
    fun anIdentityTheBoardRetiredIsNeverOffered() {
        val andrew = session("andrew-claude", andrewKey)
        lastHostHad(andrew)
        val next = KeptIdentities(file) { setOf(andrew.current) }
        assertNull(next.pickUp("andrew-claude", andrewKey), "departed is permanent: its cards would read as unheard anyway")
    }

    @Test
    fun aPickUpWhoseSessionNeverStartedWaitsAgain() {
        val andrew = session("andrew-claude", andrewKey)
        lastHostHad(andrew)
        val next = KeptIdentities(file) { emptySet() }
        val first = requireNotNull(next.pickUp("andrew-claude", andrewKey)) { "rig: andrew is kept" }
        next.putBack(first)
        assertEquals(andrew, next.pickUp("andrew-claude", andrewKey), "Claude Code's refused first initialize doesn't use it up")
    }

    @Test
    fun aSessionIsKeptWhileLiveAndForgottenOnceItDeparts() {
        val kept = KeptIdentities(file) { emptySet() }
        val owner = Any()
        val andrew = session("andrew-claude", andrewKey)
        kept.live(owner, andrew)
        val whileLive = file.load()
        kept.drop(owner)
        val afterDepart = file.load()
        assertAll(
            { assertEquals(listOf(andrew), whileLive, "on disk while live, so a host that dies keeps it too") },
            { assertTrue(afterDepart.isEmpty(), "a session that left is gone for good") },
        )
    }

    @Test
    fun theFileKeepsEveryIdentityASessionHeldAndNoKey() {
        val first = PeerIdentity.agent("andrew-claude", random)
        val current = PeerIdentity.agent("andrew-claude", random)
        val andrew = KeptSession("andrew-claude", andrewKey, current, setOf(first, current))
        file.save(listOf(andrew))
        val text = Files.readString(dir.resolve(KeptSessionFile.NAME))
        assertAll(
            { assertEquals(listOf(andrew), file.load(), "what was saved reads back, held identities and all") },
            { assertFalse("andrews-key" in text, "the key itself is never written") },
        )
    }

    @Test
    fun aFileThatDoesNotReadKeepsNothing() {
        Files.writeString(dir.resolve(KeptSessionFile.NAME), "not json")
        assertTrue(file.load().isEmpty(), "sessions come back new, as before bee#126")
    }

    private fun session(name: String, caller: Caller): KeptSession {
        val identity = PeerIdentity.agent(name, random)
        return KeptSession(name, caller, identity, setOf(identity))
    }

    /** The store as a host leaves it that had [sessions] live when it stopped. */
    private fun lastHostHad(vararg sessions: KeptSession) {
        val last = KeptIdentities(file) { emptySet() }
        sessions.forEach { last.live(Any(), it) }
    }
}
