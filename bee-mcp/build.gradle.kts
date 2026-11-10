// The MCP bridge: a Claude session's replica of the board (Tasks 5 and 7).
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
}

kotlin { jvmToolchain(25) }

application { mainClass = "us.tractat.bee.mcp.MainKt" }

// ToolsPageTest reads docs/tools.md, from this module's directory, and checks it names every tool
// and prompt the bridge serves. PluginCommandsTest reads the plugin's skills, whose command skills
// copy the prompts, and its two manifests, Claude Code's and Codex's. Both are inputs, so an edit
// to either reruns the tests.
tasks.test {
    inputs.file(layout.projectDirectory.file("../docs/tools.md"))
        .withPropertyName("toolsPage")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(layout.projectDirectory.dir("../plugins/bee"))
        .withPropertyName("plugin")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    implementation(platform(libs.kuilt.bom))
    implementation(platform(libs.ktor.bom))
    implementation(project(":bee-core"))
    implementation(project(":bee-a2ui"))
    implementation(libs.kuilt.websocket)
    implementation(libs.kuilt.gossip)
    implementation(libs.kuilt.liveness)
    implementation(libs.mcp.kotlin.sdk.server)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.server.netty)
    // HttpBridges reads an opening POST's clientInfo before the SDK reads the same body.
    implementation(libs.ktor.server.double.receive)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlin.logging)
    runtimeOnly(libs.logback)
    testImplementation(kotlin("test"))
    testImplementation(libs.kuilt.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mcp.kotlin.sdk.client)
    // PeerHttpTest serves a WebSocket that never pings, so only the client's ping can find a dead link.
    testImplementation(libs.ktor.server.websockets)
    // bee-host's in-memory voter and relay, for the bridge's claims and board tests (Task 7).
    testImplementation(testFixtures(project(":bee-host")))
}
