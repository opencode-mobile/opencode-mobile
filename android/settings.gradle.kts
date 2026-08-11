pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Bytecode-patched markdown-jvm for Android 14-safe List removeFirst/Last.
        // See shared/markdown-core/libs/README.md and Play Console Kotlin incompatibilities.
        exclusiveContent {
            forRepository {
                maven {
                    url = uri("../shared/markdown-core/libs/repo")
                    name = "markdown-jvm-android15-compat"
                }
            }
            filter {
                includeModule("org.jetbrains", "markdown-jvm")
            }
        }
        google()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "OpenCode Mobile"
include(":app")
include(":markdown-core")
project(":markdown-core").projectDir = file("../shared/markdown-core")
