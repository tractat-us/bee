package us.tractat.bee.host

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import us.tractat.kuilt.liveness.PartitionEvent
import us.tractat.kuilt.raft.ClusterConfig
import kotlin.time.Instant

/**
 * The claims links the host has lost, read off the voter's [membership] (bee#28's ruling).
 *
 * TODO(#28): not yet implemented.
 */
fun claimsLinkLoss(membership: Flow<ClusterConfig>, clock: () -> Instant): Flow<PartitionEvent> = emptyFlow()
