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
// bundle and browser-test tasks track the same sources (bee-web/build.gradle.kts).
tasks.withType<KotlinNpmInstallTask>().configureEach {
    inputs.files(fileTree("bee-notions") { exclude("node_modules/**") })
        .withPropertyName("beeNotions")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    val yarnIntegrity = nodeModules.file(".yarn-integrity")
    doFirst { yarnIntegrity.get().asFile.delete() }
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
