import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// The board lattice, claims and peer identity, shared by every bee peer.
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
            // api: consumers of this module get the kuilt versions with it.
            api(project.dependencies.platform(libs.kuilt.bom))
            api(libs.kuilt.crdt)
            api(libs.kuilt.quilter)
            api(libs.kuilt.cluster)
            api(libs.kuilt.raft)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kuilt.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
