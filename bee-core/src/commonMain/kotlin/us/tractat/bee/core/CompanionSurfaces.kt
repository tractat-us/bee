package us.tractat.bee.core

/**
 * The surfaces the brainstorming companion opens (spec §3): `companion-<id>`, one per companion
 * session. The companion alone waits on its own, so a board-wide wait leaves them out. Lives in
 * core because the companion names them and the bridge recognises them.
 */
object CompanionSurfaces {
    const val PREFIX: String = "companion-"
}
