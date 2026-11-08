// The brainstorming visual companion, run on the board (Task 8).
plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

kotlin { jvmToolchain(25) }

application { mainClass = "us.tractat.bee.companion.MainKt" }

dependencies {
    implementation(platform(libs.ktor.bom))
    implementation(project(":bee-core"))
    implementation(project(":bee-a2ui"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
}
