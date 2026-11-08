package us.tractat.bee.host

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import us.tractat.bee.a2ui.NOTIONS_CATALOG_ID
import us.tractat.bee.a2ui.SurfaceFormat
import us.tractat.bee.a2ui.toSurfaceView
import us.tractat.bee.core.ActionRecord
import us.tractat.bee.core.Basis
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.ClaimCommand
import us.tractat.bee.core.ClaimOutcome
import us.tractat.bee.core.ClaimStatus
import us.tractat.bee.core.ClaimTarget
import us.tractat.bee.core.DepartReason
import us.tractat.bee.core.LivePresence
import us.tractat.bee.core.OrphanAnswers
import us.tractat.bee.core.OrphanDecision
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.PresenceEntry
import us.tractat.bee.core.SurfaceMeta
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.raft.DurableStoreRaftStorage
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Orphan decisions (spec §6.4, plan Task 11), on the in-memory relay and voter under virtual time.
 * An agent claims a record and is departed, which orphans the claim; the host opens its decision
 * surface; a person answers it through their own client's claims, as the page's driver does.
 */
class OrphanDecisionsTest {
    private val ada = PeerIdentity.agent("ada", Random(1))
    private val bo = PeerIdentity.agent("bo", Random(2))
    private val iain = PeerIdentity.human("iain")
    private val target = ClaimTarget("record:web-iain:1")
    private val surfaceId = OrphanDecision.surfaceId(target)

    private class Rig(val test: TestScope, val star: BoardStar, val claims: ClaimsRig, val voter: ClaimsVoter, val answers: OrphanAnswers) {
        val board: BoardState get() = star.relay.state.value

        fun data(): JsonObject = requireNotNull(board.surfaceDoc(OrphanDecision.surfaceId(TARGET))).toSurfaceView(OrphanDecision.surfaceId(TARGET)).data

        fun optionIds(): List<String> = data().getValue("options").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }

        fun orphanSurfaces(): List<String> = board.openSurfaces().filter { it.startsWith(OrphanDecision.PREFIX) }

        /** The data path the decision's `Poll` binds its answers to now. */
        fun answersPath(): String = requireNotNull(board.surfaceDoc(OrphanDecision.surfaceId(TARGET)))
            .toSurfaceView(OrphanDecision.surfaceId(TARGET)).components.getValue("root")
            .getValue("answers").jsonObject.getValue("path").jsonPrimitive.content

        /** The value at [pointer] in the decision's data. */
        fun at(pointer: String): JsonElement? =
            SurfaceFormat.segments(pointer).fold(data() as JsonElement?) { node, key -> (node as? JsonObject)?.get(key) }

        /**
         * Answers [option] as the person: their answer at the `Poll`'s bound answers path and the
         * record, as the page's driver writes them (Task 11a's `NotionActions`), then their client's proposal.
         */
        suspend fun answer(actor: PeerIdentity, option: String): OrphanAnswers.Outcome {
            val context = buildJsonObject { put(OrphanDecision.OPTION_KEY, option) }
            val answered = "${answersPath()}/${SurfaceFormat.escapeSegment(actor.toString())}"
            star.relay.mutate { it.updateData(star.relay.replica, OrphanDecision.surfaceId(TARGET), answered, JsonPrimitive(option)) }
            star.relay.mutate { state ->
                val record = ActionRecord(
                    surfaceId = OrphanDecision.surfaceId(TARGET),
                    componentId = "root",
                    actor = actor,
                    name = OrphanDecision.ANSWER_ACTION,
                    context = context,
                    basis = Basis.capture(state, OrphanDecision.surfaceId(TARGET)),
                    at = star.clock(),
                )
                state.appendRecord(star.relay.replica, record)
            }
            return answers.answered(OrphanDecision.surfaceId(TARGET), OrphanDecision.ANSWER_ACTION, context)
        }

        /** Every command in the voter's log, in order. */
        suspend fun log(): List<ClaimCommand?> = DurableStoreRaftStorage.open(claims.store).entries(1).map { entry ->
            try {
                ClaimCommand.decode(entry.command)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }

    /**
     * Ada holds [target] and departs, with bo present. The host's decision surface is open before
     * [body] runs. The person answers through a claims client of their own.
     */
    private fun rig(body: suspend Rig.() -> Unit) = runTest(timeout = 120.seconds) {
        val star = BoardStar(this).apply { start() }
        val claims = ClaimsRig(this)
        val voter = ClaimsVoter.start(backgroundScope, claims.host, claims.store, claims.raftConfig, boardMirror(star.relay), claims.clock)
        val roster = LivePresence(star.relay.state, star.clock, backgroundScope) { throw it }
        OrphanDecisions(star.relay, { roster.live().map { it.identity } }, star.clock).start(backgroundScope)
        star.relay.mutate { it.postPresence(ReplicaId("mcp-bo"), PresenceEntry(bo, null, null, star.clock(), waiting = false), 1) }
        // Ada's entry outlives her for its TTL: her decision must still not offer her.
        star.relay.mutate { it.postPresence(ReplicaId("mcp-ada"), PresenceEntry(ada, null, null, star.clock(), waiting = false), 1) }
        val page = claims.client(NodeId("web-iain-page"))
        val rig = Rig(this, star, claims, voter, OrphanAnswers({ page }, iain))

        val won = claims.client(NodeId("bridge-ada")).claim(target, ada)
        assertIs<ClaimOutcome.Won>(won, "rig: ada held the record")
        voter.depart(ada, DepartReason.Crashed)
        star.relay.state.first { surfaceId in it.openSurfaces() }
        rig.body()
        voter.close()
    }

    /**
     * One orphan opens one decision surface, a `Poll` offering each present peer but the retired
     * holder, release and wait. More board changes open no second one.
     */
    @Test
    fun orphanOpensOneDecisionSurface() = rig {
        star.relay.mutate { it.postPresence(ReplicaId("mcp-bo"), PresenceEntry(bo, null, "busy", star.clock(), waiting = false), 2) }
        delay(10.seconds)
        val root = requireNotNull(board.surfaceDoc(surfaceId)).toSurfaceView(surfaceId).components.getValue("root")
        val question = data().getValue("question").jsonArray.last().jsonPrimitive.content
        assertAll(
            { assertEquals(listOf(surfaceId), orphanSurfaces(), "one decision surface") },
            { assertEquals("Poll", root.getValue("component").jsonPrimitive.content) },
            { assertEquals(listOf(OrphanDecision.reassignOption(bo), OrphanDecision.RELEASE, OrphanDecision.WAIT), optionIds()) },
            { assertTrue(ada.toString() in question, "the question names the retired holder: $question") },
            { assertEquals(target.value, data().getValue("target").jsonPrimitive.content) },
        )
    }

    /**
     * A person in presence, or a room screen (a `human:` peer too), is not offered a reassign: a
     * person has no release path in v1, so the claim would be held for good. An agent arriving in
     * the same pass is, so the pass ran.
     */
    @Test
    fun aHumanInPresenceIsNotOffered() = rig {
        val wall = PeerIdentity.human("wall")
        val cy = PeerIdentity.agent("cy", Random(4))
        star.relay.mutate { it.postPresence(ReplicaId("web-iain"), PresenceEntry(iain, null, null, star.clock(), waiting = false), 1) }
        star.relay.mutate { it.postPresence(ReplicaId("web-wall"), PresenceEntry(wall, null, null, star.clock(), waiting = false), 1) }
        star.relay.mutate { it.postPresence(ReplicaId("mcp-cy"), PresenceEntry(cy, null, null, star.clock(), waiting = false), 1) }
        delay(10.seconds)
        assertEquals(
            listOf(OrphanDecision.reassignOption(bo), OrphanDecision.RELEASE, OrphanDecision.WAIT, OrphanDecision.reassignOption(cy)),
            optionIds(),
            "only the agents are offered",
        )
    }

    /** "Reassign to bo" commits a `Reassign` through Raft: bo holds the record, under a grant. */
    @Test
    fun answerReassignCommitsThroughRaft() = rig {
        val outcome = answer(iain, OrphanDecision.reassignOption(bo))
        val table = voter.table.first { it.status(target) is ClaimStatus.Held }
        assertAll(
            { assertEquals(OrphanAnswers.Outcome.Proposed(OrphanDecision.Choice.Reassign(bo), applied = true), outcome) },
            { assertEquals(bo, (table.status(target) as ClaimStatus.Held).holder) },
            { assertTrue(table.reassigned(target), "held by a grant, not a win") },
        )
    }

    /** "Wait" proposes nothing: the claim stays orphaned and its decision stays open. */
    @Test
    fun waitLeavesTheClaimOrphaned() = rig {
        val before = log().size
        val outcome = answer(iain, OrphanDecision.WAIT)
        delay(30.seconds)
        val after = log().size
        assertAll(
            { assertEquals(OrphanAnswers.Outcome.Waited, outcome) },
            { assertIs<ClaimStatus.Orphaned>(voter.table.value.status(target)) },
            { assertEquals(before, after, "nothing was proposed") },
            { assertEquals(listOf(surfaceId), orphanSurfaces(), "the decision stays open") },
        )
    }

    /**
     * Once the target isn't orphaned, the host closes its decision surface: here, released. The
     * surface keeps its content and records, in the history strip.
     */
    @Test
    fun resolvedOrphanClosesItsSurface() = rig {
        val outcome = answer(iain, OrphanDecision.RELEASE)
        star.relay.state.first { surfaceId in it.closedSurfaces() }
        assertAll(
            { assertEquals(OrphanAnswers.Outcome.Proposed(OrphanDecision.Choice.Release, applied = true), outcome) },
            { assertEquals(ClaimStatus.Free, voter.table.value.status(target)) },
            { assertEquals(emptyList(), orphanSurfaces(), "no decision left open") },
            { assertEquals(1, board.records(surfaceId).size, "the answer stays with the surface") },
        )
    }

    /**
     * A target orphaned again asks again, on the same surface: reassigned to bo, it closes; bo
     * departs, and it reopens with a new question naming bo, for the new orphaning's token.
     */
    @Test
    fun aReorphanedTargetAsksAgain() = rig {
        answer(iain, OrphanDecision.reassignOption(bo))
        star.relay.state.first { surfaceId in it.closedSurfaces() }
        voter.depart(bo, DepartReason.Crashed)
        val reopened = star.relay.state.first { surfaceId in it.openSurfaces() }
        val questions = requireNotNull(reopened.surfaceDoc(surfaceId)).toSurfaceView(surfaceId).data.getValue("question").jsonArray
        val orphaned = voter.table.value.status(target) as ClaimStatus.Orphaned
        assertAll(
            { assertEquals(listOf(surfaceId), orphanSurfaces(), "still one decision surface") },
            { assertEquals(2, questions.size, "a second revision: $questions") },
            { assertTrue(bo.toString() in questions.last().jsonPrimitive.content, "naming the new holder") },
            { assertEquals("${orphaned.token.term}:${orphaned.token.index}", data().getValue("token").jsonPrimitive.content) },
        )
    }

    /**
     * Answers belong to the orphaning they answered. Iain answers the first, which reassigns the
     * record to bo; bo departs, and the decision asks again with its `Poll` bound to the new
     * orphaning's answers, which hold none. The first orphaning's answers are kept at their own path.
     */
    @Test
    fun answersBelongToTheirOrphaning() = rig {
        val first = answersPath()
        val firstToken = data().getValue("token").jsonPrimitive.content
        answer(iain, OrphanDecision.reassignOption(bo))
        star.relay.state.first { surfaceId in it.closedSurfaces() }
        voter.depart(bo, DepartReason.Crashed)
        star.relay.state.first { surfaceId in it.openSurfaces() && data().getValue("token").jsonPrimitive.content != firstToken }
        val token = data().getValue("token").jsonPrimitive.content
        assertAll(
            { assertEquals("/rounds/$token/answers", answersPath(), "bound to the new orphaning's answers") },
            { assertEquals(JsonObject(emptyMap()), at(answersPath()), "which hold none yet") },
            { assertEquals(JsonPrimitive(OrphanDecision.reassignOption(bo)), at("$first/${SurfaceFormat.escapeSegment(iain.toString())}"), "the first orphaning keeps its answer") },
        )
    }

    /**
     * One decision surface the host can't extend stops no other decision. A bridge opened
     * `orphan-<second>` first, with a `question` that isn't an append-only array; bo, holding that
     * target and a third, departs. The host can't ask on the squatted surface, and still opens the
     * third's decision, and keeps ada's.
     */
    @Test
    fun aMalformedDecisionStopsNoOther() = rig {
        val second = ClaimTarget("record:web-iain:2")
        val third = ClaimTarget("record:web-iain:3")
        star.relay.mutate {
            it.openSurface(
                ReplicaId("mcp-zed"),
                OrphanDecision.surfaceId(second),
                SurfaceMeta("squatted", NOTIONS_CATALOG_ID, PeerIdentity.agent("zed", Random(3)), star.clock()),
                listOf(buildJsonObject { put("id", "root"); put("component", "Text"); put("text", "squatted") }),
                buildJsonObject { put("question", "not an array") },
            )
        }
        val bridge = claims.client(NodeId("bridge-bo"))
        assertIs<ClaimOutcome.Won>(bridge.claim(second, bo), "rig: bo held the second record")
        assertIs<ClaimOutcome.Won>(bridge.claim(third, bo), "rig: bo held the third record")
        voter.depart(bo, DepartReason.Crashed)
        val opened = withTimeoutOrNull(30.seconds) { star.relay.state.first { OrphanDecision.surfaceId(third) in it.openSurfaces() } }
        assertAll(
            { assertNotNull(opened, "the third orphan's decision opened") },
            { assertEquals(listOf(OrphanDecision.RELEASE, OrphanDecision.WAIT), opened?.let { optionsOf(it, third) }, "offering release and wait") },
            { assertTrue(surfaceId in board.openSurfaces(), "ada's decision stays open") },
        )
    }

    private fun optionsOf(state: BoardState, target: ClaimTarget): List<String> {
        val id = OrphanDecision.surfaceId(target)
        return requireNotNull(state.surfaceDoc(id)).toSurfaceView(id).data.getValue("options").jsonArray
            .map { it.jsonObject.getValue("id").jsonPrimitive.content }
    }

    /**
     * The host proposes nothing but `Depart`: through an orphan, its decision surface, a "Wait"
     * answer and a minute of board changes, the log holds only ada's claim and her departure.
     */
    @Test
    fun hostProposesNothingButDepart() = rig {
        answer(iain, OrphanDecision.WAIT)
        repeat(6) { n ->
            star.relay.mutate { it.postPresence(ReplicaId("mcp-bo"), PresenceEntry(bo, null, "step $n", star.clock(), waiting = false), 3L + n) }
            delay(10.seconds)
        }
        val log = log().filterNot { it == null }
        assertAll(
            { assertEquals(2, log.size, "only the claim and the Depart: $log") },
            { assertIs<ClaimCommand.Claim>(log[0]) },
            { assertEquals(ClaimCommand.Depart(ada, DepartReason.Crashed), log[1]) },
        )
    }

    private companion object {
        val TARGET = ClaimTarget("record:web-iain:1")
    }
}
