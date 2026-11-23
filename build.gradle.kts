import org.jetbrains.kotlin.gradle.targets.js.npm.tasks.KotlinNpmInstallTask
import org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootExtension
import java.nio.file.Files

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
// Every target is wasmJs, so the wasm install is the only one, and it always takes bee-notions.
plugins.withType<WasmYarnPlugin> {
    tasks.named<KotlinNpmInstallTask>("kotlinWasmNpmInstall") {
        val notionsSources = fileTree("bee-notions") { exclude("node_modules/**") }
        inputs.files(notionsSources)
            .withPropertyName("beeNotions")
            .withPathSensitivity(PathSensitivity.RELATIVE)
        val yarnIntegrity = nodeModules.file(".yarn-integrity")
        val installReport = layout.buildDirectory.file("wasm/bee-notions-install-report.txt")
        doFirst {
            yarnIntegrity.get().asFile.delete()
            installReport.get().asFile.delete()
        }
        val notionsDir = file("bee-notions")
        val installed = nodeModules.dir("@bee/notions")
        doLast {
            val copy = installed.get().asFile
            if (!copy.isDirectory) {
                // Yarn exited 0, and the Kotlin plugin drops Yarn's output when it does, so say what
                // Yarn left behind. CI uploads the report with the rest of build/wasm's state (bee#269).
                val modules = copy.parentFile.parentFile
                val wasm = modules.parentFile
                val report = buildString {
                    appendLine("node_modules: ${modules.list()?.let { "${it.size} entries" } ?: "absent"}")
                    val scope = copy.parentFile
                    appendLine("node_modules/@bee: ${scope.list()?.sorted()?.ifEmpty { null } ?: "absent or empty"}")
                    if (Files.isSymbolicLink(copy.toPath())) {
                        appendLine("node_modules/@bee/notions: symlink to ${Files.readSymbolicLink(copy.toPath())}")
                    }
                    // doFirst deleted it, so a fresh one means Yarn finished linking and called the tree whole.
                    val integrity = yarnIntegrity.get().asFile
                    appendLine(
                        ".yarn-integrity: " + if (integrity.isFile) {
                            "written, ${integrity.length()} bytes, " +
                                (if ("@bee/notions" in integrity.readText()) "names" else "doesn't name") + " @bee/notions"
                        } else {
                            "absent"
                        },
                    )
                    val nested = wasm.resolve("packages").listFiles().orEmpty()
                        .filter { it.resolve("node_modules/@bee/notions").exists() }
                    appendLine("unhoisted copies: ${nested.map { it.name }.ifEmpty { "none" }}")
                    val lock = wasm.resolve("yarn.lock")
                    appendLine(
                        "yarn.lock: " + if (lock.isFile) {
                            (if ("\"@bee/notions@file:" in lock.readText()) "has" else "lacks") + " the @bee/notions entry"
                        } else {
                            "absent"
                        },
                    )
                }
                installReport.get().asFile.writeText(report)
                error("Yarn didn't install bee-notions at $copy (bee#93).\n$report")
            }
            // The whole tree, not only what webpack reads today, so a styles/ or assets/ added
            // later is checked too. If bee-notions gets a `files` field, compare the listed set
            // instead, since Yarn then copies only that (yarnpkg/yarn#2822).
            val sources = notionsSources.files.map { it.relativeTo(notionsDir) }
            // An empty tree would pass the comparison below having compared nothing.
            check(sources.isNotEmpty()) { "Found no bee-notions sources under $notionsDir (bee#93)." }
            val stale = sources.filterNot {
                copy.resolve(it).isFile && copy.resolve(it).readBytes().contentEquals(notionsDir.resolve(it).readBytes())
            }
            check(stale.isEmpty()) {
                "Yarn's copy of bee-notions in $copy doesn't match the sources (${stale.joinToString()}). " +
                    "Was a bee-notions file edited during the install? Run the build again; if it persists, " +
                    "delete build/wasm/node_modules and rerun (bee#80)."
            }
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
