package us.tractat.bee.host

import java.nio.file.Path

/** Who can reach the host: this machine only, or the local network too (spec §5.4, §5.7). */
enum class Reach(val bind: String) {
    /** The default: only this machine's own browsers and sessions. */
    Loopback("127.0.0.1"),

    /** `--lan`: every interface. A device off this machine needs a key, from pairing or a join link. */
    Lan("0.0.0.0"),
}

/**
 * Where the host listens and keeps its data (plan, "Decisions": port 8233, "BEE" on a keypad,
 * data in `~/.bee/host/`). It binds loopback only unless [reach] is [Reach.Lan].
 *
 * @param pairingBudget how many devices off this machine may ask to join in an hour, all together
 */
data class HostConfig(
    val port: Int = DEFAULT_PORT,
    val reach: Reach = Reach.Loopback,
    val dataDir: Path,
    val pairingBudget: PairingBudget = PairingBudget.DEFAULT,
) {
    /** The address the host binds, from [reach]. */
    val bind: String get() = reach.bind

    companion object {
        const val DEFAULT_PORT: Int = 8233

        private const val LAN_FLAG = "--lan"
        private const val BUDGET_FLAG = "--join-requests-per-hour"

        /**
         * Reads `--port <n>`, `--data-dir <dir>`, `--join-requests-per-hour <n>` and `--lan`, the
         * form Playwright starts the host with (`--lan` aside). Without `--data-dir` the data lives
         * in `<home>/.bee/host`; without `--join-requests-per-hour`, it is
         * [PairingBudget.DEFAULT_PER_HOUR].
         *
         * @throws IllegalArgumentException on an unknown or repeated flag, a flag without a value, a
         *   bad port, or a budget below one
         */
        fun fromArgs(args: List<String>, home: Path): HostConfig {
            require(args.count { it == LAN_FLAG } <= 1) { "Repeated flags: $LAN_FLAG" }
            val valued = args - LAN_FLAG
            require(valued.size % 2 == 0) { "Every flag but $LAN_FLAG takes a value: ${args.joinToString(" ")}" }
            val pairs = valued.chunked(2).map { (flag, value) -> flag to value }
            val repeated = pairs.groupBy { it.first }.filterValues { it.size > 1 }.keys
            require(repeated.isEmpty()) { "Repeated flags: ${repeated.joinToString()}" }
            val flags = pairs.toMap()
            val unknown = flags.keys - setOf("--port", "--data-dir", BUDGET_FLAG)
            require(unknown.isEmpty()) {
                "Unknown flags: ${unknown.joinToString()}. bee-host takes --port <n>, --data-dir <dir>, $BUDGET_FLAG <n> and $LAN_FLAG."
            }
            val budget = flags[BUDGET_FLAG]?.let { PairingBudget(requireNotNull(it.toIntOrNull()) { "Not a number: $it" }) }
            val port = flags["--port"]?.let { requireNotNull(it.toIntOrNull()?.takeIf { p -> p in 0..65535 }) { "Not a port: $it" } }
            return HostConfig(
                port = port ?: DEFAULT_PORT,
                reach = if (LAN_FLAG in args) Reach.Lan else Reach.Loopback,
                dataDir = flags["--data-dir"]?.let(Path::of) ?: home.resolve(".bee").resolve("host"),
                pairingBudget = budget ?: PairingBudget.DEFAULT,
            )
        }
    }
}
