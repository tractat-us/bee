package us.tractat.bee.host

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.raft.DurableStoreRaftStorage
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

        /** Answers [option] as the person: the record, as the driver appends it, then their client's proposal. */
        suspend fun answer(actor: PeerIdentity, option: String): OrphanAnswers.Outcome {
            val context = buildJsonObject { put(OrphanDecision.OPTION_KEY, option) }
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
