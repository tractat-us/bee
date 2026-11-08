package us.tractat.bee.core

import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PeerIdentityTest {
    @Test
    fun formatsAndParsesBothKinds() {
        val minted = PeerIdentity.agent("bee", Random(42))
        assertAll(
            { assertEquals("agent:bee#0a1b2c3d", PeerIdentity.parse("agent:bee#0a1b2c3d").toString()) },
            { assertEquals(PeerIdentity(PeerKind.Agent, "bee", "0a1b2c3d"), PeerIdentity.parse("agent:bee#0a1b2c3d")) },
            { assertEquals("human:iain", PeerIdentity.parse("human:iain").toString()) },
            { assertEquals(PeerIdentity.human("iain"), PeerIdentity.parse("human:iain")) },
            { assertTrue(Regex("[0-9a-f]{8}").matches(minted.instance), "instance was ${minted.instance}") },
            { assertEquals(minted, PeerIdentity.parse(minted.toString())) },
        )
    }

    /** The host is `host:<name>`, its own kind with no instance (bee#71). */
    @Test
    fun formatsAndParsesTheHost() {
        assertAll(
            { assertEquals("host:bee-host", PeerIdentity.host("bee-host").toString()) },
            { assertEquals(PeerIdentity(PeerKind.Host, "bee-host", ""), PeerIdentity.parse("host:bee-host")) },
            { assertFailsWith<IllegalArgumentException> { PeerIdentity(PeerKind.Host, "bee-host", "00000000") } },
        )
    }

    /** A snapshot from before the host had its kind names it `agent:bee-host#00000000`; it still reads as the host. */
    @Test
    fun oldEncodedHostDecodesAsTheHost() {
        assertAll(
            { assertEquals(PeerIdentity.host("bee-host"), PeerIdentity.parse("agent:bee-host#00000000")) },
            { assertEquals(PeerKind.Agent, PeerIdentity.parse("agent:bee-host#00000001").kind, "only that one identity is the old host") },
            { assertEquals(PeerKind.Agent, PeerIdentity.parse("agent:bee#00000000").kind, "and only under its name") },
        )
    }

    @Test
    fun parseRefusesWhatItCannotRead() {
        assertAll(
            { assertFailsWith<IllegalArgumentException> { PeerIdentity.parse("robot:x") } },
            { assertFailsWith<IllegalArgumentException> { PeerIdentity.parse("agent:bee") } },
            { assertFailsWith<IllegalArgumentException> { PeerIdentity.parse("human:") } },
            { assertFailsWith<IllegalArgumentException> { PeerIdentity.parse("host:") } },
        )
    }
}
