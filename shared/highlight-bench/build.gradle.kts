import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

// Benchmark inputs and measuring code for the #872 Highlighting Lab, shared by the host
// tests and both apps. Exported to iOS through markdown-core's framework.
kotlin {
    applyDefaultHierarchyTemplate()

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "dev.opencode.mobile.highlight.bench"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
    }
}
