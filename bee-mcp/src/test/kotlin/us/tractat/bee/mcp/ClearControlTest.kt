package us.tractat.bee.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import us.tractat.bee.core.DepartReason
import us.tractat.bee.core.PeerIdentity
import us.tractat.kuilt.test.assertAll
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import java.time.Duration as JavaDuration

/**
 * The `/clear` channel is a loopback HTTP endpoint by design (A8), so these tests bind an ephemeral
 * loopback port. Every request has a 5 s timeout; only the slow-retire test waits on the clock, and
 * it asserts an upper bound, never an exact time.
 */
class ClearControlTest {
    private val runDir: Path = Files.createTempDirectory("bee-run")
    private val http: HttpClient = HttpClient.newHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun cleanUp() {
        scope.cancel()
        http.close()
        runDir.deleteRecursively()
    }

    @Test
    fun postRetireRetiresWithCleared() {
        val reported = mutableListOf<Pair<PeerIdentity, DepartReason>>()
        val identity = BridgeIdentity("bee", Random(1)) { old, reason -> reported += old to reason }
        val before = identity.current.value

        val response = control(identity).use { clear ->
            clear.start()
            post(clear.port, bearer = runFile(clear).token)
        }

        assertAll(
            { assertEquals(200, response.statusCode()) },
            { assertEquals(listOf(before to DepartReason.Cleared), reported) },
            { assertNotEquals(before, identity.current.value) },
            { assertTrue(before.toString() in response.body(), "the response doesn't name the retired identity") },
        )
    }

    @Test
    fun runFileNamesThePortAndIsRemovedOnClose() {
        val clear = control(quietIdentity())
        val file = runDir.resolve("4242.json")

        clear.start()
        val written = runFile(clear)
        val mode = PosixFilePermissions.toString(Files.getPosixFilePermissions(file))
        clear.close()
        clear.close() // the shutdown hook closes again; that's harmless

        assertAll(
            { assertEquals(clear.port, written.port) },
            { assertTrue(Regex("[0-9a-f]{64}").matches(written.token), "token was ${written.token}") },
            { assertEquals(ProcessHandle.current().pid(), written.pid) },
            { assertEquals("rw-------", mode) },
            { assertFalse(file.exists(), "the run file outlived close") },
        )
    }

    @Test
    fun runDirIsMadeOwnerOnlyEvenWhenItAlreadyExists() {
        Files.setPosixFilePermissions(runDir, PosixFilePermissions.fromString("rwxr-xr-x"))

        control(quietIdentity()).use { it.start() }

        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(runDir)))
    }

    @Test
    fun closeLeavesALaterBridgesRunFileAlone() {
        val first = control(quietIdentity())
        val second = control(quietIdentity())
        first.start()
        second.start() // the same claude, so the same file, now naming the second bridge

        first.close()
        val afterFirst = runFile(second)
        second.close()

        assertAll(
            { assertEquals(second.port, afterFirst.port) },
            { assertFalse(runDir.resolve("4242.json").exists(), "the second bridge's close left its file") },
        )
    }

    @Test
    fun aMissingOrWrongTokenIsRefused() {
        val reported = mutableListOf<PeerIdentity>()
        val identity = BridgeIdentity("bee", Random(4)) { old, _ -> reported += old }

        val (missing, wrong) = control(identity).use { clear ->
            clear.start()
            post(clear.port, bearer = null).statusCode() to post(clear.port, bearer = "0".repeat(64)).statusCode()
        }

        assertAll(
            { assertEquals(401, missing) },
            { assertEquals(401, wrong) },
            { assertEquals(emptyList(), reported) },
        )
    }

    @Test
    fun onlyPostRetires() {
        val reported = mutableListOf<PeerIdentity>()
        val identity = BridgeIdentity("bee", Random(3)) { old, _ -> reported += old }
        val status = control(identity).use { clear ->
            clear.start()
            http.send(
                request(clear.port, runFile(clear).token).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            ).statusCode()
        }
        assertAll(
            { assertEquals(405, status) },
            { assertEquals(emptyList(), reported) },
        )
    }

    /** A Depart that never commits (Task 7's Raft unreachable) must stall neither the hook nor shutdown. */
    @Test
    fun aRetireThatHangsIsAcceptedAndDoesNotStallClose() {
        val identity = BridgeIdentity("bee", Random(5)) { _, _ -> awaitCancellation() }
        val before = identity.current.value
        val clear = control(identity)
        clear.start()

        val response = post(clear.port, bearer = runFile(clear).token)
        val closing = Thread(clear::close).apply { start() }
        closing.join(5_000)

        assertAll(
            { assertEquals(202, response.statusCode()) },
            { assertNotEquals(before, identity.current.value) },
            { assertFalse(closing.isAlive, "close waited on the hung retire") },
        )
    }

    private fun control(identity: BridgeIdentity) =
        ClearControl(identity, scope, runDir, claudePid = 4242, answerWithin = 200.milliseconds)

    private fun quietIdentity() = BridgeIdentity("bee", Random(2)) { _, _ -> }

    private data class RunFile(val port: Int, val token: String, val pid: Long)

    private fun runFile(clear: ClearControl): RunFile {
        val json: JsonObject = Json.parseToJsonElement(checkNotNull(clear.runFile).readText()).jsonObject
        return RunFile(
            port = json.getValue("port").jsonPrimitive.int,
            token = json.getValue("token").jsonPrimitive.content,
            pid = json.getValue("pid").jsonPrimitive.long,
        )
    }

    private fun request(port: Int, bearer: String?): HttpRequest.Builder =
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port${ClearControl.RETIRE_PATH}"))
            .timeout(JavaDuration.ofSeconds(5))
            .apply { bearer?.let { header("Authorization", "Bearer $it") } }

    private fun post(port: Int, bearer: String?): HttpResponse<String> = http.send(
        request(port, bearer).POST(HttpRequest.BodyPublishers.noBody()).build(),
        HttpResponse.BodyHandlers.ofString(),
    )
}
