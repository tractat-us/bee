// SPIKE, throwaway: the shape of what bee-host's server needs, on iOS.
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket

fun startProbe() = embeddedServer(CIO, port = 8233, host = "0.0.0.0") {
    install(WebSockets)
    install(SSE)
    routing {
        get("/") { call.respondText("bee on iOS") }
        webSocket("/ws/board") { for (frame in incoming) outgoing.send(frame) }
        sse("/events") { send(data = "hello") }
    }
}.start(wait = false)
