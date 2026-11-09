package us.tractat.bee.web

import us.tractat.bee.core.PeerIdentity
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The bar's status: a short line a person reads, the failure's detail kept for its tooltip. */
class StatusLineTest {
    private val alex = PeerIdentity.human("alex")

    /** What a browser's socket failure looks like: a serialized event, naming the socket's URL. */
    private val socketFailure = Exception("""{"target":{"url":"ws://192.168.1.20:8233/ws/board?k=s3cr3t&name=alex","readyState":3},"isTrusted":true}""")

    @Test
    fun aSocketFailureReadsAsReconnectingNotAsAnEvent() {
        val line = StatusLine.of(online = false, actor = alex, failure = socketFailure)

        assertAll(
            { assertEquals("offline — reconnecting…", line.text) },
            { assertFalse('{' in line.text) },
            { assertTrue("ws/board?k=…&name=alex" in line.detail, line.detail) },
            { assertFalse("s3cr3t" in line.detail, line.detail) },
        )
    }

    @Test
    fun onlineNamesWhoAndKeepsNoDetail() {
        assertEquals(StatusLine("online as human:alex", ""), StatusLine.of(online = true, actor = alex, failure = socketFailure))
    }
}
