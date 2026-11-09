package us.tractat.bee.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How often a client's sockets ping the host (bee#77). Ktor's client waits twice this for the
 * pong, so a half-open link is torn down within three times this: 15 s.
 *
 * The host's own ping (kuilt's 15 s period and 15 s pong timeout) finds a dead peer, but a peer
 * that only reads never finds a dead host: after a host reboot or a dropped Wi-Fi link, its sockets
 * would wait out TCP's retransmits. Those can take longer than the host's boot grace (60 s,
 * bee#62), and the host then departs a live bridge. Torn down within 15 s, the board link redials
 * and the bridge posts its presence again (about 15 s more, as `Departures.BOOT_GRACE` counts it):
 * about half the grace. A pong late by 10 s on a live link costs a redial, not the identity.
 */
val PEER_PING_INTERVAL: Duration = 5.seconds
