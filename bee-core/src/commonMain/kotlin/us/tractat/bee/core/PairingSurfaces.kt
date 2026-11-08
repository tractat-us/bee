package us.tractat.bee.core

/**
 * The surfaces the host opens for pairing (spec §5.7): one `bee-pair-<id>` join request per
 * pairing. The host handles their admit records itself, so the bridge refuses a wait or list on
 * one (bee#72). Lives in core because the host names them and the bridge recognises them.
 */
object PairingSurfaces {
    const val PREFIX: String = "bee-pair-"

    /** Whether [surfaceId] is a pairing surface. */
    fun isPairing(surfaceId: String): Boolean = surfaceId.startsWith(PREFIX)
}
