import org.jetbrains.kotlin.gradle.targets.js.npm.tasks.KotlinNpmInstallTask
import org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootExtension

plugins {
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
}

// bee-web takes bee-notions as a `file:` npm dependency, which Yarn copies into
// build/wasm/node_modules. While the lockfile stays the same, Yarn's integrity check calls that
// copy current, so an edit to bee-notions never reached the install. Track its sources as an
// input, and drop the integrity file before a rerun so Yarn copies them again. bee-web's
// bundle and browser-test tasks track the copy (bee-web/build.gradle.kts), so the copy must
// match the sources: fail the install if it doesn't, rather than bundle old code (bee#80).
tasks.withType<KotlinNpmInstallTask>().configureEach {
    val notionsSources = fileTree("bee-notions") { exclude("node_modules/**") }
    inputs.files(notionsSources)
        .withPropertyName("beeNotions")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    val yarnIntegrity = nodeModules.file(".yarn-integrity")
    doFirst { yarnIntegrity.get().asFile.delete() }
    val notionsDir = file("bee-notions")
    val installed = nodeModules.dir("@bee/notions")
    doLast {
        val copy = installed.get().asFile
        // Only the wasm install takes bee-notions; the JS one has nothing to check.
        if (!copy.isDirectory) return@doLast
        // The modules webpack bundles: src/, and package.json for their entry point.
        val stale = notionsSources.files
            .map { it.relativeTo(notionsDir) }
            .filter { it.startsWith("src") || it.path == "package.json" }
            .filterNot { copy.resolve(it).isFile && copy.resolve(it).readBytes().contentEquals(notionsDir.resolve(it).readBytes()) }
        check(stale.isEmpty()) {
            "Yarn's copy of bee-notions in $copy doesn't match the sources (${stale.joinToString()}). " +
                "Was a bee-notions file edited during the install? Run the build again (bee#80)."
        }
    }
}

// @a2ui/markdown-it pins DOMPurify to exactly 3.4.11, which has published advisories. None is
// reachable with DOMPurify's default config and markdown-it's `html: false`, but 3.4.17 fixes them.
plugins.withType<WasmYarnPlugin> {
    the<WasmYarnRootExtension>().resolution("dompurify", "3.4.17")
}

// -Pbee.realsocket.tests=true turns on each task's one real-socket smoke. Forward it to
// every JVM Test task as a system property (smokes skip themselves with assumeTrue
// otherwise), mirroring kuilt-cluster/build.gradle.kts. Netty and the JDK's native
// transports call restricted methods, so JDK 25 needs native access granted when they run.
val realSocketTests: String? = providers.gradleProperty("bee.realsocket.tests").orNull
subprojects {
    tasks.withType<Test>().configureEach {
        if (realSocketTests != null) systemProperty("bee.realsocket.tests", realSocketTests)
        if (realSocketTests == "true") jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}
