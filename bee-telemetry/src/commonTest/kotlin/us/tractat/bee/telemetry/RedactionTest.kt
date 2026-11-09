package us.tractat.bee.telemetry

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RedactionTest {
    private val key = "q1w2e3r4t5y6u7i8o9p0a1s2d3f4g5h6j7k8l9z0x1c" // 43 base64url characters, a device key's shape

    @Test
    fun scrubsAKeyInAJoinLinkAndAnySocketUrl() {
        val join = Redaction.scrub("Join link, no code: http://laptop.local:8233/?k=$key")
        val socket = Redaction.scrub("dial ws://10.0.0.2:8233/ws/board?board=b-1&k=short-key failed")
        val encoded = Redaction.scrub("GET /mcp%3Fk%3Dshort-key%26name%3Dada")
        assertAll(
            { assertEquals("Join link, no code: http://laptop.local:8233/?k=<redacted>", join) },
            { assertEquals("dial ws://10.0.0.2:8233/ws/board?board=b-1&k=<redacted> failed", socket) },
            { assertFalse("short-key" in encoded, encoded) },
        )
    }

    @Test
    fun scrubsAKeyShapedRunWhereverItIsWritten() {
        val bare = Redaction.scrub("presented $key, refused")
        assertEquals("presented <redacted>, refused", bare)
    }

    @Test
    fun leavesIdsAndOrdinaryTextAlone() {
        val line = "board b-7c2e restored as host-0b1f2a3c-1d2e-4f50-8a9b-0c1d2e3f4a5b: 3 surfaces, 12 records"
        assertEquals(line, Redaction.scrub(line))
    }
}
