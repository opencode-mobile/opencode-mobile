import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

// The #872 syntax highlighting library: hand-written lexers behind a session API. Pure Kotlin
// with no dependencies, exported to iOS through markdown-core's framework.
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
    namespace = "dev.opencode.mobile.highlight.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
    }
}

// The randomized run reads HIGHLIGHT_FUZZ_SECONDS / HIGHLIGHT_FUZZ_SEED; pass them through to tests.
tasks.withType<Test>().configureEach {
    listOf("HIGHLIGHT_FUZZ_SECONDS", "HIGHLIGHT_FUZZ_SEED").forEach { name ->
        System.getenv(name)?.let { environment(name, it) }
    }
}
