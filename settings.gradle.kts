rootProject.name = "bee"

pluginManagement {
    repositories {
        // The Android Gradle plugin, for bee-app (#156).
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Provisions the JDK 25 toolchain every module pins, when the Gradle JVM differs.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Tigris S3 credentials (Fly), used by the TigrisKuilt repository below. Copied from
// fireworks-compose's settings.gradle.kts. Source order: gradle.properties
// (~/.gradle/gradle.properties locally), then env vars (CI org secrets). Absent means
// us.tractat.kuilt cannot resolve; never paper over that with dummy credentials.
val tigrisAccessKey: String? = providers.gradleProperty("S3_BUILD_CACHE_ACCESS_KEY_ID").orNull
    ?: System.getenv("S3_BUILD_CACHE_ACCESS_KEY_ID")
val tigrisSecretKey: String? = providers.gradleProperty("S3_BUILD_CACHE_SECRET_KEY").orNull
    ?: System.getenv("S3_BUILD_CACHE_SECRET_KEY")

dependencyResolutionManagement {
    repositories {
        // AndroidX and the Android build's own artifacts, for bee-app (#156).
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        maven {
            name = "TigrisKuilt"
            // The s3:// URL deliberately omits the bucket: the bucket is in the endpoint
            // subdomain (systemProp.org.gradle.s3.endpoint in gradle.properties). Works
            // around gradle/gradle#16638 (path-style URLs, which Tigris rejects).
            url = uri("s3://maven/")
            credentials(AwsCredentials::class) {
                accessKey = tigrisAccessKey
                secretKey = tigrisSecretKey
            }
            mavenContent { includeGroup("us.tractat.kuilt") }
        }
    }
}

// Side-by-side kuilt work: active only where a kuilt clone sits beside this checkout
// (the main checkout). Workers in .claude/worktrees/ and CI build against coordinates.
if (file("../kuilt").exists()) includeBuild("../kuilt")

include(
    ":bee-a2ui",
    ":bee-core",
    ":bee-web",
    ":bee-host",
    ":bee-mcp",
    ":bee-companion",
    ":bee-app",
)
