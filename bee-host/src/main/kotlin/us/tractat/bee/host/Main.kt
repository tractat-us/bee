package us.tractat.bee.host

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import us.tractat.bee.core.PairingWire
import us.tractat.bee.telemetry.Attr
import us.tractat.bee.telemetry.ProcessTelemetry
import us.tractat.bee.telemetry.Role
import java.nio.file.Path
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}

/** Runs the host: `bee-host [--port <n>] [--data-dir <dir>] [--join-requests-per-hour <n>] [--host-name <name>] [--lan] [--new-link] [--forget-devices]`. */
fun main(args: Array<String>) {
    val config = HostConfig.fromArgs(args.toList(), home = Path.of(System.getProperty("user.home")))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val host = try {
        runBlocking { BeeHost.start(scope, config, ::hostTelemetry) }
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
    if (config.reach == Reach.Lan) {
        val lan = "http://${lanName()}:${host.port}/"
        // The join link's key admits whoever holds the link, across restarts, until --new-link or --forget-devices.
        val link = "$lan?${PairingWire.KEY_PARAM}=${host.pairing.joinKey.value}"
        logger.info { "On your network: $lan. A device that opens it pairs with a code. Join link, no code: $link" }
    }
    runBlocking { awaitCancellation() }
}

/**
 * The host's telemetry, under its data dir's `telemetry/`: every log line and every board change,
 * each stamped as the host's. `bee.peer` is the steady `host`; `BeeHost` adds the board and its
 * replica for this boot. Off under `BEE_TELEMETRY=off`.
 */
private fun hostTelemetry(dataDir: Path): ProcessTelemetry = ProcessTelemetry.open(
    dirs = listOf(dataDir.resolve("telemetry")),
    resource = mapOf(
        Attr.SERVICE to "bee-host",
        Attr.ROLE to Role.HOST,
        Attr.PEER to Role.HOST,
        Attr.PID to ProcessHandle.current().pid().toString(),
    ),
)
