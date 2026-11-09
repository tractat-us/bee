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

    /** Keys a host with this reach admits from memory alone, never saved ([HostKeys.boot]). */
    fun heldKeys(): Set<DeviceKey>

    /** Whether the join link admits on a host with this reach ([HostKeys.boot]). */
    fun joinLink(): JoinLink

    /** The default: only this machine's own browsers and sessions. */
    data object Loopback : Reach {
        override val bind: String = "127.0.0.1"

        override fun names(): Set<String> = emptySet()

        override fun publicUrls(): Set<PublicUrl> = emptySet()

        override fun invitation(): Invitation = Invitation.Closed

        override fun peers(): PeerAddress = PeerAddress.DIRECT

        override fun heldKeys(): Set<DeviceKey> = emptySet()

        override fun joinLink(): JoinLink = JoinLink.Admits
    }

    /** `--lan`: every interface. A device off this machine needs a key, from pairing or a join link. */
    data object Lan : Reach {
        override val bind: String = ALL_INTERFACES

        override fun names(): Set<String> = setOf(lanName())

        override fun publicUrls(): Set<PublicUrl> = emptySet()

        override fun invitation(): Invitation = Invitation.Open(lanName(), ::lanAddresses)

        override fun peers(): PeerAddress = PeerAddress.DIRECT

        override fun heldKeys(): Set<DeviceKey> = emptySet()

        override fun joinLink(): JoinLink = JoinLink.Admits
    }

    /**
     * `--public-url <url>`: every interface, behind one trusted TLS-terminating proxy that dials the
     * machine's private address (Fly.io's). A browser names the host by [url], and each call's
     * caller is the one the proxy names, read the way [client] says that proxy writes it:
     * `--client-ip-header <name>` is [ForwardedPeer.ClientHeader], and without it,
     * [ForwardedPeer.AppendedForwardedFor].
     *
     * **Being on loopback grants nothing here**: no caller is [Caller.local], so every socket, MCP
     * session and invite is by key. The [owner] proves themselves with theirs; there is no public
     * host without one.
     *
     * **Only paired device keys write** (Iain's device-keys-only ruling, bee#159): the join link
     * admits nothing ([JoinLink.Off]), so a device gets on by its code, which anyone already on
     * types (Iain, 2026-11-08: any admitted person can admit anyone, for now).
     */
    data class Public(val url: PublicUrl, val client: ForwardedPeer, val owner: OwnerKey) : Reach {
        override val bind: String = ALL_INTERFACES

        override fun names(): Set<String> = emptySet()

        override fun publicUrls(): Set<PublicUrl> = setOf(url)

        override fun invitation(): Invitation = Invitation.Public(url)

        override fun peers(): PeerAddress = client

        override fun heldKeys(): Set<DeviceKey> = setOf(owner.key)

        override fun joinLink(): JoinLink = JoinLink.Off
    }
}

private const val ALL_INTERFACES = "0.0.0.0"

/** This machine's name on the local network: its host name, as mDNS serves it (`<name>.local`). */
fun lanName(): String = InetAddress.getLocalHost().hostName.let { if ('.' in it) it else "$it.local" }

/**
 * Where the host listens and keeps its data (plan, "Decisions": port 8233, "BEE" on a keypad,
 * data in `~/.bee/host/`). It binds loopback only unless [reach] is [Reach.Lan] or [Reach.Public].
 *
 * @param pairingBudget how many devices off this machine may ask to join in an hour, all together
 * @param hostNames names the host answers to beyond those [reach] gives it ([SiteRule]): one the
 *   machine goes by that bee can't find itself, or a test's
 * @param keyReset what this boot does to the keys kept in [dataDir] ([HostKeys]): by default, nothing
 * @param mcpIdleAfter how long an MCP session over HTTP may idle before its bridge departs
 *   ([HttpBridges.IDLE_AFTER]); not a flag, a test's
 * @param mcpMaxSessions how many MCP sessions over HTTP may have a bridge at once
 *   ([HttpBridges.MAX_SESSIONS]); not a flag, a test's
 * @param mcpUnclaimedAfter how long an MCP session the last host kept may wait for its client
 *   before the host departs it `Quiet` ([Departures.QUIET_AFTER], bee#126); not a flag, a test's
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
    val mcpUnclaimedAfter: Duration = Departures.QUIET_AFTER,
) {
    /** The address the host binds, from [reach]. */
    val bind: String get() = reach.bind

    /** Which calls come from the host's own site: by [reach]'s names and public URLs, and [hostNames]. */
    fun siteRule(): SiteRule = SiteRule(reach.names() + hostNames, reach.publicUrls())

    companion object {
        const val DEFAULT_PORT: Int = 8233

        private const val LAN_FLAG = "--lan"
        private const val NEW_LINK_FLAG = "--new-link"
        private const val FORGET_FLAG = "--forget-devices"
        private val SWITCHES = setOf(LAN_FLAG, NEW_LINK_FLAG, FORGET_FLAG)
        private const val BUDGET_FLAG = "--join-requests-per-hour"
        private const val NAME_FLAG = "--host-name"
        private const val PUBLIC_FLAG = "--public-url"
        private const val CLIENT_IP_FLAG = "--client-ip-header"

        /**
         * Reads `--port <n>`, `--data-dir <dir>`, `--join-requests-per-hour <n>`, `--host-name <name>`,
         * `--public-url <url>` ([PublicUrl.parse], [Reach.Public]) and `--lan`, the form Playwright
         * starts the host with (`--lan` and `--public-url` aside), which exclude each other.
         * `--client-ip-header <name>` names the header a public host's proxy sets to the client's
         * address ([ForwardedPeer.ClientHeader]), and needs `--public-url`. Without
         * `--data-dir` the data lives in `<home>/.bee/host`; without `--join-requests-per-hour`, it
         * is [PairingBudget.DEFAULT_PER_HOUR]. `--new-link` is [KeyReset.NewLink] and
         * `--forget-devices` is [KeyReset.ForgetDevices], which mints a new link too, so the two
         * together are `--forget-devices`.
         *
         * `--public-url` reads its owner's key from [environment]'s [OwnerKey.ENV], never from a
         * flag, so it stays out of the process list ([OwnerKey.parse]).
         *
         * @throws IllegalArgumentException on an unknown or repeated flag, a flag without a value, a
         *   bad port, a budget below one, a URL [PublicUrl.parse] refuses, `--lan` with `--public-url`,
         *   `--client-ip-header` without `--public-url` or with a value that is no header name, or
         *   `--public-url` with no owner key, or one [OwnerKey.parse] refuses
         */
        fun fromArgs(args: List<String>, home: Path, environment: (String) -> String?): HostConfig {
            val repeatedSwitches = SWITCHES.filter { switch -> args.count { it == switch } > 1 }
            require(repeatedSwitches.isEmpty()) { "Repeated flags: ${repeatedSwitches.joinToString()}" }
            val valued = args.filterNot { it in SWITCHES }
            require(valued.size % 2 == 0) { "Every flag but ${SWITCHES.joinToString()} takes a value: ${args.joinToString(" ")}" }
            val pairs = valued.chunked(2).map { (flag, value) -> flag to value }
            val repeated = pairs.groupBy { it.first }.filterValues { it.size > 1 }.keys
            require(repeated.isEmpty()) { "Repeated flags: ${repeated.joinToString()}" }
            val flags = pairs.toMap()
            val unknown = flags.keys - setOf("--port", "--data-dir", BUDGET_FLAG, NAME_FLAG, PUBLIC_FLAG, CLIENT_IP_FLAG)
            require(unknown.isEmpty()) {
                "Unknown flags: ${unknown.joinToString()}. bee-host takes --port <n>, --data-dir <dir>, $BUDGET_FLAG <n>, $NAME_FLAG <name>, $PUBLIC_FLAG <url>, " +
                    "$CLIENT_IP_FLAG <name>, $LAN_FLAG, $NEW_LINK_FLAG and $FORGET_FLAG."
            }
            val budget = flags[BUDGET_FLAG]?.let { PairingBudget(requireNotNull(it.toIntOrNull()) { "Not a number: $it" }) }
            val port = flags["--port"]?.let { requireNotNull(it.toIntOrNull()?.takeIf { p -> p in 0..65535 }) { "Not a port: $it" } }
            val public = flags[PUBLIC_FLAG]?.let(PublicUrl::parse)
            require(public == null || LAN_FLAG !in args) { "$LAN_FLAG and $PUBLIC_FLAG exclude each other: a public host binds every interface already." }
            require(public != null || CLIENT_IP_FLAG !in flags) { "$CLIENT_IP_FLAG needs $PUBLIC_FLAG: only a host behind a proxy has one to name its clients." }
            val client = flags[CLIENT_IP_FLAG]?.let(ForwardedPeer::ClientHeader) ?: ForwardedPeer.AppendedForwardedFor
            // Refused rather than ignored: a flag that silently does nothing reads as one that worked.
            require(public == null || NEW_LINK_FLAG !in args) { "$NEW_LINK_FLAG means nothing with $PUBLIC_FLAG: a hosted board's join link admits no one." }
            return HostConfig(
                port = port ?: DEFAULT_PORT,
                reach = when {
                    public != null -> Reach.Public(public, client, OwnerKey.parse(environment(OwnerKey.ENV)))
                    LAN_FLAG in args -> Reach.Lan
                    else -> Reach.Loopback
                },
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
