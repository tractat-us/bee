package us.tractat.bee.mcp

import us.tractat.bee.core.HostAddress

/** The host a bridge or a companion joins when `$BEE_HOST` is unset: the default port on loopback (plan, "Decisions"). */
const val DEFAULT_HOST: String = "ws://127.0.0.1:8233"

/**
 * The host `$BEE_HOST` names, read from [value], its value or null when it is unset: a host's root
 * or the join link a `--lan` host prints ([HostAddress.parse]). Unset or blank, it is [DEFAULT_HOST].
 *
 * @throws IllegalArgumentException naming `BEE_HOST`, if [value] names no host
 */
fun beeHostFrom(value: String?): HostAddress {
    val text = value?.takeIf { it.isNotBlank() } ?: DEFAULT_HOST
    return try {
        HostAddress.parse(text)
    } catch (unusable: IllegalArgumentException) {
        throw IllegalArgumentException("BEE_HOST: ${unusable.message}", unusable)
    }
}
