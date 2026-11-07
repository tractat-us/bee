package us.tractat.bee.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import us.tractat.bee.core.DepartReason
import us.tractat.bee.core.PeerIdentity
import us.tractat.kuilt.test.assertAll
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

/**
 * The `/clear` channel is a loopback HTTP endpoint by design (A8), so these tests bind an ephemeral
 * loopback port. Nothing in them waits on time, so they stay deterministic in the required gate.
 */
class ClearControlTest {
    private val runDir = Files.createTempDirectory("bee-run")
    private val http = HttpClient.newHttpClient()

    @Test
    fun postRetireRetiresWithCleared() {
        val reported = mutableListOf<Pair<PeerIdentity, DepartReason>>()
        val identity = BridgeIdentity("bee", Random(1)) { old, reason -> reported += old to reason }
        val before = identity.current.value

        val (status, after) = ClearControl(identity, runDir, claudePid = 4242).use { clear ->
            clear.start()
            val response = post(clear.port, ClearControl.RETIRE_PATH)
            response.statusCode() to identity.current.value
        }

        assertAll(
            { assertEquals(200, status) },
            { assertEquals(listOf(before to DepartReason.Cleared), reported) },
            { assertNotEquals(before, after) },
        )
    }

    @Test
    fun runFileNamesThePortAndIsRemovedOnClose() {
        val identity = BridgeIdentity("bee", Random(2)) { _, _ -> }
        val clear = ClearControl(identity, runDir, claudePid = 4242)
        val runFile = runDir.resolve("4242.json")

        clear.start()
        val written = Json.parseToJsonElement(runFile.readText()).jsonObject["port"]?.jsonPrimitive?.int
        clear.close()

        assertAll(
            { assertEquals(clear.port, written) },
            { assertFalse(runFile.exists(), "the run file outlived close") },
        )
    }

    @Test
    fun onlyPostRetires() {
        val reported = mutableListOf<PeerIdentity>()
        val identity = BridgeIdentity("bee", Random(3)) { old, _ -> reported += old }
        val status = ClearControl(identity, runDir, claudePid = 4242).use { clear ->
            clear.start()
            http.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:${clear.port}${ClearControl.RETIRE_PATH}")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            ).statusCode()
        }
        assertAll(
            { assertEquals(405, status) },
            { assertEquals(emptyList(), reported) },
        )
    }

    private fun post(port: Int, path: String): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).POST(HttpRequest.BodyPublishers.noBody()).build(),
        HttpResponse.BodyHandlers.ofString(),
    )
}
