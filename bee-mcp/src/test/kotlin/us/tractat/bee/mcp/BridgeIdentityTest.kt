package us.tractat.bee.mcp

import kotlinx.coroutines.test.runTest
import us.tractat.bee.core.DepartReason
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.PeerKind
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class BridgeIdentityTest {
    @Test
    fun retireMintsAFreshInstanceAndReportsTheOld() = runTest {
        val reported = mutableListOf<Pair<PeerIdentity, DepartReason>>()
        // The report sees the fresh identity already in place.
        var currentDuringReport: PeerIdentity? = null
        lateinit var identity: BridgeIdentity
        identity = BridgeIdentity("bee", Random(7)) { old, reason ->
            reported += old to reason
            currentDuringReport = identity.current.value
        }
        val first = identity.current.value

        identity.retire(DepartReason.Cleared)

        val second = identity.current.value
        assertAll(
            { assertEquals(PeerKind.Agent, second.kind) },
            { assertEquals("bee", second.name) },
            { assertNotEquals(first.instance, second.instance) },
            { assertEquals(listOf(first to DepartReason.Cleared), reported) },
            { assertEquals(second, currentDuringReport) },
        )
    }
}
