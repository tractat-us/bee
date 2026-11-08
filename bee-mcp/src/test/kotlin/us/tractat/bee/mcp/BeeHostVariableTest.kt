package us.tractat.bee.mcp

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BeeHostVariableTest {
    @Test
    fun unsetOrBlankIsTheDefaultHost() {
        assertAll(
            { assertEquals("ws://127.0.0.1:8233/ws/board", beeHostFrom(null).socket("/ws/board")) },
            { assertEquals("ws://127.0.0.1:8233/ws/board", beeHostFrom("  ").socket("/ws/board")) },
        )
    }

    @Test
    fun aJoinLinkIsTakenAsPasted() {
        val host = beeHostFrom("http://tractatus.local:8233/?k=$KEY")
        assertEquals("ws://tractatus.local:8233/ws/claims?k=$KEY", host.socket("/ws/claims"))
    }

    @Test
    fun anUnusableValueStopsTheBridgeNamingBeeHostAndNotTheKey() {
        val failure = assertFailsWith<IllegalArgumentException> { beeHostFrom("tractatus.local:8233/?k=$KEY") }
        val message = failure.message.orEmpty()
        assertAll(
            { assertTrue(message.startsWith("BEE_HOST: "), message) },
            { assertFalse(KEY in message, message) },
        )
    }

    private companion object {
        const val KEY = "s3cr3tJoinKey_0123456789"
    }
}
