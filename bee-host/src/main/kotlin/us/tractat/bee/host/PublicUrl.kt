package us.tractat.bee.host

import java.net.URI
import java.net.URISyntaxException

/**
 * The one URL a host behind a TLS-terminating proxy is served at, `--public-url` (bee#145): an
 * `https://` origin, or `http://` for a test proxy, with the path `/` and nothing after it. A
 * browser on the board's page names the host by [authority] and sends [origin] as its `Origin`,
 * whatever port the proxy dials the host on ([SiteRule]); a join link starts with [page].
 *
 * Only [parse] makes one, so a URL with a query, a fragment, a user or another path never gets this far.
 *
 * @property scheme `https` or `http`, lower-cased
 * @property host the host name, lower-cased; an IPv6 address keeps its brackets
 * @property port the port a browser dials: the scheme's default when the URL names none
 */
@ConsistentCopyVisibility
data class PublicUrl private constructor(val scheme: String, val host: String, val port: Int) {
    /** The host as a browser writes it in `Host`: no port when it is [scheme]'s default. */
    val authority: String get() = if (port == defaultPort(scheme)) host else "$host:$port"

    /** What a page served at this URL sends as its `Origin`. */
    val origin: String get() = "$scheme://$authority"

    /** The board's page, at this URL. */
    val page: String get() = "$origin/"

    override fun toString(): String = page

    companion object {
        private const val HTTPS = "https"
        private const val HTTP = "http"
        private const val HTTPS_PORT = 443
        private const val HTTP_PORT = 80

        /** The port a browser dials for [scheme] when a URL names none. */
        internal fun defaultPort(scheme: String): Int = if (scheme == HTTPS) HTTPS_PORT else HTTP_PORT

        /**
         * [text] as a public URL: `https://` or `http://`, a host, an optional port, and the path
         * `/` (or none, which is the same page). Nothing else: no query, fragment or user.
         *
         * @throws IllegalArgumentException for anything else, saying what was wrong
         */
        fun parse(text: String): PublicUrl {
            val uri = try {
                URI(text)
            } catch (failure: URISyntaxException) {
                throw IllegalArgumentException("Not a URL: $text (${failure.reason})", failure)
            }
            val scheme = uri.scheme?.lowercase()
            require(scheme == HTTPS || scheme == HTTP) { "A public URL is https:// (or http://, behind a test proxy): $text" }
            val host = requireNotNull(uri.host?.lowercase()?.takeIf { it.isNotEmpty() }) { "A public URL names a host: $text" }
            require(uri.rawUserInfo == null) { "A public URL names no user: $text" }
            require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") { "A public URL's path is /, the board's page: $text" }
            require(uri.rawQuery == null && uri.rawFragment == null) { "A public URL has no query or fragment: $text" }
            return PublicUrl(scheme, host, if (uri.port == -1) defaultPort(scheme) else uri.port)
        }
    }
}
