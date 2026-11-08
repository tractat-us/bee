package us.tractat.bee.host

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.liveness.PartitionEvent
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.NodeId
import kotlin.time.Instant

/**
 * The claims links the host has lost, read off the voter's [membership] (bee#28's ruling): a
 * [PartitionEvent.PeerLost] for each node that leaves the cluster, stamped by [clock]. The peer id
 * is the node's own value, as `ClaimsLink` names its loom.
 *
 * The voter drops a learner once it has been disconnected for [ClaimsVoter.LEARNER_GRACE], the
 * spec's 30 s reconnect window, so a removal is a link that stayed lost for that window. A
 * learner that rejoins under the same node within the grace is never removed.
 *
 * The first config is the baseline: nothing in it is lost. Each later config is compared with the
 * one before it, so a node is lost once. A learner made a voter is still in the cluster, and isn't
 * lost. [membership] is a `StateFlow`, which conflates, so a node that joined and left between two
 * reads is never reported; one connected for a whole grace is read.
 */
fun claimsLinkLoss(membership: Flow<ClusterConfig>, clock: () -> Instant): Flow<PartitionEvent> = flow {
    var before: Set<NodeId>? = null
    membership.collect { config ->
        val now = config.allMembers
        before?.minus(now)?.forEach { gone -> emit(PartitionEvent.PeerLost(PeerId(gone.value), clock())) }
        before = now
    }
}
