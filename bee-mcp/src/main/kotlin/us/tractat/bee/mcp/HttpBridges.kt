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
import kotlinx.coroutines.CancellationException
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
 * bridge, and that bridge departs at most once, when its session closes**: `Left` when the client
 * deletes the session or its transport closes; `Quiet` when the session idles past [idleAfter]
 * ([IDLE_AFTER]). A host that stops ([close]) departs only those that can't come back as
 * themselves ([resumable]), `Left`: the others' identities are [kept], so the session each client
 * opens on the next host, at the same URL, is the same peer (bee#126).
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
 * @param kept the identities of the sessions the last host on this data dir had, and of these
 * @param keyKind which of the host's keys a call's `?k=` is, by the host's own reckoning
 * @param checkEvery how often the idle check runs
 * @param maxSessions how many sessions may have a bridge at once ([MAX_SESSIONS]); the SDK refuses the next with a 503
 * @param closeServer how a session's server closes: the SDK's own [Server.close]. A test's way to have it fail
 */
@Suppress("LongParameterList")
class HttpBridges internal constructor(
    scope: CoroutineScope,
    private val clock: () -> Instant,
    private val random: Random,
    private val kept: KeptIdentities,
    private val keyKind: (String?) -> PresentedKey,
    private val idleAfter: Duration,
    private val checkEvery: Duration,
    private val maxSessions: Int,
    private val closeServer: suspend (Server) -> Unit,
) {
    @Suppress("LongParameterList")
    constructor(
        scope: CoroutineScope,
        clock: () -> Instant,
        random: Random,
        kept: KeptIdentities,
        keyKind: (String?) -> PresentedKey,
        idleAfter: Duration = IDLE_AFTER,
        checkEvery: Duration = minOf(CHECK_EVERY, idleAfter / 4),
        maxSessions: Int = MAX_SESSIONS,
    ) : this(scope, clock, random, kept, keyKind, idleAfter, checkEvery, maxSessions, Server::close)

    private val supervisor = SupervisorJob(scope.coroutineContext[Job])
    private val work = CoroutineScope(scope.coroutineContext + supervisor + Dispatchers.Default)
    private val http = peerHttpClient()
    private val open = ConcurrentHashMap.newKeySet<Session>()
    private val byId = ConcurrentHashMap<String, Session>()
    private val departures = ConcurrentHashMap.newKeySet<Job>()

    /** Set once [close] begins: a session that closes from then on ends with its host, not by itself. */
    @Volatile private var stopping = false

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
            // Beyond this the SDK answers 503 before any bridge is made.
            maxSessions = maxSessions,
        ) { open(this) }
    }

    /**
     * Ends every session. One that can come back as itself ([resumable]) departs nothing, since its
     * client hasn't gone: its identities stay [kept], for the session the client opens on the next
     * host. One that can't departs `Left`, as before bee#126. Waits for those departures, and any
     * already under way, so call it before the host's sockets and voter stop.
     */
    suspend fun close() = withContext(NonCancellable) {
        stopping = true
        open.snapshot().forEach { it.end() }
        departures.snapshot().joinAll()
        supervisor.cancelAndJoin()
        http.close()
    }

    /**
     * A new MCP session's server, over a bridge named for the call's `?name=`: the identities the
     * last host kept for that name and the call's key, if it kept one session's ([KeptIdentities]),
     * else a fresh one. The join link's key, or a name nobody chose ([resumable]), always gets a
     * fresh one. The bridge stays off the board until the session initializes ([bind]): Claude
     * Code's first POST offers a protocol version this SDK refuses, then retries, and that first
     * session must leave no trace.
     */
    private fun open(context: RoutingContext): Server {
        val call = context.call
        val name = sessionNameFrom(call.request.queryParameters[NAME_PARAM])
        val key = call.request.queryParameters[KEY_PARAM]
        val caller = Caller.of(key)
        val canResume = resumable(name, keyKind(key))
        val resumed = if (canResume) kept.pickUp(name, caller) else null
        // The call arrived on the host's own port: the bridge dials it on loopback, which needs no key.
        val host = HostAddress.parse("ws://127.0.0.1:${call.request.local.localPort}")
        val bridgeScope = CoroutineScope(work.coroutineContext + SupervisorJob(supervisor) + CoroutineName("http-bridge-$name"))
        val peer = when (resumed) {
            null -> BridgePeer.join(host, name, http, bridgeScope, random, clock)
            else -> BridgePeer.join(host, name, http, bridgeScope, random, clock, resumed.current, resumed.held)
        }
        val server = BeeServer.create(peer.bridge)
        val session = Session(peer, bridgeScope, server, name, caller, canResume, resumed)
        open += session
        call.attributes.put(OPENING, session)
        // One server per session, so the one session it connects is this one: its close is the bridge's.
        server.onConnect { server.sessions.values.single().onClose { session.depart() } }
        return server
    }

    /** One MCP session's bridge and how recently its client was heard from. */
    @Suppress("LongParameterList")
    private inner class Session(
        val peer: BridgePeer,
        private val bridgeScope: CoroutineScope,
        val server: Server,
        private val name: String,
        private val caller: Caller,
        /**
         * Whether a session at its client's URL may come back as this one ([resumable]). One that
         * can't is never kept, and a host that stops departs it `Left`, as it did before bee#126.
         */
        private val canResume: Boolean,
        /** What the last host kept for this session's client, until the session starts and keeps its own. */
        private val resumed: KeptSession?,
    ) {
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

        /** Guards [id] and [departing] together: a session either starts, then departs, or never starts. */
        private val lifecycle = Any()

        /**
         * The session initialized as [sessionId]: files it there, and puts its bridge on the board.
         * A session that has already departed stays off the board and out of [byId].
         */
        fun start(sessionId: String) {
            synchronized(lifecycle) {
                if (departing.get()) return
                id = sessionId
                byId[sessionId] = this
            }
            peer.start()
            // Kept as it is now, and again each time it retires an identity, until it departs.
            if (canResume) {
                bridgeScope.launch(CoroutineName("http-bridge-kept")) {
                    peer.identity.current.collect { current ->
                        synchronized(lifecycle) {
                            if (!departing.get()) kept.live(this@Session, KeptSession(name, caller, current, peer.identity.held))
                        }
                    }
                }
            }
            bridgeScope.launch { peer.bridge.announce() }
            val again = if (resumed != null) " again, as the last host kept it" else ""
            logger.info { "An MCP client over HTTP joins$again as ${peer.bridge.self}." }
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
         *
         * A server whose close throws still departs, and the host's close goes on to the next
         * session, the voter and the board's last save. The SDK's (0.16.0) transport close throws
         * `NoSuchElementException` when a client's GET stream ends during it: it reads its streams
         * with `values.toList()`, which on one stream calls `iterator().next()` after `size`, while
         * the stream's own handler removes it from that `ConcurrentHashMap` outside the close's lock.
         */
        suspend fun end() {
            try {
                closeServer(server)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logger.warn(failure) { "${peer.bridge.self}'s MCP session didn't close cleanly; departing it all the same." }
            } finally {
                depart()
            }
        }

        /**
         * The session closed: departs the bridge, once, then ends its work. A bridge whose session
         * never initialized was never on the board, so it has nothing to depart, and what it picked
         * up waits again. One that closes with its host, and could come back as itself, departs
         * nothing either, and stays kept; one that couldn't departs `Left`.
         */
        fun depart() {
            val started = synchronized(lifecycle) {
                if (!departing.compareAndSet(false, true)) return
                id
            }
            val why = reason
            val withHost = stopping && canResume
            if (started == null) resumed?.let(kept::putBack)
            val job = work.launch(CoroutineName("http-bridge-depart")) {
                val who = peer.bridge.self
                val committed = try {
                    if (started == null) return@launch
                    if (withHost) {
                        logger.info { "$who's MCP session ends with its host; kept for its client's next session." }
                        return@launch
                    }
                    // A session can end before its claims link is up (a client that only checks the
                    // server, then deletes, on a slow machine): the Depart waits for the link and
                    // is proposed until it commits, as the host has no exit deadline to beat.
                    withTimeoutOrNull(DEPART_BOUND) { peer.bridge.departCommitted(why) } != null
                } finally {
                    if (!withHost) kept.drop(this@Session)
                    open -= this@Session
                    started?.let { byId.remove(it, this@Session) }
                    peer.board.close()
                    bridgeScope.cancel()
                }
                logger.info { "$who's MCP session ended; its Depart($why) ${if (committed) "committed" else "didn't commit within $DEPART_BOUND"}." }
            }
            departures += job
            job.invokeOnCompletion { departures -= job }
        }
    }

    /**
     * This set's members now, while others come and go. Not `toList()`: on a set of one it reads
     * `size`, then `iterator().next()`, which throws if the member left in between.
     */
    private fun <T> Set<T>.snapshot(): List<T> = ArrayList(this)

    /** Which session a call is on: by its `Mcp-Session-Id`, or the one it opened. */
    private fun sessionOf(call: ApplicationCall): Session? = call.request.headers[SESSION_HEADER]?.let(byId::get)

    /** After a call that opened a session: starts it under the id the SDK answered with, if it did. */
    private fun bind(call: ApplicationCall) {
        val session = call.attributes.getOrNull(OPENING) ?: return
        val id = call.response.headers[SESSION_HEADER] ?: return
        // Once: a call may pass through its respond hook more than once.
        call.attributes.remove(OPENING)
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

        /** The query parameter carrying the caller's key, off the laptop: what the host gates `/mcp` on. */
        const val KEY_PARAM: String = "k"

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
         * How many MCP sessions the host runs bridges for at once, counting those still
         * initializing. Each is a replica and a claims learner in the host's JVM; a room of people
         * and their sessions is a handful, so 32 is room to spare and a bound on what a key-holder
         * can make the host carry.
         */
        const val MAX_SESSIONS: Int = 32

        /**
         * How long a departing bridge keeps proposing its `Depart`, its claims link coming up first
         * if it isn't yet. On loopback that takes tens of milliseconds; this bounds a host that is
         * stopping, whose `close` waits on it.
         */
        private val DEPART_BOUND: Duration = 10.seconds

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
         * The `?name=` the page offers a viewer it can't name: bee-web's `McpCommand.PLACEHOLDER`.
         * Everyone who pastes that line unedited, on the one join link, presents the same name and key.
         */
        const val PLACEHOLDER_NAME: String = "you"

        /**
         * Whether a session named [name], presenting [key], may come back as a kept identity
         * (bee#126): only on a key that is one device's, or none (the host's own machine), and only
         * under a name someone chose. Everyone holding the join link holds its key, and no name, or
         * the page's [PLACEHOLDER_NAME], is anybody's.
         */
        fun resumable(name: String, key: PresentedKey): Boolean =
            key != PresentedKey.Join && name != DEFAULT_NAME && name != sessionNameFrom(PLACEHOLDER_NAME)

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
                }
            }
            // Before any byte of the answer leaves, so no DELETE naming the new id can come first.
            // The SDK has set the session's id header by then.
            onCallRespond { call, _ -> sessions.bind(call) }
        }
    }
}
