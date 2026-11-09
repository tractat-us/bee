package us.tractat.bee.telemetry

import java.nio.file.Path
import java.time.ZoneId
import kotlin.system.exitProcess
import kotlin.time.Clock
import kotlin.time.Duration

private const val USAGE = """bee-telemetry [--home <dir>] [--dir <telemetry dir>]... [--role host|bridge|page] [--peer <name>] [--since <10m>] [--tail <n>] [--json]

Prints every bee process's telemetry under a bee home as one timeline, oldest first: the host's
(<home>/host/telemetry) and each bridge's (<home>/telemetry/bridge-*). --dir reads only the
directories it names. --home defaults to ${'$'}BEE_HOME, else ~/.bee. --peer matches part of a
peer's name, or a bridge's whole session name. --since takes a duration such as 90s, 10m or 2h.
It only reads: each buffer is copied first, so a running host or bridge is never touched."""

/** Reads the merged timeline ([timeline]) and prints it, one line per entry. */
fun main(args: Array<String>) {
    val options = try {
        TimelineOptions.parse(args.toList(), env = System::getenv, clock = Clock.System)
    } catch (wrong: IllegalArgumentException) {
        System.err.println("${wrong.message}\n\n$USAGE")
        exitProcess(2)
    }
    if (options.help) {
        println(USAGE)
        return
    }
    val scratch = scratchDir()
    val dirs = options.dirs.ifEmpty { telemetryDirs(options.home) }
    if (dirs.isEmpty()) {
        System.err.println("No telemetry under ${options.home}. Is BEE_HOME right, or BEE_TELEMETRY=off?")
        exitProcess(1)
    }
    val entries = timeline(dirs.flatMap { readBuffer(it, scratch) }, options.filter).let { all -> options.tail?.let(all::takeLast) ?: all }
    val zone = ZoneId.systemDefault()
    entries.forEach { println(if (options.json) it.json() else it.text(zone)) }
}

/** `bee-telemetry`'s command line. */
data class TimelineOptions(
    val home: Path,
    val dirs: List<Path>,
    val filter: TimelineFilter,
    val tail: Int?,
    val json: Boolean,
    val help: Boolean,
) {
    companion object {
        private val VALUED = setOf("--home", "--dir", "--role", "--peer", "--since", "--tail")

        /** @throws IllegalArgumentException on an unknown flag, a flag without its value, or a bad duration or count */
        fun parse(args: List<String>, env: (String) -> String?, clock: Clock): TimelineOptions {
            val values = mutableMapOf<String, MutableList<String>>()
            var json = false
            var help = false
            var rest = args
            while (rest.isNotEmpty()) {
                val flag = rest.first()
                when (flag) {
                    "--json" -> json = true
                    "--help", "-h" -> help = true
                    in VALUED -> {
                        require(rest.size > 1) { "$flag takes a value." }
                        values.getOrPut(flag) { mutableListOf() } += rest[1]
                        rest = rest.drop(1)
                    }
                    else -> throw IllegalArgumentException("Unknown flag: $flag")
                }
                rest = rest.drop(1)
            }
            val home = values["--home"]?.last() ?: env("BEE_HOME")?.takeIf { it.isNotBlank() } ?: "${System.getProperty("user.home")}/.bee"
            val since = values["--since"]?.last()?.let { text ->
                val ago = requireNotNull(Duration.parseOrNull(text)) { "Not a duration: $text (try 10m)" }
                (clock.now() - ago).epochNanos()
            }
            return TimelineOptions(
                home = Path.of(home),
                dirs = values["--dir"].orEmpty().map(Path::of),
                filter = TimelineFilter(role = values["--role"]?.last(), peer = values["--peer"]?.last(), sinceEpochNanos = since),
                tail = values["--tail"]?.last()?.let { requireNotNull(it.toIntOrNull()?.takeIf { n -> n > 0 }) { "Not a count: $it" } },
                json = json,
                help = help,
            )
        }
    }
}
