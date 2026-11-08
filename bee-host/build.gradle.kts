// The always-on host: board relay, backup replica and the single Raft voter (Tasks 3, 6, 9).
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
    // The in-memory voter and relay rigs, shared with bee-mcp's tests (Task 7).
    `java-test-fixtures`
}

kotlin { jvmToolchain(25) }

application { mainClass = "us.tractat.bee.host.MainKt" }

// The browser page, served by the host at `/`: bee-web's production distribution,
// consumed through bee-web's `webDistribution` configuration and packed under `web/` by `webJar`.
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
    implementation(libs.kotlinx.serialization.cbor)
    implementation(libs.kotlin.logging)
    runtimeOnly(libs.logback)

    testFixturesApi(platform(libs.kuilt.bom))
    testFixturesApi(project(":bee-core"))
    testFixturesApi(libs.kuilt.test)
    testFixturesApi(libs.kuilt.store)
    testFixturesApi(libs.kotlinx.coroutines.test)

    testImplementation(kotlin("test"))
    testImplementation(libs.kuilt.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.websockets)

    webDistribution(project(path = ":bee-web", configuration = "webDistribution"))
}

// The page rides in its own jar, on the host's runtime only: the application distribution,
// `run` and this project's tests. Not in the main jar, so a consumer of the test fixtures
// (bee-mcp's tests) never builds the browser bundle.
val webJar by tasks.registering(Jar::class) {
    description = "Packs bee-web's distribution under web/, for the host to serve at /."
    archiveClassifier = "web"
    from(webDistribution) { into("web") }
}
tasks.startScripts { classpath = classpath!! + files(webJar) }
distributions { main { contents { into("lib") { from(webJar) } } } }
tasks.named<JavaExec>("run") { classpath(webJar) }
tasks.test { classpath += files(webJar) }

// bee-web's build starts FixtureVoterMain from this project's test runtime classpath
// before its browser test. It may not resolve this project's configurations while its own
// task runs, so this task writes the resolved path to a file. bee-web consumes that file
// through the `fixtureVoterClasspath` configuration, the way this project consumes
// bee-web's `webDistribution`.
val writeFixtureVoterClasspath by tasks.registering {
    description = "Writes the fixture voter's runtime classpath to build/fixture-voter-classpath.txt."
    val classpath = sourceSets.test.get().runtimeClasspath
    val out = layout.buildDirectory.file("fixture-voter-classpath.txt")
    inputs.files(classpath)
    outputs.file(out)
    doLast { out.get().asFile.writeText(classpath.asPath) }
}
val fixtureVoterClasspath: Configuration by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts {
    add(fixtureVoterClasspath.name, layout.buildDirectory.file("fixture-voter-classpath.txt")) {
        builtBy(writeFixtureVoterClasspath)
    }
}

// Playwright's globalSetup runs this to write the fixture surfaces through a JVM
// BoardClient: `./gradlew :bee-host:e2eSeed -Pbee.e2e.host=<url>` runs
// `E2eSeederMain --host <url>`. The seeder seeds every fixture by default; Task 4's
// seeder may also take `--fixture <name>` to seed only that one.
tasks.register<JavaExec>("e2eSeed") {
    group = "verification"
    description = "Writes the Playwright fixture surfaces onto a running host."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "us.tractat.bee.host.fixtures.E2eSeederMainKt"
    providers.gradleProperty("bee.e2e.host").orNull?.let { args("--host", it) }
}
