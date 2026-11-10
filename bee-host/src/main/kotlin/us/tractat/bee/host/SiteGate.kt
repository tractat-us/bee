package us.tractat.bee.host

import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.request.path
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
 *   Or its `Host` is a [PublicUrl]'s authority in [publicUrls], on whatever port it arrived: a
 *   TLS-terminating proxy dials the host on a port of its own (bee#145).
 * - its `Origin`, if it has one, is the host's own: the public URL's origin for a public `Host`,
 *   else `http://` and the call's `Host`. A page from any other site, or from another port on
 *   this machine, has another origin, and a browser always says so on a WebSocket. The JVM bridge
 *   sends no `Origin`, so a missing one passes.
 * - its Fetch Metadata, if it has any, says what the call's path allows (bee#54). A page loading
 *   the host as an `<img>`, a `<script>`, a frame or a no-cors `fetch` sends no `Origin`, but a
 *   browser marks it `Sec-Fetch-Site: cross-site`, or `same-site` from another port on this
 *   machine. So:
 *   - **the page**, at a path in [PAGE_PATHS], takes a top-level navigation from anywhere:
 *     `Sec-Fetch-Mode: navigate` with `Sec-Fetch-Dest: document` from another site, so a join
 *     link opened from chat still works, and `none`, a typed URL or a bookmark. A call from another
 *     site that isn't such a navigation is refused, so no site frames the page. A prefetch or a
 *     prerender (`Sec-Purpose`) is refused here too, even from the host's own site: Chrome would
 *     admit the page, then see its subresources and sockets refused, and activate it blank. On a
 *     refusal Chrome drops the speculation and navigates normally.
 *   - **every other path** (`/pair`, `/board-id`, the sockets, the page's scripts) takes only
 *     `same-origin`, which is the page's own calls. No legitimate caller navigates to one, and a
 *     browser marks a navigation it can't attribute `none`: Chromium marks another site's
 *     speculation-rules prefetch that way. A call carrying `Sec-Purpose` is refused
 *     on every path, as above.
 *
 *   A call with no `Sec-Fetch-Site` passes on any path: the bridge sends none, nor does a browser
 *   older than these headers. The page paths are an allow-list, so any other spelling of a path,
 *   such as `/%70air`, gets the stricter rule.
 *
 * A browser can reach a loopback host from any page it shows, and loopback needs no key
 * ([mountEndpoints]), so without this any site open on the laptop could read and write the board.
 *
 * @param names the names the host answers to beyond `localhost`: its LAN name under [Reach.Lan],
 *   and any `--host-name`. Compared without case, as browsers lower-case a host name.
 * @param publicUrls the URLs a proxy serves the host at: `--public-url`'s under [Reach.Public]
 */
class SiteRule(names: Set<String>, private val publicUrls: Set<PublicUrl> = emptySet()) {
    private val names: Set<String> = names.mapTo(mutableSetOf(LOCALHOST)) { it.lowercase() }

    /**
     * Why a call to [path] (as sent, before any decoding, without its query), with [headers] and
     * arriving at [arrival], isn't from the host's site; null if it is.
     */
    fun refusal(path: String, headers: Headers, arrival: Arrival): String? {
        val host = headers[HttpHeaders.Host]
        val origin = headers[HttpHeaders.Origin]
        val ownOrigin = host?.let(::authority)?.let { originFor(it, arrival) }
        return when {
            ownOrigin == null -> "This host doesn't go by \"$host\"."
            origin != null && origin.lowercase() != ownOrigin -> "Only the board's own page may call this host, not one from $origin."
            headers[SEC_PURPOSE] != null -> "This host doesn't answer a prefetch or a prerender."
            path in PAGE_PATHS -> "Another site may only link to the board's page, not load it.".takeIf { fromAnotherSite(headers) && !topLevelNavigation(headers) }
            !fromOwnOriginOrUnmarked(headers) -> "Only the board's own page may call this; another site may only link to the page."
            else -> null
        }
    }

    private fun fromAnotherSite(headers: Headers): Boolean =
        headers[FETCH_SITE].let { it.equals("cross-site", ignoreCase = true) || it.equals("same-site", ignoreCase = true) }

    private fun topLevelNavigation(headers: Headers): Boolean =
        headers[FETCH_MODE].equals("navigate", ignoreCase = true) && headers[FETCH_DEST].equals("document", ignoreCase = true)

    private fun fromOwnOriginOrUnmarked(headers: Headers): Boolean =
        headers[FETCH_SITE].let { it == null || it.equals("same-origin", ignoreCase = true) }

    /**
     * The origin of the host's own page when a call names it [named] and arrives at [arrival]: a
     * public URL's, on any port the proxy dialled, else `http://` and [named] when the host goes by
     * it there; null when it doesn't.
     */
    private fun originFor(named: Authority, arrival: Arrival): String? =
        publicUrls.firstOrNull { named.name == it.host && named.portOr(PublicUrl.defaultPort(it.scheme)) == it.port }?.origin
            ?: "http://${named.spelled}".takeIf { answersTo(named, arrival) }

    private fun answersTo(named: Authority, arrival: Arrival): Boolean =
        named.portOr(HTTP_PORT) == arrival.port && (named.name in names || sameAddress(named.name, arrival.address))

    private companion object {
        /** The page's own paths, the only ones another site may navigate to: what `staticResources("/", "web")` serves as `index.html`. */
        val PAGE_PATHS = setOf("/", "/index.html")
        const val LOCALHOST = "localhost"
        const val SEC_PURPOSE = "Sec-Purpose"
        const val FETCH_SITE = "Sec-Fetch-Site"
        const val FETCH_MODE = "Sec-Fetch-Mode"
        const val FETCH_DEST = "Sec-Fetch-Dest"
        const val HTTP_PORT = 80
        const val MAX_PORT = 65_535
    }

    /**
     * A `Host` authority, lower-cased: an IPv6 address keeps its brackets. [port] is the one it
     * names, if any; which port none means depends on the scheme the caller dialled.
     */
    private data class Authority(val name: String, val port: Int?) {
        fun portOr(default: Int): Int = port ?: default

        /** As a browser writes it for `http://`: no port when it is the default. */
        val spelled: String get() = if (portOr(HTTP_PORT) == HTTP_PORT) name else "$name:$port"
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
            port.isEmpty() -> null
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
        spikeOfLiteral(text)
    } catch (_: IllegalArgumentException) {
        null
    }
}

// SPIKE (docs/spikes/2026-11-bee-app.md): Android has no InetAddress.ofLiteral (Java 22).
// getByName does no lookup for a literal; anything that isn't one is refused before it gets there.
internal fun spikeOfLiteral(text: String): InetAddress = try {
    InetAddress.ofLiteral(text)
} catch (_: LinkageError) {
    require(SPIKE_LITERAL.matches(text) && (':' in text || SPIKE_V4.matches(text))) { "Not a literal: $text" }
    InetAddress.getByName(text)
}

private val SPIKE_LITERAL = Regex("[0-9A-Fa-f:.%]+")
private val SPIKE_V4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

/**
 * Refuses, with a 403 before any route runs, every call [rule] says isn't from the host's own site.
 * Installed on the application, not on chosen routes, so a route added later, anywhere, is behind
 * it too: the board and claims sockets, `/pair`, `/board-id` and the page alike. A browser marks a
 * call from another site by its `Origin` or its Fetch Metadata, so no page on another site reaches
 * any of them except by a top-level navigation to the page itself, which only opens the board's
 * own page, or in a browser too old to send Fetch Metadata ([SiteRule]). The rule sees the path
 * as the call spelled it, so a route added later is held to the stricter, non-page rule.
 *
 * Workaround for kuilt#3146, which asks kuilt's server looms for a handshake check of their own.
 */
fun Application.installSiteGate(rule: SiteRule, arrivals: ArrivalAddress) {
    install(
        createApplicationPlugin("BeeSiteGate") {
            onCall { call ->
                val refusal = rule.refusal(call.request.path(), call.request.headers, arrivals.of(call))
                if (refusal != null) call.respond(HttpStatusCode.Forbidden, refusal)
            }
        },
    )
}
