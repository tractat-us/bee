package us.tractat.bee.telemetry

import kotlin.time.Instant

/**
 * What a bee process records about itself: an event when something happens, a span for work with
 * a duration. Invariant: **recording never throws, never blocks and never suspends the caller**,
 * whatever the store underneath does. A call hands its record to a bounded queue and returns; a
 * full queue drops its oldest record.
 *
 * Attributes are ids, names, counts and sizes, never content: no key, no board text, no MCP
 * argument body. Every value is scrubbed ([Redaction]) before it is kept, as a backstop, not as
 * permission to pass one.
 */
interface BeeTelemetry {
    /** Records that [name] happened now, with [attributes]. */
    fun event(name: String, attributes: Map<String, String> = emptyMap())

    /**
     * Records a span [name] that ran from [start] to [end], with [attributes]. [error] is null for
     * work that succeeded, else a short, content-free reason.
     */
    fun span(name: String, start: Instant, end: Instant, attributes: Map<String, String>, error: String? = null)

    companion object {
        /** Records nothing: the kill switch (`BEE_TELEMETRY=off`), a test's default, and a process whose store wouldn't open. */
        val Off: BeeTelemetry = object : BeeTelemetry {
            override fun event(name: String, attributes: Map<String, String>) = Unit

            override fun span(name: String, start: Instant, end: Instant, attributes: Map<String, String>, error: String?) = Unit
        }
    }
}

/** [BeeTelemetry.event], with each attribute's value as its `toString`; a null value is left out. */
fun BeeTelemetry.event(name: String, vararg attributes: Pair<String, Any?>) =
    event(name, attributes.mapNotNull { (key, value) -> value?.let { key to it.toString() } }.toMap())

/** Attribute names bee records under. OpenTelemetry's own where one fits. */
object Attr {
    /** Every record's event name, OpenTelemetry's `event.name`. */
    const val EVENT = "event.name"

    /** Which program recorded it: `bee-host`, `bee-mcp`, `bee-web`. */
    const val SERVICE = "service.name"

    /** The recording process's role on the board: [Role]. */
    const val ROLE = "bee.role"

    /** The recording peer: a host's replica, a bridge's identity, a page's name. */
    const val PEER = "bee.peer"

    /** The board the recording peer is on, once it knows. */
    const val BOARD = "bee.board"

    /** The commit the recording build is from ([BuildInfo.commit]). */
    const val COMMIT = "bee.commit"

    /** A bridge's session name, `BEE_SESSION_NAME` or its folder's. */
    const val SESSION = "bee.session"

    /** The host's CRDT author for this boot. A host's `bee.peer` is the steady `host`. */
    const val REPLICA = "bee.replica"

    /** The recording process's id. */
    const val PID = "process.pid"

    /** The peer an event is about, where that isn't the recording peer: who attached, departed, dialled. */
    const val SUBJECT = "bee.subject"

    const val SURFACE = "bee.surface"
    const val TOOL = "bee.tool"
    const val OUTCOME = "bee.outcome"
    const val REASON = "bee.reason"
    const val PATH = "url.path"
}

/** The [Attr.ROLE] values. */
object Role {
    const val HOST = "host"
    const val BRIDGE = "bridge"
    const val PAGE = "page"
}
