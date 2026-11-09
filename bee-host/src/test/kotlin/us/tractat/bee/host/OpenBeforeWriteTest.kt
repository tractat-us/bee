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
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.PresenceEntry
import us.tractat.bee.core.SurfaceMeta
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

private val AGENT = ReplicaId("agent")
private val SAM = ReplicaId("web-sam")
private val KIM = ReplicaId("web-kim")
private const val POLL = "poll"

private fun TestScope.settle(duration: Duration) {
    advanceTimeBy(duration)
    runCurrent()
}

class OpenBeforeWriteTest {
    @Test
    fun experiment() = runTest(StandardTestDispatcher()) {
        val results = mutableListOf<String>()
        for (gap in listOf(100, 300, 600, 1000, 1500, 2500, 4000)) {
            results += "gap=$gap: " + scenario(gap.milliseconds)
        }
        println(results.joinToString("\n"))
        assertEquals(emptyList(), results.filter { "BAD" in it })
    }

    private suspend fun TestScope.scenario(gap: Duration): String {
        val star = BoardStar(this).apply { start() }
        val agent = star.client(AGENT)
        agent.connect()
        settle(200.milliseconds)
        agent.mutate { it.postPresence(AGENT, presence("agent"), 1) }
        settle(500.milliseconds)
        val sam = star.client(SAM)
        sam.connect()
        settle(100.milliseconds)
        sam.mutate { it.postPresence(SAM, presence("sam"), 1) }
        settle(300.milliseconds)
        val kim = star.client(KIM)
        kim.connect()
        settle(100.milliseconds)
        kim.mutate { it.postPresence(KIM, presence("kim"), 1) }
        settle(gap)

        val seen = mutableListOf<String>()
        val watch = backgroundScope.launch {
            kim.state.collect { s ->
                val doc = s.surfaceDoc(POLL) ?: return@collect
                seen += if (s.meta(POLL) == null) "BAD(data-before-open)" else "ok"
            }
        }
        val answer = backgroundScope.launch {
            sam.state.first { it.meta(POLL) != null }
            sam.mutate { it.appendEntry(SAM, POLL, "/answers", JsonPrimitive("SSO")) }
        }
        agent.mutate { it.openSurface(AGENT, POLL, meta(), listOf(component()), JsonObject(emptyMap())) }
        settle(5000.milliseconds)
        watch.cancel()
        answer.cancel()
        agent.close(); sam.close(); kim.close()
        star.relay.close()
        return seen.distinct().joinToString()
    }

    private fun presence(name: String) = PresenceEntry(PeerIdentity.human(name), null, null, Instant.parse("2026-11-08T00:00:00Z"), false)

    private fun meta() = SurfaceMeta("Poll", NOTIONS_CATALOG_ID, PeerIdentity.human("agent"), Instant.parse("2026-11-08T00:00:00Z"))

    private fun component() = buildJsonObject {
        put("id", "root")
        put("component", "Text")
        put("text", "Which?")
    }
}
