import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    applyDefaultHierarchyTemplate()

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    val xcfName = "OpenCodeMarkdownCore"
    iosX64 {
        binaries.framework {
            baseName = xcfName
            export(project(":highlight-bench"))
            export(project(":highlight-core"))
        }
    }
    iosArm64 {
        binaries.framework {
            baseName = xcfName
            export(project(":highlight-bench"))
            export(project(":highlight-core"))
        }
    }
    iosSimulatorArm64 {
        binaries.framework {
            baseName = xcfName
            export(project(":highlight-bench"))
            export(project(":highlight-core"))
        }
    }

    sourceSets {
        commonMain.dependencies {
            // The Highlighting Lab benchmark ships inside this framework so iOS keeps one Kotlin framework.
            api(project(":highlight-bench"))
            // The #872 highlighting library: every code fence is colored by it, and the apps call it directly.
            api(project(":highlight-core"))
            implementation(libs.jetbrains.markdown)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "dev.opencode.mobile.markdown"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
    }
}
