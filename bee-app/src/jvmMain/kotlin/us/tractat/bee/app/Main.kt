package us.tractat.bee.app

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import us.tractat.bee.core.HostAddress
import us.tractat.kuilt.crdt.ReplicaId
import java.security.SecureRandom
import kotlin.random.asKotlinRandom
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * `bee-app [<board link>]`: the desktop app. It joins the board its first argument names, else
 * the one `$BEE_URL` names, else it asks for a link. A link is a host's root or the join link a
 * `--lan` host prints ([HostAddress.parse]).
 */
@OptIn(ExperimentalUuidApi::class)
fun main(args: Array<String>) {
    val given = args.firstOrNull() ?: System.getenv("BEE_URL")?.takeIf { it.isNotBlank() }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val http = appHttpClient()
    val random = SecureRandom().asKotlinRandom()
    val clock = { Clock.System.now() }
    val join = { host: HostAddress -> joinBoard(host, http, scope, ReplicaId("app-${Uuid.random()}"), clock, random) }
    val start = given?.let { screenFor(it, join) } ?: AppScreen.AskForBoard()
    application {
        Window(onCloseRequest = ::exitApplication, title = "bee") { BeeApp(start, join) }
    }
}
