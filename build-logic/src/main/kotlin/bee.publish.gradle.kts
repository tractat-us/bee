import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

// The library modules bee publishes, modelled on kuilt's kuilt.publish convention. Applying this
// plugin is what makes a module published; PUBLISHING.md says which modules do and how a release
// is cut. Apply it before the Kotlin plugin or after: the KMP wiring waits for KMP.
plugins {
    `maven-publish`
    // The base plugin adds no repository and signs nothing by itself, so the gates below decide.
    id("com.vanniktech.maven.publish.base")
}

group = "us.tractat.bee"
// A continuous build is `<beeVersionLine>.0-dev`; CI and a release pass -Pversion=<x.y.z>.
if (version == Project.DEFAULT_VERSION) {
    version = "${providers.gradleProperty("beeVersionLine").get()}.0-dev"
}

plugins.withId("org.jetbrains.kotlin.multiplatform") {
    // Central requires a javadoc jar on every publication, and KMP emits none: publish an empty
    // one, as kuilt does. Sources jars are on by default.
    mavenPublishing {
        configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty()))
    }
    // Every published module dumps its ABI to api/, and `check` fails when the surface moves
    // without the dump: run `./gradlew updateKotlinAbi` and commit the diff. KGP 2.4: calling
    // abiValidation { } is what turns it on.
    extensions.configure<KotlinMultiplatformExtension> {
        @OptIn(ExperimentalAbiValidation::class)
        abiValidation { }
    }
}

// Only the KMP wiring above creates a publication, so on any other module this plugin would
// publish nothing and say nothing. Fail instead; a JVM-only library needs its own configure().
afterEvaluate {
    check(plugins.hasPlugin("org.jetbrains.kotlin.multiplatform")) {
        "${project.path} applies bee.publish but not Kotlin Multiplatform, so it would publish nothing."
    }
}

// Read now, not inside pom { }, which runs only once a publication exists: the missing-description
// error then fires for every module that applies this plugin, on any build.
val pomDescription = moduleDescription(project.name)

mavenPublishing {
    pom {
        name.set(project.name)
        description.set(pomDescription)
        url.set("https://github.com/tractat-us/bee")
        licenses {
            license {
                name.set("GNU Affero General Public License v3.0")
                url.set("https://www.gnu.org/licenses/agpl-3.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("keddie")
                name.set("Iain Keddie")
                url.set("https://github.com/keddie")
            }
        }
        scm {
            url.set("https://github.com/tractat-us/bee")
            connection.set("scm:git:https://github.com/tractat-us/bee.git")
            developerConnection.set("scm:git:ssh://git@github.com/tractat-us/bee.git")
        }
    }

    // Sign only when CI supplies a key (ORG_GRADLE_PROJECT_signingInMemoryKey, from the
    // SIGNING_KEY secret). A local publishToMavenLocal has none and must not fail for want of it.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    // The Central Portal repository exists only when CI supplies Central credentials, so no
    // local build can upload by accident. automaticRelease = false: an upload lands as a
    // pending deployment that a person releases by hand at central.sonatype.com.
    if (providers.gradleProperty("mavenCentralUsername").isPresent) {
        publishToMavenCentral(automaticRelease = false)
    }
}

// What Central's search and klibs.io show a reader for each module. No fallback, on purpose, as in
// kuilt (tractat-us/kuilt#2340): a generic `else` fires silently, so a module that applies this
// plugin without a line here fails configuration until it says what it does.
fun moduleDescription(module: String): String = when (module) {
    "bee-a2ui" ->
        "A2UI surfaces stored in a kuilt JSON CRDT: how a surface lays out in the document, the " +
            "view a renderer shows, the A2UI messages from one view to the next, and validation."
    "bee-core" ->
        "Join a bee board from any Kotlin app: the shared board state, the client that keeps a " +
            "replica in step with the host, presence, and exactly-once claims on actions."
    else -> error(
        "No POM description for '$module'. Add a line to moduleDescription() in " +
            "build-logic/src/main/kotlin/bee.publish.gradle.kts saying what the module does: " +
            "it's what Maven Central search and klibs.io show a reader."
    )
}
