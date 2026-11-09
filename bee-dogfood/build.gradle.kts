// bee's own development board (bee#21): a seeder that joins a local board over HTTP MCP, as any
// Claude does, and draws the plan, the morning report and the open decisions from the repo's files.
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
}

kotlin { jvmToolchain(25) }

application { mainClass = "us.tractat.bee.dogfood.MainKt" }

dependencies {
    implementation(platform(libs.ktor.bom))
    implementation(libs.mcp.kotlin.sdk.client)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.snakeyaml)
    implementation(libs.kotlin.logging)
    runtimeOnly(libs.logback)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.kuilt.bom))
    testImplementation(libs.kuilt.test)
    // SeedOnHostTest seeds a real host, in process, over the same HTTP MCP endpoint.
    testImplementation(project(":bee-host"))
    testImplementation(project(":bee-core"))
}

// PlanBoardTest's smoke test reads the real docs/project/plan.yaml, so an edit to it reruns the tests.
// A failure prints the whole assertion, so a red CI run says which outcome was wrong.
tasks.test {
    testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    inputs.file(layout.projectDirectory.file("../docs/project/plan.yaml"))
        .withPropertyName("plan")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
