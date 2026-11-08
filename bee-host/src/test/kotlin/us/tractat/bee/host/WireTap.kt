package us.tractat.bee.host

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onEach
import us.tractat.kuilt.core.fabric.Connection

/**
 * One spoke's view of its wire to the hub: the size of every frame it sends ([up]) and receives
 * ([down]), and a switch that drops what the hub sends it ([dropDown]). Frames are whole fabric
 * messages, gossip and mesh headers included, so a size here is what a socket would carry.
 */
internal class WireTap {
    val up = mutableListOf<Int>()
    val down = mutableListOf<Int>()

    /** While true, frames from the hub are lost before the spoke sees them, and not counted. */
    var dropDown = false

    fun wrap(spokeEnd: Connection): Connection = Tapped(spokeEnd)

    fun clear() {
        up.clear()
        down.clear()
    }

    private inner class Tapped(private val base: Connection) : Connection by base {
        override suspend fun send(frame: ByteArray) {
            up += frame.size
            base.send(frame)
        }

        override val incoming: Flow<ByteArray> = base.incoming.filter { !dropDown }.onEach { down += it.size }
    }
}
