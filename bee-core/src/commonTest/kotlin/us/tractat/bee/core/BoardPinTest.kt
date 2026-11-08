package us.tractat.bee.core

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.websocket.WebSocketAdvertisement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class BoardPinTest {
    private val first = BoardId.mint()
    private val second = BoardId.mint()

    @Test
    fun aPinToABoardNeverAsks() = runTest {
        val pin = BoardPin.to(first)
        val resolved = pin.resolve()
        assertAll(
            { assertEquals(first, pin.board.value) },
            { assertEquals(first, resolved) },
        )
    }

    /** A replica built empty belongs to the first board it learns, and to no other after. */
    @Test
    fun aLearnedPinKeepsTheFirstBoardItLearns() = runTest {
        val answers = ArrayDeque(listOf<BoardId?>(null, first, second))
        var asked = 0
        val pin = BoardPin.learnedFrom {
            asked++
            answers.removeFirst() ?: error("the host is down")
        }
        val before = pin.board.value
        assertFailsWith<IllegalStateException> { pin.resolve() }
        val afterAFailedAsk = pin.board.value
        val learned = pin.resolve()
        val later = pin.resolve()
        assertAll(
            { assertNull(before, "nothing pinned before the first dial") },
            { assertNull(afterAFailedAsk, "a failed ask pins nothing") },
            { assertEquals(first, learned) },
            { assertEquals(first, later, "the host now names another board, and the pin stays") },
            { assertEquals(2, asked, "never asked again once pinned") },
        )
    }

    @Test
    fun aPinnedLoomNamesTheBoardOnEveryDial() = runTest {
        val loom = RecordingLoom()
        val pinned = loom.pinnedTo(BoardPin.to(first))
        pinned.attempt(socket("ws://127.0.0.1:8233/ws/board"))
        pinned.attempt(socket("ws://127.0.0.1:8233/ws/board?k=key"))
        // kuilt's cluster client dials by join, a default method a delegating loom would pass on.
        assertIs<Recorded>(runCatching { pinned.join(socket("ws://127.0.0.1:8233/ws/claims").tag) }.exceptionOrNull())
        assertEquals(
            listOf(
                "ws://127.0.0.1:8233/ws/board?board=$first",
                "ws://127.0.0.1:8233/ws/board?k=key&board=$first",
                "ws://127.0.0.1:8233/ws/claims?board=$first",
            ),
            loom.dialled,
        )
    }

    @Test
    fun onlyAWebSocketRendezvousCanNameABoard() {
        assertFailsWith<IllegalArgumentException> { Rendezvous.Existing(OtherTag).naming(first) }
    }

    @Test
    fun aBoardIdIsUrlSafe() {
        assertAll(
            { assertEquals(first, BoardId(first.value), "a minted id is one") },
            { assertFailsWith<IllegalArgumentException> { BoardId("") } },
            { assertFailsWith<IllegalArgumentException> { BoardId("a&board=b") } },
            { assertFailsWith<IllegalArgumentException> { BoardId("a/b") } },
            { assertFailsWith<IllegalArgumentException> { BoardId("a b") } },
            { assertFailsWith<IllegalArgumentException> { BoardId("é") } },
        )
    }

    private fun socket(url: String) = Rendezvous.Existing(WebSocketAdvertisement(url, PeerId("bee-host"), "board"))

    /** Dials [rendezvous], expecting the recording loom's refusal. */
    private suspend fun Loom.attempt(rendezvous: Rendezvous) {
        assertIs<Recorded>(runCatching { weave(rendezvous) }.exceptionOrNull())
    }

    private class Recorded : Exception()

    /** A loom that records each socket URL it is asked to dial, and dials none. */
    private class RecordingLoom : Loom {
        val dialled = mutableListOf<String>()

        override suspend fun weave(rendezvous: Rendezvous): Seam {
            dialled += ((rendezvous as Rendezvous.Existing).tag as WebSocketAdvertisement).url
            throw Recorded()
        }
    }

    private object OtherTag : us.tractat.kuilt.core.Tag {
        override val sessionName: String = "other"
        override val peerKey: String = "other"
    }
}
