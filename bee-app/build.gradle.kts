import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The bee app (#156): a Compose Multiplatform shell whose whole screen is the board. Desktop JVM
// always; Android only with -Pbee.android=true, since it needs an Android SDK and its lint and
// variant tests would slow every `check`. iOS waits on kuilt: KtorRoomHost and
// KtorConnectionSource are JVM and Android only (#156, "kuilt gaps").
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val withAndroid = providers.gradleProperty("bee.android").orNull == "true"
if (withAndroid) apply(plugin = libs.plugins.androidApplication.get().pluginId)

kotlin {
    jvmToolchain(25)
    jvm()
    if (withAndroid) {
        androidTarget {
            // Android runs Java 11 bytecode; the desktop target keeps the toolchain's 25.
            compilerOptions { jvmTarget = JvmTarget.JVM_11 }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project.dependencies.platform(libs.kuilt.bom))
            implementation(project.dependencies.platform(libs.ktor.bom))
            implementation(project(":bee-core"))
            implementation(libs.kuilt.websocket)
            implementation(libs.kuilt.gossip)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.kotlinx.coroutines.core)
            // CIO dials on desktop, Android and, later, iOS.
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.websockets)
            implementation(libs.kotlin.logging)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            runtimeOnly(libs.logback)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.compose.ui.test)
            // bee-host's in-memory relay: the app joins it as it joins a real host.
            implementation(project.dependencies.testFixtures(project(":bee-host")))
        }
        if (withAndroid) {
            androidMain.dependencies {
                implementation(libs.androidx.activity.compose)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "us.tractat.bee.app.MainKt"
    }
}

if (withAndroid) {
    extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
        namespace = "us.tractat.bee.app"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        defaultConfig {
            applicationId = "us.tractat.bee.app"
            minSdk = libs.versions.android.minSdk.get().toInt()
            targetSdk = libs.versions.android.compileSdk.get().toInt()
            versionCode = 1
            versionName = "0.1"
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }
    }
}
