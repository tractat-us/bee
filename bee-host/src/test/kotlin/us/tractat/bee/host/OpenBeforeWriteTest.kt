@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.bee.host

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import us.tractat.bee.a2ui.NOTIONS_CATALOG_ID
import us.tractat.bee.a2ui.SurfaceFormat
import us.tractat.bee.a2ui.toSurfaceView
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.PresenceEntry
import us.tractat.bee.core.SurfaceMeta
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val AGENT = ReplicaId("agent")
private val SAM = ReplicaId("web-sam")
private val KIM = ReplicaId("web-kim")
private const val POLL = "poll"
private val AT = Instant.parse("2026-11-08T00:00:00Z")

/**
 * The relay seeds the repro runs under. On kuilt 0.7.0-dev.1926 the answer overtakes the open
 * under 1 and 4 to 7, and under 17 of seeds 0 to 29.
 */
private val SEEDS = 0..7

private fun TestScope.settle(duration: Duration) {
    advanceTimeBy(duration)
    runCurrent()
}

/**
 * bee#171 over the in-memory relay: why a page can hold a surface's data before its open.
 *
 * A page that joins a running board first sights the host and every agent mid-stream. Its
 * `GossipSeam` holds each such origin's first frame for up to `reorderGrace`, waiting for seqs
 * from before it joined (tractat-us/kuilt#2916). The agent's open reaches the page only through
 * those held frames: the agent's own delta, which the page's Quilter can't apply anyway, since its
 * cursor for a spoke that was mid-stream never heals (tractat-us/kuilt#3148), and the host's
 * forward of it. Another page's answer, that page's first write, comes from an origin the page
 * sights from seq 1, and passes at once. The Quilter delivers in order per origin, not causally
 * across origins, so nothing holds the answer back for the open it was based on.
 *
 * This repro leans on those two kuilt bugs, and on a schedule of the relay's random draws that
 * lets the answer overtake the open: a seed is one such schedule, and only some of them overtake.
 * So it runs under [SEEDS]. The guard must hold under every one, and the overtake must happen
 * under at least one, or the test proves nothing. The guard's lasting coverage is
 * `BoardStateTest`'s `aWriteThatArrivesBeforeItsSurfacesOpenWaitsUnshownUntilTheOpenArrives`.
 */
class OpenBeforeWriteTest {
    /** What kim saw of the poll under one seed. */
    private data class Outcome(
        val answerBeforeOpen: Boolean,
        val readFailures: List<Throwable>,
        val shownWithoutMeta: List<String>,
        val open: List<String>,
        val answers: String,
    )

    @Test
    fun aPageThatJoinedLateWaitsForTheOpenOfAnAnswerThatOvertookIt() {
        val outcomes = SEEDS.associateWith { seed ->
            lateinit var outcome: Outcome
            runTest(StandardTestDispatcher()) { outcome = lateJoin(seed) }
            outcome
        }
        assertAll(
            {
                assertTrue(
                    outcomes.values.any { it.answerBeforeOpen },
                    "precondition: under some seed in $SEEDS, kim held the answer before the open. If none does, the " +
                        "repro no longer reproduces: either kuilt#2916/#3148 are fixed, or the relay's draws moved " +
                        "and every seed here now delivers the open first. Try more seeds before deciding which.",
                )
            },
            *outcomes.flatMap { (seed, it) ->
                listOf(
                    { assertEquals(emptyList(), it.readFailures, "seed $seed: reading kim's board never threw") },
                    { assertEquals(emptyList(), it.shownWithoutMeta, "seed $seed: kim never showed the poll before its open") },
                    { assertEquals(listOf(POLL), it.open, "seed $seed: kim shows the poll once its open arrives") },
                    { assertEquals("[\"SSO\"]", it.answers, "seed $seed: with the answer") },
                )
            }.toTypedArray(),
        )
    }

    private suspend fun TestScope.lateJoin(seed: Int): Outcome {
        val star = BoardStar(this, seed = seed).apply { start() }
        val agent = star.client(AGENT)
        agent.connect()
        settle(200.milliseconds)
        // The agent and the host broadcast before the pages join, so the pages sight both mid-stream.
        agent.mutate { it.postPresence(AGENT, presence(), 1) }
        settle(500.milliseconds)
        val sam = star.client(SAM)
        val kim = star.client(KIM)
        sam.connect()
        settle(400.milliseconds)
        kim.connect()
        settle(100.milliseconds)

        var answerBeforeOpen = false
        val readFailures = mutableListOf<Throwable>()
        val shownWithoutMeta = mutableListOf<String>()
        backgroundScope.launch {
            kim.state.collect { board ->
                // Read raw, past the board's reads: whether the open's meta has landed in the doc.
                val opened = board.surfaceDoc(POLL)?.let { it[SurfaceFormat.META] != null } ?: return@collect
                if (!opened) answerBeforeOpen = true
                runCatching { board.openSurfaces() }
                    .onSuccess { open -> if (POLL in open && !opened) shownWithoutMeta += POLL }
                    .onFailure { readFailures += it }
            }
        }
        backgroundScope.launch {
            sam.state.first { it.meta(POLL) != null }
            sam.mutate { it.appendEntry(SAM, POLL, "/answers", JsonPrimitive("SSO")) }
        }
        agent.mutate { it.openSurface(AGENT, POLL, meta(), listOf(poll()), JsonObject(emptyMap())) }
        settle(3.seconds)

        val answers = kim.state.value.surfaceDoc(POLL)?.toSurfaceView(POLL)?.data?.get("answers")
        return Outcome(answerBeforeOpen, readFailures, shownWithoutMeta, kim.state.value.openSurfaces(), answers.toString())
    }

    private fun presence() = PresenceEntry(PeerIdentity.human("agent"), null, null, AT, false)

    private fun meta() = SurfaceMeta("Poll", NOTIONS_CATALOG_ID, PeerIdentity.human("agent"), AT)

    private fun poll() = buildJsonObject {
        put("id", "root")
        put("component", "Text")
        put("text", "Which?")
    }
}
