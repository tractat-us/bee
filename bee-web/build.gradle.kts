import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpack
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

// The bee client in the browser: a kuilt replica in wasm, behind the stock A2UI Lit
// renderer (bee-notions). Modelled on kuilt's demo/web/build.gradle.kts, but with tests on.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

// kuilt's Karma timeouts are private to its convention plugin, so bee carries its own
// (karma.config.d/timeouts.js). The directory Karma reads is generated: it is a copy of
// karma.config.d plus, with -Pbee.realsocket.tests=true, fixture.js (see below).
val karmaConfigDir = layout.buildDirectory.dir("karma-config.d")

kotlin {
    jvmToolchain(25)
    // external declarations and JsAny (A2uiRenderer.kt) are experimental wasm/JS interop.
    compilerOptions { optIn.add("kotlin.js.ExperimentalWasmJsInterop") }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig { outputFileName = "bee.js" }
            testTask {
                useKarma {
                    useChromeHeadless()
                    useConfigDirectory(karmaConfigDir.get().asFile)
                }
            }
        }
        binaries.executable()
    }

    sourceSets {
        val wasmJsMain by getting {
            dependencies {
                implementation(project.dependencies.platform(libs.kuilt.bom))
                implementation(project(":bee-core"))
                implementation(project(":bee-a2ui"))
                implementation(libs.kuilt.quilter)
                implementation(libs.kuilt.websocket)
                implementation(libs.kuilt.gossip)
                implementation(libs.kuilt.cluster)
                implementation(libs.kuilt.store)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.browser)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.js) // browser WebSocket engine
                implementation(libs.ktor.client.websockets)
                // The A2UI renderer host, with @a2ui/lit and @a2ui/web_core pinned in its package.json.
                implementation(npm(project.file("../bee-notions")))
            }
        }
        val wasmJsTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kuilt.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

// webpack (the bundle, and Karma's test bundle) reads bee-notions from its copy in node_modules,
// which these tasks' own inputs leave out. The root build re-copies it when its sources change;
// tracking the same sources here reruns the tasks that bundle it.
val notionsSources = fileTree("../bee-notions") { exclude("node_modules/**") }
tasks.withType<KotlinWebpack>().configureEach {
    inputs.files(notionsSources).withPropertyName("beeNotions").withPathSensitivity(PathSensitivity.RELATIVE)
}
tasks.withType<KotlinJsTest>().configureEach {
    inputs.files(notionsSources).withPropertyName("beeNotions").withPathSensitivity(PathSensitivity.RELATIVE)
}

// The production page, for bee-host to pack under `web/`.
val webDistribution: Configuration by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts {
    add(webDistribution.name, tasks.named("wasmJsBrowserDistribution").map { it.outputs.files.singleFile }) {
        builtBy("wasmJsBrowserDistribution")
    }
}

// The fixture voter's classpath, as a file bee-host writes (its `fixtureVoterClasspath`).
val fixtureVoterClasspath: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    fixtureVoterClasspath(project(path = ":bee-host", configuration = "fixtureVoterClasspath"))
}

val karmaConfig by tasks.registering(Sync::class) {
    from("karma.config.d")
    into(karmaConfigDir)
    // The directory's content depends on the flag: a flagged run adds fixture.js (written by
    // wasmJsBrowserTest), which names a voter that is dead by the next run. Declaring the flag as
    // an input re-runs this Sync when it flips, and Sync deletes files it did not copy.
    inputs.property("realSocketTests", providers.gradleProperty("bee.realsocket.tests").map { it == "true" }.orElse(false))
}

/**
 * The fixture Raft voter for the browser claim test (Task 6): a JVM process started from
 * bee-host's test classpath before the browser test and stopped after it. It reports its
 * port as a line `BEE_FIXTURE_VOTER_PORT=<n>` on stdout. A voter that exits without one
 * (the Task 1 placeholder) leaves the port unset, and a test that needs it fails on that.
 */
abstract class FixtureVoterService : BuildService<FixtureVoterService.Params>, AutoCloseable {
    interface Params : BuildServiceParameters {
        val javaExecutable: Property<String>
        /** One file holding the voter's classpath: bee-host's `fixtureVoterClasspath`. */
        val classpathFile: ConfigurableFileCollection
        val mainClass: Property<String>
        val logFile: RegularFileProperty
    }

    private var process: Process? = null

    fun start(): Int? {
        val log = parameters.logFile.get().asFile.apply { parentFile.mkdirs() }
        val started = ProcessBuilder(
            parameters.javaExecutable.get(),
            "-cp",
            parameters.classpathFile.singleFile.readText(),
            parameters.mainClass.get(),
        ).redirectErrorStream(true).start()
        process = started
        val port = CompletableFuture<Int?>()
        Thread {
            log.bufferedWriter().use { out ->
                started.inputStream.bufferedReader().forEachLine { line ->
                    out.appendLine(line)
                    out.flush()
                    PORT_LINE.matchEntire(line)?.let { port.complete(it.groupValues[1].toInt()) }
                }
            }
            port.complete(null)
        }.apply { isDaemon = true }.start()
        return port.get(60, TimeUnit.SECONDS)
    }

    fun stop() {
        process?.let {
            it.destroy()
            if (!it.waitFor(10, TimeUnit.SECONDS)) it.destroyForcibly()
        }
        process = null
    }

    override fun close() = stop()

    private companion object {
        val PORT_LINE = Regex("BEE_FIXTURE_VOTER_PORT=(\\d+)")
    }
}

val realSocketTests = providers.gradleProperty("bee.realsocket.tests").orNull == "true"

tasks.named("wasmJsBrowserTest") {
    dependsOn(karmaConfig)
    if (realSocketTests) {
        val voter = gradle.sharedServices.registerIfAbsent("beeFixtureVoter", FixtureVoterService::class) {
            // Resolved now, to a plain path: a provider chained on the toolchain service
            // cannot be restored from the configuration cache.
            parameters.javaExecutable = javaToolchains
                .launcherFor { languageVersion = JavaLanguageVersion.of(25) }
                .get().executablePath.asFile.absolutePath
            parameters.classpathFile.from(fixtureVoterClasspath)
            parameters.mainClass = "us.tractat.bee.host.fixtures.FixtureVoterMainKt"
            parameters.logFile = layout.buildDirectory.file("fixture-voter.log")
        }
        usesService(voter)
        inputs.files(fixtureVoterClasspath)
        val fixtureJs = karmaConfigDir.map { it.file("fixture.js").asFile }
        val portJs = layout.buildDirectory.file("fixture-voter-port.js").map { it.asFile }
        doFirst {
            val port = voter.get().start()
            if (port == null) logger.warn("Fixture voter exited without reporting a port; see build/fixture-voter.log.")
            portJs.get().writeText("window.__BEE_FIXTURE_VOTER_PORT__ = ${port ?: "undefined"};\n")
            val portPath = portJs.get().absolutePath.replace("\\", "\\\\")
            fixtureJs.get().writeText(
                "// Generated by bee-web/build.gradle.kts: loads the fixture voter's port first.\n" +
                    "config.files.unshift({ pattern: \"$portPath\", included: true, served: true, watched: false });\n",
            )
        }
        doLast { voter.get().stop() }
    }
}
