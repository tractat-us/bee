package us.tractat.bee.web

import kotlinx.browser.document
import org.w3c.dom.Element
import us.tractat.bee.core.DepartReason
import us.tractat.bee.core.PeerIdentity
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The words and `data-reason` the chrome writes for a departed peer, in the strip and on its
 * cards. The page's CSS alarms (red, struck through) only on `crashed` and `quiet`, so a session
 * that ended normally must read `left` and carry `data-reason="left"`.
 */
class BoardChromeTest {
    private val root = document.createElement("div").also { document.body?.appendChild(it) }
    private val strip = document.createElement("ul").also { document.body?.appendChild(it) }

    @AfterTest
    fun detach() {
        root.remove()
        strip.remove()
    }

    @Test
    fun aSessionThatLeftReadsLeftNotCrashed() {
        val gone = PeerIdentity.agent("iain-agent", Random(1))
        BoardChrome(root, strip).show(departed(gone, DepartReason.Left))

        assertAll(
            { assertEquals("left", strip.peer(gone).text(".bee-peer-state")) },
            { assertEquals("left", strip.peer(gone).getAttribute("data-reason")) },
            { assertEquals("left", root.text(".bee-card-gone")) },
            { assertEquals("left", root.query(".bee-card-gone").getAttribute("data-reason")) },
        )
    }

    /** The control: a crash still reads as one, so the page still alarms on it. */
    @Test
    fun aCrashedSessionStillReadsCrashed() {
        val gone = PeerIdentity.agent("iain-agent", Random(2))
        BoardChrome(root, strip).show(departed(gone, DepartReason.Crashed))

        assertAll(
            { assertEquals("crashed", strip.peer(gone).text(".bee-peer-state")) },
            { assertEquals("crashed", strip.peer(gone).getAttribute("data-reason")) },
            { assertEquals("crashed", root.text(".bee-card-gone")) },
            { assertEquals("crashed", root.query(".bee-card-gone").getAttribute("data-reason")) },
        )
    }

    /** A board with one card [who] opened, and [who] in the strip, both departed for [reason]. */
    private fun departed(who: PeerIdentity, reason: DepartReason) = BoardView(
        cards = listOf(CardView("lunch", "Pick a lunch spot", who, OpenerState.Departed(reason), emptyList())),
        peers = listOf(PeerView(who, PeerState.Departed(reason), "drawing the board")),
    )

    private fun Element.peer(who: PeerIdentity): Element = query("li.bee-peer[data-peer=\"$who\"]")

    private fun Element.query(selector: String): Element = checkNotNull(querySelector(selector)) { "no $selector in $outerHTML" }

    private fun Element.text(selector: String): String? = query(selector).textContent
}
