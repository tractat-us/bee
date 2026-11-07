plugins {
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
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
