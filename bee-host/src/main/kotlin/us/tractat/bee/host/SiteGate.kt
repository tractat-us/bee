package us.tractat.bee.host

import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.response.respond
import java.net.InetAddress

/** The host's own end of a call's connection: the address and port the dialler reached. */
data class Arrival(val address: String, val port: Int)

/** Where a call arrived, at the host's end. */
fun interface ArrivalAddress {
    fun of(call: ApplicationCall): Arrival

    companion object {
        /** The TCP socket's local end, never a header: a header is whatever the dialler says it is. */
        val DIRECT: ArrivalAddress = ArrivalAddress { Arrival(it.request.local.localAddress, it.request.local.localPort) }
    }
}

/**
 * Which calls come from the host's own site (bee#45). A call passes only when:
 *
 * - its `Host` names the port it arrived on, and a name the host goes by: `localhost`, a name in
 *   [names], or the very IP address it arrived on (`127.0.0.1`, `[::1]`, a LAN address). A DNS
 *   rebinding attack needs a name the attacker owns, and no such name is ever one of these.
 * - its `Origin`, if it has one, is the host's own: `http://` and the call's `Host`. A page from
 *   any other site, or from another port on this machine, has another origin, and a browser
 *   always says so on a WebSocket. The JVM bridge sends no `Origin`, so a missing one passes.
 * - its Fetch Metadata, if it has any, doesn't say another site sent it (bee#54). A page loading
 *   the host as an `<img>`, a `<script>`, a frame or a no-cors `fetch` sends no `Origin`, but a
 *   browser marks it `Sec-Fetch-Site: cross-site`, or `same-site` from another port on this
 *   machine. Only a top-level navigation from another site passes, `Sec-Fetch-Mode: navigate` with
 *   `Sec-Fetch-Dest: document`, so a join link opened from chat still works. `same-origin` and
 *   `none` (a typed URL, a bookmark) pass, and so does a call with no `Sec-Fetch-Site`: the bridge
 *   sends none, nor does a browser older than these headers.
 *
 * A browser can reach a loopback host from any page it shows, and loopback needs no key
 * ([mountEndpoints]), so without this any site open on the laptop could read and write the board.
 *
 * @param names the names the host answers to beyond `localhost`: its LAN name under [Reach.Lan],
 *   and any `--host-name`. Compared without case, as browsers lower-case a host name.
 */
class SiteRule(names: Set<String>) {
    private val names: Set<String> = names.mapTo(mutableSetOf(LOCALHOST)) { it.lowercase() }

    /** Why a call with [headers], arriving at [arrival], isn't from the host's site; null if it is. */
    fun refusal(headers: Headers, arrival: Arrival): String? {
        val host = headers[HttpHeaders.Host]
        val origin = headers[HttpHeaders.Origin]
        val named = host?.let(::authority)
        return when {
            named == null || !answersTo(named, arrival) -> "This host doesn't go by \"$host\"."
            origin != null && origin.lowercase() != "http://${named.spelled}" -> "Only the board's own page may call this host, not one from $origin."
            fromAnotherSite(headers) && !topLevelNavigation(headers) -> "Another site may only link to this host, not load it."
            else -> null
        }
    }

    private fun fromAnotherSite(headers: Headers): Boolean =
        headers[FETCH_SITE].let { it.equals("cross-site", ignoreCase = true) || it.equals("same-site", ignoreCase = true) }

    private fun topLevelNavigation(headers: Headers): Boolean =
        headers[FETCH_MODE].equals("navigate", ignoreCase = true) && headers[FETCH_DEST].equals("document", ignoreCase = true)

    private fun answersTo(named: Authority, arrival: Arrival): Boolean =
        named.port == arrival.port && (named.name in names || sameAddress(named.name, arrival.address))

    private companion object {
        const val LOCALHOST = "localhost"
        const val FETCH_SITE = "Sec-Fetch-Site"
        const val FETCH_MODE = "Sec-Fetch-Mode"
        const val FETCH_DEST = "Sec-Fetch-Dest"
        const val HTTP_PORT = 80
        const val MAX_PORT = 65_535
    }

    /** A `Host` or origin authority, lower-cased: an IPv6 address keeps its brackets. */
    private data class Authority(val name: String, val port: Int) {
        /** As a browser writes it: no port when it is the default. */
        val spelled: String get() = if (port == HTTP_PORT) name else "$name:$port"
    }

    /** [raw] as `name[:port]` or `[v6][:port]`, or null if it is neither. */
    private fun authority(raw: String): Authority? {
        val text = raw.lowercase()
        val (name, port) = if (text.startsWith('[')) {
            val close = text.indexOf(']').takeIf { it > 0 } ?: return null
            text.substring(0, close + 1) to text.substring(close + 1)
        } else {
            text.substringBefore(':') to text.substring(text.substringBefore(':').length)
        }
        val number = when {
            port.isEmpty() -> HTTP_PORT
            port.startsWith(':') -> port.drop(1).toIntOrNull()?.takeIf { it in 1..MAX_PORT } ?: return null
            else -> return null
        }
        return Authority(name, number).takeIf { name.isNotEmpty() }
    }

    /** Whether [name] is an IP literal for the same address as [arrived]. A name that isn't a literal never is. */
    private fun sameAddress(name: String, arrived: String): Boolean {
        val literal = literalOrNull(name.removePrefix("[").removeSuffix("]")) ?: return false
        return literal == literalOrNull(arrived)
    }

    private fun literalOrNull(text: String): InetAddress? = try {
        InetAddress.ofLiteral(text)
    } catch (_: IllegalArgumentException) {
        null
    }
}

/**
 * Refuses, with a 403 before any route runs, every call [rule] says isn't from the host's own site.
 * Installed on the application, not on chosen routes, so a route added later, anywhere, is behind
 * it too: the board and claims sockets, `/pair`, `/board-id` and the page alike. A browser marks a
 * call from another site by its `Origin` or its Fetch Metadata, so no page on another site reaches
 * any of them, except in a browser too old to send Fetch Metadata ([SiteRule]).
 *
 * Workaround for kuilt#3146, which asks kuilt's server looms for a handshake check of their own.
 */
fun Application.installSiteGate(rule: SiteRule, arrivals: ArrivalAddress) {
    install(
        createApplicationPlugin("BeeSiteGate") {
            onCall { call ->
                val refusal = rule.refusal(call.request.headers, arrivals.of(call))
                if (refusal != null) call.respond(HttpStatusCode.Forbidden, refusal)
            }
        },
    )
}
