package us.tractat.bee.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import us.tractat.bee.core.Basis
import us.tractat.bee.core.ClaimOutcome
import us.tractat.bee.core.ClaimStatus
import us.tractat.bee.core.ClaimTarget
import us.tractat.bee.core.ClaimView
import us.tractat.bee.core.ClaimsConnection
import us.tractat.bee.core.ClaimsLink
import us.tractat.bee.core.DepartReason
import us.tractat.bee.host.BeeHost
import us.tractat.bee.host.ClaimsVoter
import us.tractat.bee.host.InMemoryClaimsHost
import us.tractat.kuilt.cluster.ClusterEndpoints
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.test.assertAll
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class BridgeClaimsTest {
    private val iain = ReplicaId("web-iain")

    /** A click on a surface reaches a waiting session claimed: the voter holds it under the token the wait returned, and the board mirrors it. */
    @Test
    fun waitReturnsAClickClaimedWithItsToken() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        val browser = rig.browser()
        ada.call(BeeTools.OPEN, openArgs("s"))
        browser.state.first { "s" in it.openSurfaces() }

        browser.mutate { it.appendRecord(iain, click(it, "s", "go")) }
        val record = ada.wait("s").record()
        val token = BoardActionSource.parseToken(record.string("token"))
        val target = BoardActionSource.target(BoardActionSource.parseRecordId(record.string("recordId")))
        val mirrored = browser.state.first { it.claimView(target)?.status == ClaimView.HELD }.claimView(target)
        assertAll(
            { assertEquals("go", record.string("componentId")) },
            { assertEquals(true, record.getValue("basisComplete").jsonPrimitive.boolean) },
            { assertEquals(ClaimStatus.Held(ada.bridge.self, token), rig.voter.table.value.status(target), "the voter holds it under the returned token") },
            { assertEquals(BoardActionSource.tokenText(token), mirrored?.token, "the board mirrors the same token") },
        )
        rig.close()
    }

    /** Two sessions wait on one surface: each click goes to exactly one of them. */
    @Test
    fun twoWaitersNeverBothGetOneClick() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        val bo = rig.session("bo")
        val browser = rig.browser()
        ada.call(BeeTools.OPEN, openArgs("s"))
        bo.client.state.first { "s" in it.openSurfaces() }
        browser.state.first { "s" in it.openSurfaces() }

        val waits = listOf(ada, bo).map { s -> async { s.wait("s", timeoutSeconds = 10) } }
        browser.mutate { it.appendRecord(iain, click(it, "s", "go")) }
        val first = waits.map { it.await() }
        val loser = listOf(ada, bo)[first.indexOfFirst { it.timedOut }.coerceAtLeast(0)]
        val again = async { loser.wait("s", cursor = first[listOf(ada, bo).indexOf(loser)].cursor, timeoutSeconds = 10) }
        browser.mutate { it.appendRecord(iain, click(it, "s", "go")) }
        val second = again.await()
        val ids = (first.mapNotNull { it.record } + listOfNotNull(second.record)).map { it.string("recordId") }
        assertAll(
            { assertEquals(1, first.count { it.record != null }, "one waiter got the first click: $first") },
            { assertEquals(1, first.count { it.timedOut }, "the other timed out") },
            { assertTrue(second.record != null, "control: the other waiter gets the next click") },
            { assertEquals(ids.distinct(), ids, "no click went to two waiters") },
        )
        rig.close()
    }

    /**
     * Review Focus 3: a wait cancelled while its claim is in flight leaves nothing held. The
     * claim's commit is held on its way back, so the claim wins at the voter while the waiter is
     * still waiting; the wait is cancelled; when the commit lands, the claim is released.
     */
    @Test
    fun cancelledWaitReleasesItsClaim() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        val browser = rig.browser()
        ada.call(BeeTools.OPEN, openArgs("s"))
        val warmUp = ada.bridge.claim(ClaimTarget("record:warm-up:0"))
        browser.state.first { "s" in it.openSurfaces() }
        browser.mutate { it.appendRecord(iain, click(it, "s", "go")) }
        val (id, _) = ada.client.state.first { it.records("s").isNotEmpty() }.records("s").single()
        val target = BoardActionSource.target(id)

        val hold = rig.claims.host.holdFor(ada.node())
        hold.hold()
        val wait = launch { BoardActionSource(ada.bridge).next("s", null, 60.seconds) }
        val atVoter = rig.voter.table.first { it.status(target) is ClaimStatus.Held }.status(target)
        val parked = hold.parkedSends
        wait.cancelAndJoin()
        hold.releaseAll()
        val freed = withTimeoutOrNull(10.seconds) { rig.voter.table.first { it.status(target) == ClaimStatus.Free } }
        assertAll(
            { assertIs<ClaimOutcome.Won>(warmUp, "rig: the learner was admitted") },
            { assertIs<ClaimStatus.Held>(atVoter, "the claim won at the voter while the wait held") },
            { assertTrue(parked > 0, "rig: the hold caught the commit on its way back") },
            { assertNotNull(freed, "the cancelled wait's claim was released") },
        )
        rig.close()
    }

    /**
     * A wait whose first claim times out still gets the record. The claim's proposal is held on
     * its way up past the claim's 10 s timeout, so the source retries; when the hold lets go,
     * the first claim wins late and is released as abandoned, and the retry is told it lost to
     * this very session. The record must stay a candidate, and the wait must end with it.
     */
    @Test
    fun aRetryNeverLosesTheRecordToItsOwnTimedOutClaim() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        val browser = rig.browser()
        ada.call(BeeTools.OPEN, openArgs("s"))
        val warmUp = ada.bridge.claim(ClaimTarget("record:warm-up:0"))
        browser.state.first { "s" in it.openSurfaces() }
        browser.mutate { it.appendRecord(iain, click(it, "s", "go")) }
        val (id, _) = ada.client.state.first { it.records("s").isNotEmpty() }.records("s").single()
        val target = BoardActionSource.target(id)

        val hold = rig.claims.host.holdUpFor(ada.node())
        hold.hold()
        val parked = async {
            delay(11.seconds)
            hold.parkedSends.also { hold.releaseAll() }
        }
        val waited = ada.wait("s", timeoutSeconds = 50)
        val caught = parked.await()
        val token = waited.record?.let { BoardActionSource.parseToken(it.string("token")) }
        val atVoter = rig.voter.table.value.status(target)
        assertAll(
            { assertIs<ClaimOutcome.Won>(warmUp, "rig: the learner was admitted") },
            { assertTrue(caught > 0, "rig: the hold caught the first claim on its way up") },
            { assertEquals(false, waited.timedOut, "the wait ends with the record: $waited, voter $atVoter") },
            { assertEquals<ClaimStatus?>(token?.let { ClaimStatus.Held(ada.bridge.self, it) }, atVoter, "held under the returned token") },
        )
        rig.close()
    }

    /** `bee_action_done` with a stale token is refused and changes nothing; with the wait's token it completes. */
    @Test
    fun doneWithAStaleTokenIsRejected() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        val browser = rig.browser()
        ada.call(BeeTools.OPEN, openArgs("s"))
        browser.state.first { "s" in it.openSurfaces() }
        browser.mutate { it.appendRecord(iain, click(it, "s", "go")) }
        val record = ada.wait("s").record()
        val recordId = record.string("recordId")
        val token = BoardActionSource.parseToken(record.string("token"))
        val target = BoardActionSource.target(BoardActionSource.parseRecordId(recordId))

        val stale = ada.call(BeeTools.DONE, mapOf("recordId" to recordId, "token" to "${token.term}:${token.index + 100}"))
        val afterStale = rig.voter.table.value.status(target)
        val done = ada.call(BeeTools.DONE, mapOf("recordId" to recordId, "token" to BoardActionSource.tokenText(token)))
        assertAll(
            { assertTrue(stale.isError == true && recordId in stale.text(), "stale: ${stale.text()}") },
            { assertEquals(ClaimStatus.Held(ada.bridge.self, token), afterStale, "the stale done changed nothing") },
            { assertEquals(null, done.isError, "control: the real token completes: ${done.text()}") },
            { assertEquals(ClaimStatus.Done(ada.bridge.self), rig.voter.table.value.status(target)) },
        )
        rig.close()
    }

    /**
     * Once the host retires a session's identity, its next write is refused, naming the retired
     * identity, why, and the claim it left orphaned; the bridge has re-identified, and the same
     * write then lands under the fresh identity.
     */
    @Test
    fun retiredBridgeRefusesWritesAndReidentifies() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        val browser = rig.browser()
        ada.call(BeeTools.OPEN, openArgs("s"))
        browser.state.first { "s" in it.openSurfaces() }
        browser.mutate { it.appendRecord(iain, click(it, "s", "go")) }
        val recordId = ada.wait("s").record().string("recordId")
        val old = ada.bridge.self

        rig.voter.depart(old, DepartReason.Crashed)
        ada.link().client.table.first { it.retired(old) }
        val refused = ada.call(BeeTools.OPEN, openArgs("t"))
        val fresh = ada.bridge.self
        val afterRefusal = ada.client.state.value.surfaceDoc("t")
        val retried = ada.call(BeeTools.OPEN, openArgs("t"))
        assertAll(
            { assertTrue(refused.isError == true, "refused: ${refused.text()}") },
            { assertTrue(old.toString() in refused.text() && "Crashed" in refused.text(), "names who and why: ${refused.text()}") },
            { assertTrue("record:$recordId" in refused.text(), "names the orphaned claim: ${refused.text()}") },
            { assertEquals(null, afterRefusal, "the refused write never landed") },
            { assertNotEquals(old, fresh, "the bridge re-identified") },
            { assertEquals(null, retried.isError, "the retried write lands: ${retried.text()}") },
            { assertEquals(fresh, ada.client.state.value.meta("t")?.openedBy, "under the fresh identity") },
        )
        rig.close()
    }

    /** A record whose basis names a dot this session's replica hasn't got is handed over with `basisComplete = false`. */
    @Test
    fun basisNamingAnUndeliveredDotIsIncomplete() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        val browser = rig.browser()
        ada.call(BeeTools.OPEN, openArgs("s"))
        browser.state.first { "s" in it.openSurfaces() }

        browser.mutate { it.appendRecord(iain, click(it, "s", "go")) }
        val plain = ada.wait("s")
        browser.mutate { state ->
            val seen = Basis.capture(state, "s")
            val ghost = Dot(ReplicaId("web-ghost"), 1)
            state.appendRecord(iain, click(state, "s", "go", seen.copy(recordDots = seen.recordDots + ghost)))
        }
        val ghosted = ada.wait("s", cursor = plain.cursor)
        assertAll(
            { assertEquals(true, plain.record().getValue("basisComplete").jsonPrimitive.boolean, "control: a basis it holds is complete") },
            { assertEquals(false, ghosted.record().getValue("basisComplete").jsonPrimitive.boolean, "a basis naming an undelivered dot is not") },
        )
        rig.close()
    }

    /**
     * The claims link names its loom after its node: the voter replicates to the id the client
     * answers to, so a claim through it comes back `Won`, not `Unavailable`. Its coordinates are
     * the ones the host serves.
     */
    @Test
    fun claimsLinkNamesItsLoomAfterItsNode() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")

        val link = ada.link()
        val outcome = link.client.claim(ClaimTarget("record:pin:1"), ada.bridge.self, timeout = 5.seconds)
        assertAll(
            { assertIs<ClaimOutcome.Won>(outcome, "the voter answered the node the client is") },
            { assertEquals(ClaimsVoter.HOST_NODE, ClaimsLink.VOTER) },
            { assertEquals(BeeHost.CLAIMS_PATH, ClaimsLink.PATH) },
            { assertEquals(BeeHost.CLAIMS_SESSION, ClaimsLink.SESSION) },
            { assertEquals(BeeHost.CLAIMS_SERVER, ClaimsLink.SERVER) },
        )
        rig.close()
    }

    /**
     * Review Focus 3, at the hand-over: a wait cancelled after its claim won, before it returned
     * the record, releases the claim under its token. The hook holds the wait just past the win.
     */
    @Test
    fun aWaitCancelledAfterItsWinReleasesTheClaim() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val (ada, target) = rig.clicked()
        val won = CompletableDeferred<Unit>()
        val source = BoardActionSource(ada.bridge) { won.complete(Unit); awaitCancellation() }

        val wait = launch { source.next("s", null, 30.seconds) }
        won.await()
        val heldAtWin = rig.voter.table.value.status(target)
        wait.cancelAndJoin()
        val freed = withTimeoutOrNull(10.seconds) { rig.voter.table.first { it.status(target) == ClaimStatus.Free } }
        assertAll(
            { assertIs<ClaimStatus.Held>(heldAtWin, "rig: the wait had won the claim") },
            { assertNotNull(freed, "the claim nobody received was released") },
        )
        rig.close()
    }

    /** A wait whose bound lands just after its claim won hands the record over, never drops it. */
    @Test
    fun aWinAtTheBoundIsHandedOver() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val (ada, target) = rig.clicked()
        val source = BoardActionSource(ada.bridge) { delay(1.hours) }

        val result = source.next("s", null, 30.seconds)
        val token = result.record?.let { BoardActionSource.parseToken(it.getValue("token").jsonPrimitive.content) }
        assertAll(
            { assertEquals(false, result.timedOut, "the win is handed over: $result") },
            { assertEquals<ClaimStatus?>(token?.let { ClaimStatus.Held(ada.bridge.self, it) }, rig.voter.table.value.status(target)) },
        )
        rig.close()
    }

    /** A session retired while it waits gets the refusal, naming who and why, not a silent wait. */
    @Test
    fun aRetirementDuringAWaitEndsItWithTheRefusal() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val (ada, _) = rig.clicked()
        val old = ada.bridge.self
        rig.voter.depart(old, DepartReason.Quiet)
        ada.link().client.table.first { it.retired(old) }

        val refused = ada.call(WaitTool.WAIT, mapOf("surfaceId" to "s", "timeoutSeconds" to 30))
        assertAll(
            { assertEquals(true, refused.isError, "refused: ${refused.text()}") },
            { assertTrue(old.toString() in refused.text() && "Quiet" in refused.text(), "names who and why: ${refused.text()}") },
            { assertNotEquals(old, ada.bridge.self, "the bridge re-identified") },
        )
        rig.close()
    }

    /**
     * `/clear` end to end: the hook's `POST /retire` retires the identity, its `Depart` commits as
     * `Cleared` through Raft, and the claim it held shows orphaned.
     */
    @Test
    fun postRetireDepartsClearedAndOrphansTheClaims() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val (ada, _) = rig.clicked()
        val recordId = ada.wait("s").record().string("recordId")
        val target = BoardActionSource.target(BoardActionSource.parseRecordId(recordId))
        val old = ada.bridge.self
        val runDir = Files.createTempDirectory("bee-run")

        val status = ClearControl(ada.identity, backgroundScope, runDir, claudePid = 4242, answerWithin = 5.seconds).use { clear ->
            clear.start()
            val token = Json.parseToJsonElement(checkNotNull(clear.runFile).readText()).jsonObject.getValue("token").jsonPrimitive.content
            withContext(Dispatchers.IO) {
                HttpClient.newHttpClient().use { http ->
                    http.send(
                        HttpRequest.newBuilder(URI("http://127.0.0.1:${clear.port}${ClearControl.RETIRE_PATH}"))
                            .timeout(java.time.Duration.ofSeconds(5))
                            .header("Authorization", "Bearer $token")
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                        HttpResponse.BodyHandlers.ofString(),
                    ).statusCode()
                }
            }
        }
        val after = rig.voter.table.first { it.retired(old) }
        assertAll(
            { assertEquals(200, status) },
            { assertEquals(DepartReason.Cleared, after.departReason(old)) },
            { assertIs<ClaimStatus.Orphaned>(after.status(target), "the claim it held is orphaned") },
            { assertNotEquals(old, ada.bridge.self, "the bridge acts as a fresh identity") },
        )
        rig.close()
    }

    /**
     * A claims link that dies (kuilt#3110: a refused dial ends its reconnect loop) is rebuilt.
     * While it's down, claim tools say claims are offline, `bee_board` says so, and a `/clear`
     * `Depart` waits; once a link is back, the `Depart` commits.
     */
    @Test
    fun aDeadClaimsLinkIsRebuiltAndItsDepartCommitsAfter() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val breaker = CompletableDeferred<Unit>()
        val ada = rig.session("ada", retryDelay = { 10.seconds }) { n, linkScope ->
            if (n == 1) linkScope.launch { breaker.await(); throw IllegalStateException("refused dial (test)") }
        }
        val first = ada.link().node
        ada.call(BeeTools.OPEN, openArgs("s"))
        breaker.complete(Unit)
        ada.connection.link.first { it == null }

        val offline = ada.call(BeeTools.CLAIM, mapOf("target" to "record:x:1"))
        val board = ada.call(BeeTools.BOARD, emptyMap()).json().getValue("claims").jsonPrimitive.content
        val old = ada.bridge.self
        ada.identity.retire(DepartReason.Cleared)
        val pendingWhileDown = ada.connection.pendingDeparts.value
        val second = ada.link().node
        val departed = rig.voter.table.first { it.retired(old) }
        val pendingAfter = ada.connection.pendingDeparts.first { it.isEmpty() }
        assertAll(
            { assertTrue(offline.isError == true && CLAIMS_OFFLINE in offline.text(), "offline: ${offline.text()}") },
            { assertEquals("offline", board, "bee_board says claims are offline") },
            { assertEquals(listOf(old to DepartReason.Cleared), pendingWhileDown, "the Depart waited for a link") },
            { assertNotEquals(first, second, "a fresh link, under a fresh node") },
            { assertEquals(DepartReason.Cleared, departed.departReason(old), "the Depart committed on the new link") },
            { assertEquals(emptyList(), pendingAfter) },
            { assertEquals(1, ada.linkFailures.size, "the failure was heard, not swallowed") },
        )
        rig.close()
    }

    /** A wait that starts while the claims link is being rebuilt holds for it, then claims the click. */
    @Test
    fun aWaitHoldsThroughAClaimsRebuild() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val breaker = CompletableDeferred<Unit>()
        val ada = rig.session("ada", retryDelay = { 10.seconds }) { n, linkScope ->
            if (n == 1) linkScope.launch { breaker.await(); throw IllegalStateException("refused dial (test)") }
        }
        val browser = rig.browser()
        ada.call(BeeTools.OPEN, openArgs("s"))
        browser.state.first { "s" in it.openSurfaces() }
        breaker.complete(Unit)
        ada.connection.link.first { it == null }

        val wait = async { ada.wait("s", timeoutSeconds = 30) }
        browser.mutate { it.appendRecord(iain, click(it, "s", "go")) }
        val waited = wait.await()
        assertAll(
            { assertEquals(false, waited.timedOut, "the wait got the click once claims were back: $waited") },
            { assertEquals(1, ada.linkFailures.size, "rig: the first link failed") },
        )
        rig.close()
    }

    /**
     * A claims link that dies after the voter appended this session's claim, before the client
     * heard the commit, leaves the target held under the session's identity with nobody to
     * release it. The wait still gets the record, on the rebuilt link: it gives that claim back
     * and claims the record again, so it returns a fresh token, not the appended one.
     */
    @Test
    fun aLinkThatDiesMidClaimLeavesTheRecordForTheWait() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val breaker = CompletableDeferred<Unit>()
        val ada = rig.session("ada", retryDelay = { 1.seconds }) { n, linkScope ->
            if (n == 1) linkScope.launch { breaker.await(); throw IllegalStateException("link died (test)") }
        }
        val target = rig.clickOn(ada)
        val hold = rig.claims.host.holdFor(ada.node())
        hold.hold()

        val wait = async { ada.wait("s", timeoutSeconds = 40) }
        val appended = rig.voter.table.first { it.status(target) is ClaimStatus.Held }.status(target)
        breaker.complete(Unit)
        val waited = wait.await()
        val token = waited.record?.let { BoardActionSource.parseToken(it.string("token")) }
        val atVoter = rig.voter.table.value.status(target)
        assertAll(
            { assertEquals(ada.bridge.self, (appended as? ClaimStatus.Held)?.holder, "rig: the voter held it for the session") },
            { assertEquals(false, waited.timedOut, "the wait got the record: $waited") },
            { assertNotEquals((appended as? ClaimStatus.Held)?.token, token, "under a fresh token, not the one nobody received") },
            { assertEquals<ClaimStatus?>(token?.let { ClaimStatus.Held(ada.bridge.self, it) }, atVoter, "held under the returned token") },
        )
        rig.close()
    }

    /**
     * A claim held under this session's identity that no release will ever free (here, one a
     * second client of the same identity made, as an exhausted late-win release leaves it) is
     * given back and claimed again: the wait returns the record under a fresh token.
     */
    @Test
    fun anUnreleasedClaimOfTheSessionIsReclaimedUnderAFreshToken() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        val target = rig.clickOn(ada)
        val stray = rig.claims.client(NodeId("stray")).claim(target, ada.bridge.self)

        val waited = ada.wait("s", timeoutSeconds = 30)
        val token = waited.record?.let { BoardActionSource.parseToken(it.string("token")) }
        assertAll(
            { assertIs<ClaimOutcome.Won>(stray, "rig: the stray claim holds the target as ada") },
            { assertEquals(false, waited.timedOut, "the wait got the record: $waited") },
            { assertNotEquals((stray as? ClaimOutcome.Won)?.token, token, "under a fresh token, not the stray claim's") },
            { assertEquals<ClaimStatus?>(token?.let { ClaimStatus.Held(ada.bridge.self, it) }, rig.voter.table.value.status(target), "held under the returned token") },
        )
        rig.close()
    }

    /**
     * A claim told it lost to this session, whose target reads free by the time the wait looks,
     * retries; it never passes the record. The verdict and the release that freed the target are
     * held back and delivered together, so the table is already free when the wait reads it.
     */
    @Test
    fun aLossToSelfOnAFreedTargetRetries() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        val target = rig.clickOn(ada)
        val stray = rig.claims.client(NodeId("stray"))
        val strayWin = stray.claim(target, ada.bridge.self)
        val hold = rig.claims.host.holdFor(ada.node())
        hold.hold()

        val wait = async { ada.wait("s", timeoutSeconds = 30) }
        val strayIndex = rig.voter.table.value.appliedIndex
        rig.voter.table.first { it.appliedIndex > strayIndex } // ada's claim, rejected: held as ada
        val released = stray.release(target, ada.bridge.self, (strayWin as ClaimOutcome.Won).token)
        val parked = hold.parkedSends
        hold.releaseAll()
        val waited = wait.await()
        val token = waited.record?.let { BoardActionSource.parseToken(it.string("token")) }
        assertAll(
            { assertTrue(released, "rig: the stray claim was released") },
            { assertTrue(parked > 0, "rig: the verdict was held back") },
            { assertEquals(false, waited.timedOut, "the wait retried and got the record: $waited") },
            { assertNotEquals(strayWin.token, token, "under its own token, not the stray one") },
        )
        rig.close()
    }

    /**
     * Two waits of one session on one surface: each click goes to exactly one of them, and the
     * one that missed the first click gets the second.
     */
    @Test
    fun twoWaitsOfOneSessionShareTheClicks() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        rig.clickOn(ada)
        val waits = List(2) { async { ada.wait("s", timeoutSeconds = 30) } }
        delay(5.seconds)
        rig.browserClient.mutate { it.appendRecord(iain, click(it, "s", "go")) }
        val results = waits.map { it.await() }
        val ids = results.mapNotNull { it.record?.string("recordId") }
        assertAll(
            { assertEquals(2, ids.size, "both waits got a click: $results") },
            { assertEquals(ids.distinct(), ids, "no click went to both") },
        )
        rig.close()
    }

    /**
     * Against a host that refuses every dial, the claims connection backs off as it should: it
     * never publishes a link, and over 60 s of the production backoff it opens a handful of
     * links, not one every 250 ms. A link counts as up only once it has folded a committed entry,
     * since `clusterClient` dials after it returns.
     */
    @Test
    fun aRefusingHostIsRedialledOnTheBackoff() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val refusing = object : Loom {
            override suspend fun weave(rendezvous: Rendezvous): Seam = throw java.net.ConnectException("refused (test)")
        }
        var nodes = 0
        val failures = mutableListOf<Throwable>()
        val connection = ClaimsConnection(
            scope = backgroundScope,
            nextNode = { NodeId("refused-${++nodes}") },
            open = { node, linkScope ->
                ClaimsLink.open(
                    node,
                    { refusing },
                    ClusterEndpoints(listOf(InMemoryTag(InMemoryClaimsHost.SESSION))),
                    linkScope,
                    rig.claims.raftConfig,
                    rig.claims.clock,
                    Random(nodes),
                )
            },
            onFailure = { failures += it },
            retryDelay = ClaimsConnection.jitteredBackoff(Random(42)),
        )
        val everUp = async { connection.link.first { it != null } }
        delay(60.seconds)
        val opened = connection.opened.value
        assertAll(
            { assertTrue(opened >= 3, "rig: the refused dials were retried: $opened opens, failures $failures") },
            { assertTrue(opened <= 40, "the backoff grew: $opened opens in 60 s") },
            { assertTrue(everUp.isActive, "no link was ever published") },
        )
        everUp.cancel()
        connection.close()
        rig.close()
    }

    /** A link whose dial never answers is never published, so the bridge says claims are offline. */
    @Test
    fun aLinkThatNeverConnectsIsNotOnline() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val hanging = object : Loom {
            override suspend fun weave(rendezvous: Rendezvous): Seam = awaitCancellation()
        }
        val connection = ClaimsConnection(
            scope = backgroundScope,
            nextNode = { NodeId("hanging") },
            open = { node, linkScope ->
                ClaimsLink.open(node, { hanging }, ClusterEndpoints(listOf(InMemoryTag(InMemoryClaimsHost.SESSION))), linkScope, rig.claims.raftConfig, rig.claims.clock, Random(1))
            },
            onFailure = { },
            retryDelay = ClaimsConnection.jitteredBackoff(Random(42)),
        )
        val everUp = async { connection.link.first { it != null } }
        delay(30.seconds)
        assertAll(
            { assertEquals(1, connection.opened.value, "rig: one link, still dialling") },
            { assertTrue(everUp.isActive, "a link that never connected was never published") },
        )
        everUp.cancel()
        connection.close()
        rig.close()
    }

    /**
     * The board mirrors every committed `Depart`, so a retirement is refused even while claims
     * are offline: the write names who, why, and the claim left orphaned, from the board's mirror.
     */
    @Test
    fun aRetirementTheBoardShowsIsRefusedWhileClaimsAreOffline() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val breaker = CompletableDeferred<Unit>()
        val ada = rig.session("ada", retryDelay = { 1.hours }) { n, linkScope ->
            if (n == 1) linkScope.launch { breaker.await(); throw IllegalStateException("link died (test)") }
        }
        rig.clickOn(ada)
        val recordId = ada.wait("s").record().string("recordId")
        breaker.complete(Unit)
        ada.connection.link.first { it == null }
        val old = ada.bridge.self
        rig.voter.depart(old, DepartReason.Crashed)
        ada.client.state.first { state -> state.departures().any { it.identity == old } }

        val refused = ada.call(BeeTools.OPEN, openArgs("t"))
        assertAll(
            { assertTrue(refused.isError == true && old.toString() in refused.text() && "Crashed" in refused.text(), "refused: ${refused.text()}") },
            { assertTrue("record:$recordId" in refused.text(), "names the orphaned claim, from the mirror: ${refused.text()}") },
            { assertEquals(null, ada.client.state.value.surfaceDoc("t"), "the write never landed") },
        )
        rig.close()
    }

    /** A `Depart` that didn't commit on a link that stays up is retried until it does, not tried once. */
    @Test
    fun aPendingDepartIsRetriedOnALiveLink() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        val hold = rig.claims.host.holdUpFor(ada.node())
        hold.hold()
        val old = ada.bridge.self

        ada.identity.retire(DepartReason.Cleared)
        val pendingWhileHeld = ada.connection.pendingDeparts.value
        // Past the flusher's first try on this link, which times out under the hold too: only a retry drains it.
        delay(10.seconds + 500.milliseconds)
        hold.releaseAll()
        val drained = withTimeoutOrNull(60.seconds) { ada.connection.pendingDeparts.first { it.isEmpty() } }
        assertAll(
            { assertEquals(listOf(old to DepartReason.Cleared), pendingWhileHeld, "rig: the Depart didn't commit in time") },
            { assertNotNull(drained, "the pending Depart was retried on the live link") },
            { assertEquals(DepartReason.Cleared, rig.voter.table.value.departReason(old)) },
        )
        rig.close()
    }

    /** A wait that fails after its claim won, with any exception, releases the claim. */
    @Test
    fun aWaitThatFailsAfterItsWinReleasesTheClaim() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        val target = rig.clickOn(ada)
        val source = BoardActionSource(ada.bridge) { throw IllegalStateException("handing over failed (test)") }

        val failure = runCatching { source.next("s", null, 30.seconds) }.exceptionOrNull()
        val freed = withTimeoutOrNull(10.seconds) { rig.voter.table.first { it.status(target) == ClaimStatus.Free } }
        assertAll(
            { assertIs<IllegalStateException>(failure, "rig: the wait failed after its win") },
            { assertNotNull(freed, "the claim nobody received was released") },
        )
        rig.close()
    }

    /**
     * A wait that finds the record in another wait's hands waits it out: when that wait gives the
     * claim back, this one gets the record under a token the voter still holds.
     */
    @Test
    fun aWaitOutlastsAnotherWaitThatGivesTheClaimBack() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        val target = rig.clickOn(ada)
        val entered = CompletableDeferred<Unit>()
        val first = launch { BoardActionSource(ada.bridge) { entered.complete(Unit); awaitCancellation() }.next("s", null, 60.seconds) }
        entered.await()

        val second = async { ada.wait("s", timeoutSeconds = 30) }
        delay(2.seconds)
        first.cancelAndJoin()
        val waited = second.await()
        val token = waited.record?.let { BoardActionSource.parseToken(it.string("token")) }
        delay(5.seconds) // any release still in flight lands
        val atVoter = rig.voter.table.value.status(target)
        assertAll(
            { assertEquals(false, waited.timedOut, "the second wait got the record: $waited") },
            { assertEquals<ClaimStatus?>(token?.let { ClaimStatus.Held(ada.bridge.self, it) }, atVoter, "under a token the voter still holds") },
        )
        rig.close()
    }

    /**
     * A wait cancelled after its win while claims are offline queues the release. Once a link is
     * back, a second wait may find the claim still held as this session before the release lands:
     * it ends with a token the voter still holds, never the one being given back.
     */
    @Test
    fun aClaimGivenBackOfflineEndsUnderALiveToken() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val breaker = CompletableDeferred<Unit>()
        val ada = rig.session("ada", retryDelay = { 5.seconds }) { n, linkScope ->
            if (n == 1) linkScope.launch { breaker.await(); throw IllegalStateException("link died (test)") }
        }
        val target = rig.clickOn(ada)
        val entered = CompletableDeferred<Unit>()
        val first = launch { BoardActionSource(ada.bridge) { entered.complete(Unit); awaitCancellation() }.next("s", null, 60.seconds) }
        entered.await()
        breaker.complete(Unit)
        ada.connection.link.first { it == null }
        first.cancelAndJoin()
        val queued = ada.connection.pendingReleases.value.size

        val waited = ada.wait("s", timeoutSeconds = 30)
        val token = waited.record?.let { BoardActionSource.parseToken(it.string("token")) }
        delay(5.seconds) // any release still queued lands
        val atVoter = rig.voter.table.value.status(target)
        assertAll(
            { assertEquals(1, queued, "rig: the release was queued while offline") },
            { assertEquals(false, waited.timedOut, "the second wait got the record: $waited") },
            { assertEquals<ClaimStatus?>(token?.let { ClaimStatus.Held(ada.bridge.self, it) }, atVoter, "under a token the voter still holds") },
        )
        rig.close()
    }

    /**
     * A give-back whose caller is cancelled while it's being proposed stays queued: a wait gives a
     * claim back on its own cancellable path, so a cancel there must never drop the release. The
     * release is held on its way up, and its caller cancelled; the connection still has it, and it
     * settles once the hold lets go.
     */
    @Test
    fun aGiveBackCancelledMidTryStaysQueued() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada", retryDelay = { 1.seconds })
        val target = rig.clickOn(ada)
        val stray = rig.claims.client(NodeId("stray")).claim(target, ada.bridge.self)
        val unreceived = ClaimsConnection.Unreceived(target, ada.bridge.self, checkNotNull((stray as? ClaimOutcome.Won)?.token))
        val hold = rig.claims.host.holdUpFor(ada.node())
        hold.hold()

        val giving = launch { ada.connection.releaseUnreceived(unreceived) }
        delay(1.seconds)
        val parked = hold.parkedSends
        giving.cancelAndJoin()
        val queued = ada.connection.pendingReleases.value
        hold.releaseAll()
        val drained = withTimeoutOrNull(30.seconds) { ada.connection.pendingReleases.first { it.isEmpty() } }
        assertAll(
            { assertTrue(parked > 0, "rig: the release was held on its way up") },
            { assertEquals(listOf(unreceived), queued, "the cancelled give-back stayed queued") },
            { assertNotNull(drained, "and settled once the hold let go") },
            { assertEquals(ClaimStatus.Free, rig.voter.table.value.status(target)) },
        )
        rig.close()
    }

    /**
     * A give-back that couldn't commit is proposed again, not dropped, when a later wait finds the
     * claim still held as this session. The first wait wins, then is cancelled while the link loses
     * its sends, so its release and the connection's first retry are lost; the connection's next
     * retry is an hour off. The second wait must give the claim back itself and claim it again,
     * and the queued release must settle. Without that, the record is held for no caller and every
     * wait on the surface times out.
     */
    @Test
    fun aGiveBackThatCouldNotCommitIsNeverStranded() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada", retryDelay = { 1.hours })
        val target = rig.clickOn(ada)
        val entered = CompletableDeferred<Unit>()
        val first = launch { BoardActionSource(ada.bridge) { entered.complete(Unit); awaitCancellation() }.next("s", null, 60.seconds) }
        entered.await()
        val given = rig.voter.table.value.status(target)
        val lossy = rig.claims.host.dropUpFor(ada.node())
        lossy.dropping = true
        first.cancelAndJoin()
        delay(15.seconds) // the connection's immediate retry is lost too, and times out
        lossy.dropping = false
        val queued = ada.connection.pendingReleases.value.size
        val atVoterBefore = rig.voter.table.value.status(target)

        val waited = ada.wait("s", timeoutSeconds = 30)
        val token = waited.record?.let { BoardActionSource.parseToken(it.string("token")) }
        assertAll(
            { assertIs<ClaimStatus.Held>(given, "rig: the first wait won the claim") },
            { assertEquals(1, queued, "rig: the give-back was queued, not committed") },
            { assertEquals(given, atVoterBefore, "rig: the voter still held it under the given-back token") },
            { assertEquals(false, waited.timedOut, "the second wait got the record: $waited") },
            { assertNotEquals((given as? ClaimStatus.Held)?.token, token, "under a fresh token") },
            { assertEquals<ClaimStatus?>(token?.let { ClaimStatus.Held(ada.bridge.self, it) }, rig.voter.table.value.status(target), "held under the returned token") },
            { assertEquals(emptyList(), ada.connection.pendingReleases.value, "the queued give-back settled") },
        )
        rig.close()
    }

    /**
     * A release still on its way to the voter never frees a record a wait has returned, so a
     * second session never gets it too. A second claims client under ada's identity stands in for
     * an earlier link of ada's: it holds the record as ada, and its release of that claim is held
     * on its way up. Ada's wait gives that claim back and claims the record again, under a fresh
     * token; when the stale release lands it's rejected, and bo's wait passes the record.
     */
    @Test
    fun aReleaseInFlightNeverFreesARecordTheWaitReturned() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        val bo = rig.session("bo")
        val target = rig.clickOn(ada)
        bo.client.state.first { it.records("s").isNotEmpty() }
        val earlier = rig.claims.client(NodeId("earlier"))
        val earlierWin = earlier.claim(target, ada.bridge.self)
        val earlierToken = (earlierWin as? ClaimOutcome.Won)?.token
        val hold = rig.claims.host.holdUpFor(NodeId("earlier"))
        hold.hold()
        val stale = async { earlier.release(target, ada.bridge.self, checkNotNull(earlierToken), timeout = 1.hours) }
        delay(1.seconds)
        val parked = hold.parkedSends

        val adaGot = ada.wait("s", timeoutSeconds = 30)
        val adaToken = adaGot.record?.let { BoardActionSource.parseToken(it.string("token")) }
        hold.releaseAll()
        val staleFreed = stale.await()
        val boGot = bo.wait("s", timeoutSeconds = 10)
        assertAll(
            { assertIs<ClaimOutcome.Won>(earlierWin, "rig: the earlier claim holds the record as ada") },
            { assertTrue(parked > 0, "rig: the earlier release was held on its way up") },
            { assertEquals(false, adaGot.timedOut, "ada's wait got the record: $adaGot") },
            { assertNotEquals(earlierToken, adaToken, "under a fresh token, not the one being released") },
            { assertEquals(false, staleFreed, "the stale release was rejected") },
            { assertEquals(null, boGot.record, "bo never got the record ada's wait returned: $boGot") },
            { assertEquals<ClaimStatus?>(adaToken?.let { ClaimStatus.Held(ada.bridge.self, it) }, rig.voter.table.value.status(target), "the voter still holds it for ada's caller") },
        )
        rig.close()
    }

    private data class Waited(val record: JsonObject?, val timedOut: Boolean, val cursor: String) {
        fun record(): JsonObject = checkNotNull(record) { "the wait timed out" }
    }

    private suspend fun Session.wait(surfaceId: String, cursor: String? = null, timeoutSeconds: Int = 30): Waited {
        val args = buildMap<String, Any?> {
            put("surfaceId", surfaceId)
            cursor?.let { put("cursor", it) }
            put("timeoutSeconds", timeoutSeconds)
        }
        val result = call(WaitTool.WAIT, args)
        check(result.isError != true) { "the wait was refused: ${result.text()}" }
        val json = result.json()
        return Waited(json["record"]?.jsonObject, json.getValue("timedOut").jsonPrimitive.boolean, json.getValue("cursor").jsonPrimitive.content)
    }

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
}
