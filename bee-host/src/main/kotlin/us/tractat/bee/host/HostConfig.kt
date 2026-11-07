package us.tractat.bee.host

import java.nio.file.Path

/**
 * Where the host listens and keeps its data (plan, "Decisions": port 8233, "BEE" on a keypad,
 * data in `~/.bee/host/`). It binds loopback only until Task 10's `--lan`.
 */
data class HostConfig(
    val port: Int = DEFAULT_PORT,
    val bind: String = LOOPBACK,
    val dataDir: Path,
) {
    companion object {
        const val DEFAULT_PORT: Int = 8233
        const val LOOPBACK: String = "127.0.0.1"

        /**
         * Reads `--port <n>` and `--data-dir <dir>`, the form Playwright starts the host with.
         * Without `--data-dir` the data lives in `<home>/.bee/host`.
         *
         * @throws IllegalArgumentException on an unknown or repeated flag, a flag without a value, or a bad port
         */
        fun fromArgs(args: List<String>, home: Path): HostConfig {
            require(args.size % 2 == 0) { "Every flag takes a value: ${args.joinToString(" ")}" }
            val pairs = args.chunked(2).map { (flag, value) -> flag to value }
            val repeated = pairs.groupBy { it.first }.filterValues { it.size > 1 }.keys
            require(repeated.isEmpty()) { "Repeated flags: ${repeated.joinToString()}" }
            val flags = pairs.toMap()
            val unknown = flags.keys - setOf("--port", "--data-dir")
            require(unknown.isEmpty()) { "Unknown flags: ${unknown.joinToString()}. bee-host takes --port <n> and --data-dir <dir>." }
            val port = flags["--port"]?.let { requireNotNull(it.toIntOrNull()?.takeIf { p -> p in 0..65535 }) { "Not a port: $it" } }
            return HostConfig(
                port = port ?: DEFAULT_PORT,
                dataDir = flags["--data-dir"]?.let(Path::of) ?: home.resolve(".bee").resolve("host"),
            )
        }
    }
}
