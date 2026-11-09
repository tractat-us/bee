// SPIKE, throwaway. A Ktor CIO server with WebSockets and SSE, compiled for iosSimulatorArm64.
plugins {
    kotlin("multiplatform") version "2.4.20"
}

kotlin {
    iosSimulatorArm64()
    sourceSets {
        iosMain.dependencies {
            implementation("io.ktor:ktor-server-cio:3.6.1")
            implementation("io.ktor:ktor-server-websockets:3.6.1")
            implementation("io.ktor:ktor-server-sse:3.6.1")
            if (providers.gradleProperty("kuilt").isPresent) {
                implementation("us.tractat.kuilt:kuilt-websocket:0.7.0-dev.1696")
            }
        }
        if (providers.gradleProperty("kuilt").isPresent) {
            iosMain.get().kotlin.srcDir("src/kuiltProbe/kotlin")
        }
    }
}
