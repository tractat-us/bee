import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// The A2UI wire model and the surface mapping. Depends only on kuilt-crdt, so it can
// graduate to kuilt.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvmToolchain(25)
    jvm()
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { nodejs() }

    sourceSets {
        commonMain.dependencies {
            implementation(project.dependencies.platform(libs.kuilt.bom))
            api(libs.kuilt.crdt)
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kuilt.test)
        }
    }
}
