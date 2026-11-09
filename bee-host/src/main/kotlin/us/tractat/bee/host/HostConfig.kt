package us.tractat.bee.host

import us.tractat.bee.mcp.HttpBridges
import java.net.InetAddress
import java.nio.file.Path
import kotlin.time.Duration

/**
 * Who can reach the host: this machine only, the local network too (spec §5.4, §5.7), or anyone,
 * through one trusted proxy (bee#145).
 */
sealed interface Reach {
    /** The address the host binds. */
    val bind: String

    /** The names a host with this reach answers to, beyond `localhost` and its addresses ([SiteRule]). */
    fun names(): Set<String>

    /** The public URLs a host with this reach is served at, through its proxy ([SiteRule]). */
    fun publicUrls(): Set<PublicUrl>

    /** Whether a host with this reach has a join link to give its owner's page ([inviteEndpoint]). */
    fun invitation(): Invitation

    /** Where each call to a host with this reach came from ([mountEndpoints]). */
    fun peers(): PeerAddress

    /** The default: only this machine's own browsers and sessions. */
    data object Loopback : Reach {
        override val bind: String = "127.0.0.1"

        override fun names(): Set<String> = emptySet()

        override fun publicUrls(): Set<PublicUrl> = emptySet()

        override fun invitation(): Invitation = Invitation.Closed

        override fun peers(): PeerAddress = PeerAddress.DIRECT
    }

    /** `--lan`: every interface. A device off this machine needs a key, from pairing or a join link. */
    data object Lan : Reach {
        override val bind: String = ALL_INTERFACES

        override fun names(): Set<String> = setOf(lanName())

        override fun publicUrls(): Set<PublicUrl> = emptySet()

        override fun invitation(): Invitation = Invitation.Open(lanName(), ::lanAddresses)

        override fun peers(): PeerAddress = PeerAddress.DIRECT
    }

    /**
     * `--public-url <url>`: every interface, behind one trusted TLS-terminating proxy that dials the
     * machine's private address (Fly.io's). A browser names the host by [url], and each call's
     * caller is the one the proxy names ([PeerAddress.FORWARDED]).
     */
    data class Public(val url: PublicUrl) : Reach {
        override val bind: String = ALL_INTERFACES

        override fun names(): Set<String> = emptySet()

        override fun publicUrls(): Set<PublicUrl> = setOf(url)

        override fun invitation(): Invitation = Invitation.Public(url)

        override fun peers(): PeerAddress = PeerAddress.FORWARDED
    }
}

private const val ALL_INTERFACES = "0.0.0.0"

/** This machine's name on the local network: its host name, as mDNS serves it (`<name>.local`). */
fun lanName(): String = InetAddress.getLocalHost().hostName.let { if ('.' in it) it else "$it.local" }

/**
 * Where the host listens and keeps its data (plan, "Decisions": port 8233, "BEE" on a keypad,
 * data in `~/.bee/host/`). It binds loopback only unless [reach] is [Reach.Lan].
 *
 * @param pairingBudget how many devices off this machine may ask to join in an hour, all together
 * @param hostNames names the host answers to beyond those [reach] gives it ([SiteRule]): one the
 *   machine goes by that bee can't find itself, or a test's
 * @param keyReset what this boot does to the keys kept in [dataDir] ([HostKeys]): by default, nothing
 * @param mcpIdleAfter how long an MCP session over HTTP may idle before its bridge departs
 *   ([HttpBridges.IDLE_AFTER]); not a flag, a test's
 * @param mcpMaxSessions how many MCP sessions over HTTP may have a bridge at once
 *   ([HttpBridges.MAX_SESSIONS]); not a flag, a test's
 */
data class HostConfig(
    val port: Int = DEFAULT_PORT,
    val reach: Reach = Reach.Loopback,
    val dataDir: Path,
    val pairingBudget: PairingBudget = PairingBudget.DEFAULT,
    val hostNames: Set<String> = emptySet(),
    val keyReset: KeyReset = KeyReset.Keep,
    val mcpIdleAfter: Duration = HttpBridges.IDLE_AFTER,
    val mcpMaxSessions: Int = HttpBridges.MAX_SESSIONS,
) {
    /** The address the host binds, from [reach]. */
    val bind: String get() = reach.bind

    /** Which calls come from the host's own site: by [reach]'s names and [hostNames]. */
    fun siteRule(): SiteRule = SiteRule(reach.names() + hostNames)

    companion object {
        const val DEFAULT_PORT: Int = 8233

        private const val LAN_FLAG = "--lan"
        private const val NEW_LINK_FLAG = "--new-link"
        private const val FORGET_FLAG = "--forget-devices"
        private val SWITCHES = setOf(LAN_FLAG, NEW_LINK_FLAG, FORGET_FLAG)
        private const val BUDGET_FLAG = "--join-requests-per-hour"
        private const val NAME_FLAG = "--host-name"

        /**
         * Reads `--port <n>`, `--data-dir <dir>`, `--join-requests-per-hour <n>`, `--host-name <name>`
         * and `--lan`, the form Playwright starts the host with (`--lan` aside). Without
         * `--data-dir` the data lives in `<home>/.bee/host`; without `--join-requests-per-hour`, it
         * is [PairingBudget.DEFAULT_PER_HOUR]. `--new-link` is [KeyReset.NewLink] and
         * `--forget-devices` is [KeyReset.ForgetDevices], which mints a new link too, so the two
         * together are `--forget-devices`.
         *
         * @throws IllegalArgumentException on an unknown or repeated flag, a flag without a value, a
         *   bad port, or a budget below one
         */
        fun fromArgs(args: List<String>, home: Path): HostConfig {
            val repeatedSwitches = SWITCHES.filter { switch -> args.count { it == switch } > 1 }
            require(repeatedSwitches.isEmpty()) { "Repeated flags: ${repeatedSwitches.joinToString()}" }
            val valued = args.filterNot { it in SWITCHES }
            require(valued.size % 2 == 0) { "Every flag but ${SWITCHES.joinToString()} takes a value: ${args.joinToString(" ")}" }
            val pairs = valued.chunked(2).map { (flag, value) -> flag to value }
            val repeated = pairs.groupBy { it.first }.filterValues { it.size > 1 }.keys
            require(repeated.isEmpty()) { "Repeated flags: ${repeated.joinToString()}" }
            val flags = pairs.toMap()
            val unknown = flags.keys - setOf("--port", "--data-dir", BUDGET_FLAG, NAME_FLAG)
            require(unknown.isEmpty()) {
                "Unknown flags: ${unknown.joinToString()}. bee-host takes --port <n>, --data-dir <dir>, $BUDGET_FLAG <n>, $NAME_FLAG <name>, " +
                    "$LAN_FLAG, $NEW_LINK_FLAG and $FORGET_FLAG."
            }
            val budget = flags[BUDGET_FLAG]?.let { PairingBudget(requireNotNull(it.toIntOrNull()) { "Not a number: $it" }) }
            val port = flags["--port"]?.let { requireNotNull(it.toIntOrNull()?.takeIf { p -> p in 0..65535 }) { "Not a port: $it" } }
            return HostConfig(
                port = port ?: DEFAULT_PORT,
                reach = if (LAN_FLAG in args) Reach.Lan else Reach.Loopback,
                dataDir = flags["--data-dir"]?.let(Path::of) ?: home.resolve(".bee").resolve("host"),
                pairingBudget = budget ?: PairingBudget.DEFAULT,
                hostNames = setOfNotNull(flags[NAME_FLAG]),
                keyReset = when {
                    FORGET_FLAG in args -> KeyReset.ForgetDevices
                    NEW_LINK_FLAG in args -> KeyReset.NewLink
                    else -> KeyReset.Keep
                },
            )
        }
    }
}
