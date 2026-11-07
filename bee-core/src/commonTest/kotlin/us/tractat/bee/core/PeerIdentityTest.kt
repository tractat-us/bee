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

    @Test
    fun parseRefusesWhatItCannotRead() {
        assertAll(
            { assertFailsWith<IllegalArgumentException> { PeerIdentity.parse("robot:x") } },
            { assertFailsWith<IllegalArgumentException> { PeerIdentity.parse("agent:bee") } },
            { assertFailsWith<IllegalArgumentException> { PeerIdentity.parse("human:") } },
        )
    }
}
