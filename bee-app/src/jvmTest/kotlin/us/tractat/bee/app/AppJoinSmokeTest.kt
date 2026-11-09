@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package us.tractat.bee.app

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import us.tractat.bee.core.HostAddress
import us.tractat.bee.host.BeeHost
import us.tractat.bee.host.HostConfig
import us.tractat.kuilt.crdt.ReplicaId
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * The desktop app's own join, over real sockets: a [BeeHost] on an ephemeral loopback port, an
 * agent's replica that opens a surface there, and the app's replica, both made by [joinBoard] from
 * the link a person would type. The app's [Board] then shows the title. Runs only with
 * `-Pbee.realsocket.tests=true`.
 */
class AppJoinSmokeTest {
    @Test
    fun theAppJoinsARealHostByItsLinkAndShowsTheSurfaceAnAgentOpened() {
        assumeTrue(System.getProperty("bee.realsocket.tests") == "true")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = runBlocking { BeeHost.start(scope, HostConfig(port = 0, dataDir = Files.createTempDirectory("bee-app-smoke-"))) }
        val http = appHttpClient()
        try {
            val link = HostAddress.parse("http://127.0.0.1:${host.port}/")
            val clock = { Clock.System.now() }
            val agentId = ReplicaId("agent-smoke")
            val agent = joinBoard(link, http, scope, agentId, clock, Random.Default)
            val app = joinBoard(link, http, scope, ReplicaId("app-smoke"), clock, Random.Default)
            runBlocking {
                withTimeout(10.seconds) { agent.online.first { it } }
                agent.mutate { it.open(agentId, "plan", "Plan", clock()) }
                withTimeout(10.seconds) { app.state.first { it.openSurfaceTitles() == listOf("Plan") } }
            }
            runComposeUiTest {
                setContent { Board(app) }
                onAllNodesWithTag(SURFACE_TITLE_TAG)[0].assertTextEquals("Plan")
            }
            runBlocking {
                agent.close()
                app.close()
            }
        } finally {
            http.close()
            host.close()
            scope.cancel()
        }
    }
}
