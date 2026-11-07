package us.tractat.bee.host

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/** Runs the host: `bee-host [--port <n>] [--data-dir <dir>]`. */
fun main(args: Array<String>) {
    val config = HostConfig.fromArgs(args.toList(), home = Path.of(System.getProperty("user.home")))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val host = runBlocking { BeeHost.start(scope, config) }
    Runtime.getRuntime().addShutdownHook(
        Thread {
            host.close()
            scope.cancel()
        },
    )
    logger.info { "bee host on http://${config.bind}:${host.port}/ (data in ${config.dataDir})" }
    runBlocking { awaitCancellation() }
}
