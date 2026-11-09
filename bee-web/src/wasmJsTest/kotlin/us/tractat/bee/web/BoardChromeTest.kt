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

    /** The page's CSS mutes a card's actions under `data-unheard`; the line says why. */
    @Test
    fun aCardWhoseOpenerLeftIsMarkedUnheardAndSaysAnswersWaitForAnotherSession() {
        val gone = PeerIdentity.agent("iain-claude", Random(3))
        BoardChrome(root, strip).show(departed(gone, DepartReason.Left))

        assertAll(
            { assertEquals(true, root.query("article.bee-card").hasAttribute("data-unheard")) },
            { assertEquals("iain-claude left — answers here wait for another session", root.text(".bee-card-unheard")) },
        )
    }

    /** The control: a `/clear`ed session still handles its surfaces, so its card isn't muted. */
    @Test
    fun aCardWhoseOpenerClearedIsNotMarkedUnheard() {
        val gone = PeerIdentity.agent("iain-claude", Random(4))
        BoardChrome(root, strip).show(departed(gone, DepartReason.Cleared))

        assertAll(
            { assertEquals("cleared", root.text(".bee-card-gone")) },
            { assertEquals(false, root.query("article.bee-card").hasAttribute("data-unheard")) },
            { assertEquals(null, root.querySelector(".bee-card-unheard")) },
        )
    }

    @Test
    fun anAgentOpenersCardSaysWhetherItIsListeningOrWorking() {
        val agent = PeerIdentity.agent("iain-claude", Random(5))
        val chrome = BoardChrome(root, strip)
        chrome.show(opened(agent, OpenerState.Listening("drawing the board")))
        val listening = root.query(".bee-card-state").let { it.textContent to it.getAttribute("data-state") }
        chrome.show(opened(agent, OpenerState.Working("drawing the board")))
        val working = root.query(".bee-card-state").let { it.textContent to it.getAttribute("data-state") }

        assertAll(
            { assertEquals("listening" to "listening", listening) },
            { assertEquals("working" to "working", working) },
            { assertEquals("drawing the board", root.text(".bee-card-working")) },
            { assertEquals(false, root.query("article.bee-card").hasAttribute("data-unheard")) },
        )
    }

    /** A card that goes from unheard to heard (a fresh view of the board) loses its mark. */
    @Test
    fun aCardThatIsHeardAgainLosesItsUnheardMark() {
        val agent = PeerIdentity.agent("iain-claude", Random(6))
        val chrome = BoardChrome(root, strip)
        chrome.show(departed(agent, DepartReason.Crashed))
        chrome.show(opened(agent, OpenerState.Listening(null)))

        assertAll(
            { assertEquals(false, root.query("article.bee-card").hasAttribute("data-unheard")) },
            { assertEquals(null, root.querySelector(".bee-card-unheard")) },
        )
    }

    /** A board with one card [who] opened, and [who] in the strip, both departed for [reason]. */
    private fun departed(who: PeerIdentity, reason: DepartReason) = BoardView(
        cards = listOf(CardView("lunch", "Pick a lunch spot", who, OpenerState.Departed(reason), emptyList())),
        peers = listOf(PeerView(who, PeerState.Departed(reason), "drawing the board")),
    )

    /** A board with one card [who] opened, its opener [opener], and nobody in the strip. */
    private fun opened(who: PeerIdentity, opener: OpenerState) = BoardView(
        cards = listOf(CardView("lunch", "Pick a lunch spot", who, opener, emptyList())),
        peers = emptyList(),
    )

    private fun Element.peer(who: PeerIdentity): Element = query("li.bee-peer[data-peer=\"$who\"]")

    private fun Element.query(selector: String): Element = checkNotNull(querySelector(selector)) { "no $selector in $outerHTML" }

    private fun Element.text(selector: String): String? = query(selector).textContent
}
