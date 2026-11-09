// SPIKE, throwaway. The whole app: one Activity whose screen is a WebView of the board the
// app hosts in-process. bee-host comes in as the jars of its installDist, unchanged.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // bee's jars carry Kotlin 2.4 metadata, newer than AGP's bundled Kotlin reads.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    id("com.android.application") version "9.2.1"
}

android {
    namespace = "us.tractat.bee.spike"
    compileSdk = 36
    defaultConfig {
        applicationId = "us.tractat.bee.spike"
        // java.util.HexFormat (bee-host's HostKeys) arrived in API 34.
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "spike"
    }
    buildTypes {
        // Only to measure what R8 makes of the APK; signed with the debug key.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "spike-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/versions/9/module-info.class",
                "META-INF/native/**",
                "META-INF/native-image/**",
            )
            pickFirsts += setOf("META-INF/*.kotlin_module", "**/module-info.class", "logback.xml")
        }
    }
}

val hostLib = rootDir.resolve("../../bee-host/build/install/bee-host/lib")

dependencies {
    implementation(
        fileTree(hostLib) {
            include("*.jar")
            // The app's own Kotlin brings the stdlib.
            exclude("kotlin-stdlib-*.jar", "annotations-*.jar")
            // Desktop-only native transports and QUIC: nothing Android can load.
            exclude("netty-codec-native-quic-*.jar", "netty-transport-native-*.jar")
        },
    )
}
