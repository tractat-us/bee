// Not "build-logic": the main checkout also includes ../kuilt, whose own build-logic would share the name.
rootProject.name = "bee-build-logic"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}
