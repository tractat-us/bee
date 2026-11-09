package us.tractat.bee.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.plus
import us.tractat.bee.core.HostAddress
import us.tractat.kuilt.crdt.ReplicaId
import java.security.SecureRandom
import kotlin.random.asKotlinRandom
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The Android app: one Activity whose whole screen is the board. It asks for a board's link on
 * start; remembering it, hosting on the phone and the board-only page are later slices (#156).
 */
class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalUuidApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val http = appHttpClient()
        val random = SecureRandom().asKotlinRandom()
        val clock = { Clock.System.now() }
        // The replica merges off the main thread, and ends with the Activity.
        val scope = lifecycleScope + Dispatchers.Default
        val join = { host: HostAddress -> joinBoard(host, http, scope, ReplicaId("app-${Uuid.random()}"), clock, random) }
        setContent { BeeApp(AppScreen.AskForBoard(), join) }
    }
}
