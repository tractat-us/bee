// The MCP bridge: a Claude session's replica of the board (Tasks 5 and 7).
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
}

kotlin { jvmToolchain(25) }

application { mainClass = "us.tractat.bee.mcp.MainKt" }

dependencies {
    implementation(platform(libs.kuilt.bom))
    implementation(project(":bee-core"))
    implementation(project(":bee-a2ui"))
    implementation(libs.kuilt.websocket)
    implementation(libs.kuilt.gossip)
    implementation(libs.kuilt.liveness)
    implementation(libs.mcp.kotlin.sdk.server)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.server.netty)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlin.logging)
    runtimeOnly(libs.logback)
    testImplementation(kotlin("test"))
    testImplementation(libs.kuilt.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mcp.kotlin.sdk.client)
}
