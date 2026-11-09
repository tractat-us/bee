package us.tractat.bee.mcp

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.bee.core.DepartReason
import us.tractat.bee.core.HostAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * The bridges a host runs on behalf of MCP clients that reach it over Streamable HTTP at [PATH], so
 * a coworker's Claude joins with a URL and nothing installed. Invariant: **each MCP session has one
 * bridge, and that bridge departs once, when its session closes**: `Left` when the client deletes
 * the session, its transport closes, or the host stops ([close]); `Quiet` when the session idles
 * past [idleAfter] ([IDLE_AFTER]).
 *
 * Each bridge is a [BridgePeer], the same peer stdio's `bee-mcp` is: a replica and a claims learner
 * dialling the host's own board and claims sockets over loopback, so presence, claims, `/clear`
 * aside, and departures all work as they do for a bridge on a laptop. Each session's server is
 * [BeeServer]'s, so it offers the same tools, prompt and instructions.
 *
 * A session is idle while no request on it is in flight and no GET stream of it is open, and was
 * last active [idleAfter] ago. The SDK's own expiry counts only requests, so it would close a live
 * Claude session sitting at its prompt with its GET stream open; this one doesn't, and the SDK's is
 * off.
 *
 * Who may reach [PATH] is the host's business: the host gates it as it gates the board's sockets.
 *
 * @param scope the host's scope: the idle check and every bridge's work run under it
 * @param checkEvery how often the idle check runs
 */
class HttpBridges(
    scope: CoroutineScope,
    private val clock: () -> Instant,
    private val random: Random,
    private val idleAfter: Duration = IDLE_AFTER,
    private val checkEvery: Duration = minOf(CHECK_EVERY, idleAfter / 4),
) {
    private val supervisor = SupervisorJob(scope.coroutineContext[Job])
    private val work = CoroutineScope(scope.coroutineContext + supervisor + Dispatchers.Default)
    private val http = peerHttpClient()
    private val open = ConcurrentHashMap.newKeySet<Session>()
    private val byId = ConcurrentHashMap<String, Session>()
    private val departures = ConcurrentHashMap.newKeySet<Job>()

    /** How many MCP sessions have a bridge now. */
    val count: Int get() = open.size

    init {
        work.launch(CoroutineName("http-bridges-idle")) {
            while (true) {
                delay(checkEvery)
                val now = clock()
                open.filter { it.idle(now) }.forEach { it.quiet() }
            }
        }
    }

    /**
     * Serves MCP over Streamable HTTP at [PATH] on this application. The host's site gate already
     * guards every call against DNS rebinding and other sites' pages, on every name the host goes
     * by, so the SDK's own localhost-only check is off.
     */
    fun mount(app: Application) {
        app.routing { route(PATH) { install(Activity) { sessions = this@HttpBridges } } }
        app.mcpStreamableHttp(
            path = PATH,
            enableDnsRebindingProtection = false,
            // A heartbeat on the GET stream, so a client's read timeout never ends a quiet one.
            sseHeartbeatConfig = { period = HEARTBEAT },
            sessionIdleTimeout = Duration.INFINITE,
        ) { open(this) }
    }

    /**
     * Ends every session, each departing `Left`, and waits for those departures. Call it before the
     * host's sockets and voter stop, or no `Depart` can commit.
     */
    suspend fun close() = withContext(NonCancellable) {
        open.toList().forEach { it.end() }
        departures.toList().joinAll()
        supervisor.cancelAndJoin()
        http.close()
    }

    /**
     * A new MCP session's server, over a fresh bridge named for the call's `?name=`. The bridge
     * stays off the board until the session initializes ([bind]): Claude Code's first POST offers
     * a protocol version this SDK refuses, then retries, and that first session must leave no trace.
     */
    private fun open(context: RoutingContext): Server {
        val call = context.call
        val name = sessionNameFrom(call.request.queryParameters[NAME_PARAM])
        // The call arrived on the host's own port: the bridge dials it on loopback, which needs no key.
        val host = HostAddress.parse("ws://127.0.0.1:${call.request.local.localPort}")
        val bridgeScope = CoroutineScope(work.coroutineContext + SupervisorJob(supervisor) + CoroutineName("http-bridge-$name"))
        val peer = BridgePeer.join(host, name, http, bridgeScope, random, clock)
        val server = BeeServer.create(peer.bridge)
        val session = Session(peer, bridgeScope, server)
        open += session
        call.attributes.put(OPENING, session)
        // One server per session, so the one session it connects is this one: its close is the bridge's.
        server.onConnect { server.sessions.values.single().onClose { session.depart() } }
        return server
    }

    /** One MCP session's bridge and how recently its client was heard from. */
    private inner class Session(val peer: BridgePeer, private val bridgeScope: CoroutineScope, val server: Server) {
        private val inFlight = AtomicInteger()

        @Volatile private var lastActive: Instant = clock()

        @Volatile private var reason: DepartReason = DepartReason.Left

        /** The session's id, once it has initialized and its bridge is on the board. */
        @Volatile private var id: String? = null

        private val departing = AtomicBoolean()

        fun enter() {
            inFlight.incrementAndGet()
            lastActive = clock()
        }

        fun exit() {
            lastActive = clock()
            inFlight.decrementAndGet()
        }

        /** The session initialized as [sessionId]: files it there, and puts its bridge on the board. */
        fun start(sessionId: String) {
            id = sessionId
            byId[sessionId] = this
            peer.start()
            bridgeScope.launch { peer.bridge.announce() }
            logger.info { "An MCP client over HTTP joins as ${peer.bridge.self}." }
        }

        fun idle(now: Instant): Boolean = inFlight.get() == 0 && now - lastActive >= idleAfter

        /** Closes the session as gone quiet, departing it [DepartReason.Quiet]. */
        fun quiet() {
            if (departing.get()) return
            reason = DepartReason.Quiet
            logger.info { "${peer.bridge.self}'s MCP client went quiet for $idleAfter; closing its session." }
            work.launch { end() }
        }

        /**
         * Closes the session's server, so its id answers 404 from now on, and departs the bridge.
         * The close departs it too, once connected; [depart] runs once, so a session that never
         * connected departs all the same.
         */
        suspend fun end() {
            server.close()
            depart()
        }

        /**
         * The session closed: departs the bridge, once, then ends its work. A bridge whose session
         * never initialized was never on the board, so it has nothing to depart.
         */
        fun depart() {
            if (!departing.compareAndSet(false, true)) return
            val why = reason
            val started = id
            val job = work.launch(CoroutineName("http-bridge-depart")) {
                val who = peer.bridge.self
                val committed = try {
                    if (started == null) return@launch
                    // A session can end before its claims link is up (a client that only checks
                    // the server, then deletes): wait for it, briefly, so the Depart has a link.
                    withTimeoutOrNull(CLAIMS_BOUND) { peer.bridge.awaitClaims() }
                    peer.bridge.leave(LEAVE_BOUND, why)
                } finally {
                    open -= this@Session
                    started?.let { byId.remove(it, this@Session) }
                    peer.board.close()
                    bridgeScope.cancel()
                }
                logger.info { "$who's MCP session ended; its Depart($why) ${if (committed) "committed" else "didn't commit, so the host departs it as crashed"}." }
            }
            departures += job
            job.invokeOnCompletion { departures -= job }
        }
    }

    /** Which session a call is on: by its `Mcp-Session-Id`, or the one it opened. */
    private fun sessionOf(call: ApplicationCall): Session? = call.request.headers[SESSION_HEADER]?.let(byId::get)

    /** After a call that opened a session: starts it under the id the SDK answered with, if it did. */
    private fun bind(call: ApplicationCall) {
        val session = call.attributes.getOrNull(OPENING) ?: return
        val id = call.response.headers[SESSION_HEADER] ?: return
        session.start(id)
    }

    /** [Activity]'s configuration: the registry it reports to. */
    class ActivityConfig {
        internal var sessions: HttpBridges? = null
    }

    companion object {
        /** Where the host serves MCP over Streamable HTTP. */
        const val PATH: String = "/mcp"

        /** The query parameter naming the person whose Claude this is. */
        const val NAME_PARAM: String = "name"

        /**
         * How long a session may go with no request in flight and no GET stream open before it
         * departs `Quiet`.
         *
         * Measured (Claude Code 2.1.319): a session holds its GET stream from `initialize` until
         * the process exits, and exits without a DELETE; a `bee_wait_action` long-poll is a POST
         * in flight for its full 20 s. So a live Claude never counts as idle, however long it sits
         * at its prompt, and this clock starts only when the stream is gone. Two minutes then
         * covers the stream's own reconnect after a dropped link, with room to spare, and is how
         * long a Claude that exited stays on the board. The host's own quiet rule (20 minutes
         * without acting, bee-host's `Departures`) still applies to a live one, as for
         * any bridge.
         */
        val IDLE_AFTER: Duration = 2.minutes

        private val CHECK_EVERY: Duration = 5.seconds

        /**
         * How long a departing bridge waits for its claims link to come up, if it isn't yet. On
         * loopback it comes up in tens of milliseconds; this only bounds a host that is stopping.
         */
        private val CLAIMS_BOUND: Duration = 5.seconds

        /** Every GET stream sends a comment this often, well inside any client's read timeout. */
        private val HEARTBEAT: Duration = 15.seconds

        private const val SESSION_HEADER = "Mcp-Session-Id"
        private const val DEFAULT_NAME = "claude"
        private val OPENING = AttributeKey<Session>("bee-mcp-opening")

        /**
         * The session name for a `?name=` of [raw]: `<name>-claude`, as `bee-up` and `bee-join` name
         * a person's sessions, or [DEFAULT_NAME] when it names nobody. A peer name can't hold ':' or
         * '#', so those are dropped.
         */
        fun sessionNameFrom(raw: String?): String {
            val name = raw.orEmpty().filterNot { it == ':' || it == '#' }.trim()
            return if (name.isEmpty()) DEFAULT_NAME else "$name-claude"
        }

        /**
         * Counts each call on [PATH] as activity on its session from start to finish: a GET stream
         * for as long as it is open, a POST until its answer is sent.
         */
        private val Activity = createRouteScopedPlugin("BeeMcpActivity", ::ActivityConfig) {
            val sessions = requireNotNull(pluginConfig.sessions) { "BeeMcpActivity needs its sessions." }
            requireNotNull(route) { "BeeMcpActivity is installed on a route." }.intercept(ApplicationCallPipeline.Call) {
                val session = sessions.sessionOf(context)
                session?.enter()
                try {
                    proceed()
                } finally {
                    session?.exit()
                    sessions.bind(context)
                }
            }
        }
    }
}
