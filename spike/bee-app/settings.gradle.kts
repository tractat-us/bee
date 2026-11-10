// SPIKE, throwaway (docs/spikes/2026-11-bee-app.md): can a phone host a bee board?
// A standalone build, not part of bee's. Build bee-host first (`./gradlew :bee-host:installDist`),
// then run this from the repo root: `./gradlew -p spike/bee-app assembleDebug`.
rootProject.name = "bee-app-spike"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
