package us.tractat.bee.host

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import us.tractat.bee.core.PairingWire
import java.nio.file.Path
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}

/** Runs the host: `bee-host [--port <n>] [--data-dir <dir>] [--join-requests-per-hour <n>] [--host-name <name>] [--lan | --public-url <url> [--client-ip-header <name>]] [--new-link] [--forget-devices]`. */
fun main(args: Array<String>) {
    val config = try {
        HostConfig.fromArgs(args.toList(), home = Path.of(System.getProperty("user.home")), environment = System::getenv)
    } catch (refused: IllegalArgumentException) {
        logger.error { refused.message }
        exitProcess(2)
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val host = try {
        runBlocking { BeeHost.start(scope, config) }
    } catch (busy: DataDirInUse) {
        logger.error { busy.message }
        exitProcess(1)
    }
    Runtime.getRuntime().addShutdownHook(
        Thread {
            host.close()
            scope.cancel()
        },
    )
    logger.info { "bee host on http://${config.bind}:${host.port}/ (data in ${config.dataDir})" }
    when (val reach = config.reach) {
        Reach.Loopback -> {}
        Reach.Lan -> {
            val lan = "http://${lanName()}:${host.port}/"
            // The join link's key admits whoever holds the link, across restarts, until --new-link or --forget-devices.
            val link = "$lan?${PairingWire.KEY_PARAM}=${host.pairing.joinKey.value}"
            logger.info { "On your network: $lan. A device that opens it pairs with a code. Join link, no code: $link" }
        }
        // A hosted board's log goes to the platform's log store: no key goes in it.
        is Reach.Public -> logger.info { "Served at ${reach.url}, behind its proxy. A device that opens it pairs with a code." }
    }
    runBlocking { awaitCancellation() }
}
