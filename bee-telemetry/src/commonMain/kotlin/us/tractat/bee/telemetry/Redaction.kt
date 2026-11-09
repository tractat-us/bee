package us.tractat.bee.telemetry

/**
 * Takes keys out of text before telemetry keeps it. Invariant: **no device key, join key or key
 * hash reaches a telemetry store**, wherever in a log line or attribute it was written.
 *
 * Two shapes are scrubbed, so a key needs no registering to be caught:
 *
 * - a `k` query parameter, `?k=…` or `&k=…`, raw or percent-encoded (`k%3D…`): every join link,
 *   socket URL and `/mcp` URL carries its key there ([us.tractat.bee.core.PairingWire.KEY_PARAM]).
 * - any run of exactly 43 base64url characters: a 256-bit device key and a SHA-256 key hash both
 *   print that way. A harmless 43-character token is scrubbed too, which costs only its text.
 */
object Redaction {
    /** What a scrubbed key reads as. */
    const val REDACTED: String = "<redacted>"

    private val keyParam = Regex("""([?&]k=|[?&]k%3D|%26k%3D|%3Fk%3D)[^&\s#"'<>]*""", RegexOption.IGNORE_CASE)
    private val keyShaped = Regex("""(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{43}(?![A-Za-z0-9_-])""")

    /** [text] with every key-shaped run and `k` parameter replaced by [REDACTED]. */
    fun scrub(text: String): String =
        keyShaped.replace(keyParam.replace(text) { "${it.groupValues[1]}$REDACTED" }, REDACTED)

    /** [attributes] with every value [scrub]bed. Keys are bee's own names, so they stay. */
    fun scrub(attributes: Map<String, String>): Map<String, String> = attributes.mapValues { (_, value) -> scrub(value) }
}
