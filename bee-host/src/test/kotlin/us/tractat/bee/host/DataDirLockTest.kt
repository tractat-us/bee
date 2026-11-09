package us.tractat.bee.host

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import us.tractat.bee.core.HostAddress
import us.tractat.bee.core.askBoardId
import us.tractat.kuilt.test.assertAll
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * One host per data dir ([DataDirLock]): real [BeeHost]s on one temp dir. The second asks for a
 * new join link, so a host that got as far as its keys would leave a different `keys` file behind;
 * it asks for an ephemeral port, so nothing but the lock stops it starting.
 */
class DataDirLockTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir = Files.createTempDirectory("data-dir-lock-")
    private val http = HttpClient(CIO)

    @AfterTest
    fun stop() {
        http.close()
        scope.cancel()
        dir.toFile().deleteRecursively()
    }

    @Test
    fun aSecondHostOnAHeldDataDirRefusesBeforeTouchingIt() = runBlocking {
        val first = BeeHost.start(scope, HostConfig(port = 0, dataDir = dir))
        val keys = Files.readAllBytes(dir.resolve(KeyFile.NAME))
        val refused = assertFailsWith<DataDirInUse> {
            BeeHost.start(scope, HostConfig(port = 0, dataDir = dir, keyReset = KeyReset.NewLink)).close()
        }
        val served = http.askBoardId(HostAddress.parse("http://127.0.0.1:${first.port}"))
        val keysAfter = Files.readAllBytes(dir.resolve(KeyFile.NAME))
        first.close()
        assertAll(
            { assertContains(refused.message.orEmpty(), "Another bee host (pid ${ProcessHandle.current().pid()}) is using the data dir $dir") },
            { assertContentEquals(keys, keysAfter, "the first host's keys file") },
            { assertEquals(first.boardId, served, "the first host still serves its board") },
        )
    }

    @Test
    fun aClosedHostLetsTheNextOneOpenItsDataDir() = runBlocking {
        val first = BeeHost.start(scope, HostConfig(port = 0, dataDir = dir))
        val board = first.boardId
        first.close()
        val next = BeeHost.start(scope, HostConfig(port = 0, dataDir = dir))
        val kept = next.boardId
        next.close()
        assertEquals(board, kept)
    }
}
