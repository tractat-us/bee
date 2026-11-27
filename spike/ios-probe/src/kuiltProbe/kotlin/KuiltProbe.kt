// SPIKE, throwaway: bee-host's two kuilt server pieces, named from iOS code.
import us.tractat.kuilt.websocket.KtorConnectionSource
import us.tractat.kuilt.websocket.KtorRoomHost

val roomHost: KotlinClass = KtorRoomHost::class.let { KotlinClass }
val connectionSource: KotlinClass = KtorConnectionSource::class.let { KotlinClass }

object KotlinClass
