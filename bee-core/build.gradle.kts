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
            // The surface doc format and the JsonValue total order live once, in bee-a2ui,
            // which bee-core writes through. bee-a2ui depends only on kuilt-crdt, so no cycle.
            api(project(":bee-a2ui"))
            api(libs.kuilt.crdt)
            api(libs.kuilt.quilter)
            api(libs.kuilt.cluster)
            api(libs.kuilt.raft)
            // The board spoke (Task 3) is shaped like kuilt's RelaySpokeLoom: an HttpClient
            // and a KtorMeshClientLoom. kuilt-websocket keeps ktor-client-core as
            // `implementation`, so bee-core names it itself.
            api(libs.kuilt.websocket)
            api(libs.ktor.client.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            // BoardClient logs a board too large to sync, and measures its FullState in CBOR,
            // as the Quilter frames it (bee#65).
            implementation(libs.kotlin.logging)
            implementation(libs.kotlinx.serialization.cbor)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kuilt.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
