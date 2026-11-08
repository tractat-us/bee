package us.tractat.bee.host

import io.ktor.server.application.Application
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine

/**
 * The host's Netty server on [host]:[port] (0 picks a free port), running [module]. Every server
 * that carries the host's routes is built here, so tests run the engine configuration the host runs.
 */
fun hostServer(
    host: String,
    port: Int,
    module: Application.() -> Unit,
): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> =
    embeddedServer(
        Netty,
        configure = {
            connector {
                this.host = host
                this.port = port
            }
        },
        module = module,
    )
