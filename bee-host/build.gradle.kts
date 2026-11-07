// The always-on host: board relay, backup replica and the single Raft voter (Tasks 3, 6, 9).
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
}

kotlin { jvmToolchain(25) }

application { mainClass = "us.tractat.bee.host.MainKt" }

// The browser page, served by the host at `/`: bee-web's production distribution,
// consumed through bee-web's `webDistribution` configuration and packed under `web/`.
val webDistribution: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    implementation(platform(libs.kuilt.bom))
    implementation(project(":bee-core"))
    implementation(project(":bee-a2ui"))
    implementation(libs.kuilt.websocket)
    implementation(libs.kuilt.gossip)
    implementation(libs.kuilt.cluster)
    implementation(libs.kuilt.raft)
    implementation(libs.kuilt.liveness)
    implementation(libs.kuilt.store)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlin.logging)
    runtimeOnly(libs.logback)

    testImplementation(kotlin("test"))
    testImplementation(libs.kuilt.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.websockets)

    webDistribution(project(path = ":bee-web", configuration = "webDistribution"))
}

tasks.processResources {
    from(webDistribution) { into("web") }
}

// Playwright's globalSetup runs this to write fixture surfaces through a JVM BoardClient.
// The host to write to is passed as -Pbee.e2e.host=<url>.
tasks.register<JavaExec>("e2eSeed") {
    group = "verification"
    description = "Writes the Playwright fixture surfaces onto a running host."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "us.tractat.bee.host.fixtures.E2eSeederMainKt"
    providers.gradleProperty("bee.e2e.host").orNull?.let { args(it) }
}
