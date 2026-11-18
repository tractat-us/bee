// SPIKE, throwaway (docs/spikes/2026-11-bee-app.md): does a Ktor CIO server compile for the iOS
// simulator? `./gradlew -p spike/ios-probe compileKotlinIosSimulatorArm64` from the repo root;
// add `-Pkuilt` to also try kuilt-websocket's server side (KtorRoomHost) on iOS.
rootProject.name = "bee-ios-probe"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

val tigrisAccessKey: String? = providers.gradleProperty("S3_BUILD_CACHE_ACCESS_KEY_ID").orNull
    ?: System.getenv("S3_BUILD_CACHE_ACCESS_KEY_ID")
val tigrisSecretKey: String? = providers.gradleProperty("S3_BUILD_CACHE_SECRET_KEY").orNull
    ?: System.getenv("S3_BUILD_CACHE_SECRET_KEY")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven {
            name = "TigrisKuilt"
            url = uri("s3://maven/")
            credentials(AwsCredentials::class) {
                accessKey = tigrisAccessKey
                secretKey = tigrisSecretKey
            }
            mavenContent { includeGroup("us.tractat.kuilt") }
        }
    }
}
