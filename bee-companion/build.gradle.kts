// The brainstorming visual companion, run on the board (Task 8).
plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

kotlin { jvmToolchain(25) }

application { mainClass = "us.tractat.bee.companion.MainKt" }

dependencies {
    implementation(platform(libs.kuilt.bom))
    implementation(project(":bee-core"))
    implementation(project(":bee-a2ui"))
    // The companion joins the board as a session does: BeeBridge, BridgeIdentity and the claiming
    // BoardActionSource are the bridge's.
    implementation(project(":bee-mcp"))
    implementation(libs.kuilt.websocket)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlin.logging)
    runtimeOnly(libs.logback)
    testImplementation(kotlin("test"))
    testImplementation(libs.kuilt.test)
    testImplementation(libs.kotlinx.coroutines.test)
    // bee-host's in-memory voter and relay, as bee-mcp's tests use them.
    testImplementation(testFixtures(project(":bee-host")))
}
