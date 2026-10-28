import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.CInteropProcess
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest

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
        binaries.all {
            linkerOpts("-L${project.file("native/libs/iosX64").absolutePath}", "-lonig")
        }
        binaries.framework {
            baseName = xcfName
            export(project(":highlight-bench"))
            export(project(":highlight-core"))
        }
        configureOnigurumaCinterop("iosX64")
    }
    iosArm64 {
        binaries.all {
            linkerOpts("-L${project.file("native/libs/iosArm64").absolutePath}", "-lonig")
        }
        binaries.framework {
            baseName = xcfName
            export(project(":highlight-bench"))
            export(project(":highlight-core"))
        }
        configureOnigurumaCinterop("iosArm64")
    }
    iosSimulatorArm64 {
        binaries.all {
            linkerOpts("-L${project.file("native/libs/iosSimulatorArm64").absolutePath}", "-lonig")
        }
        binaries.framework {
            baseName = xcfName
            export(project(":highlight-bench"))
            export(project(":highlight-core"))
        }
        configureOnigurumaCinterop("iosSimulatorArm64")
    }

    sourceSets {
        commonMain.dependencies {
            // The Highlighting Lab benchmark ships inside this framework so iOS keeps one Kotlin framework.
            api(project(":highlight-bench"))
            // The #872 highlighting library, compared in the Lab; nothing outside the Lab uses it yet.
            api(project(":highlight-core"))
            implementation(libs.jetbrains.markdown)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        val iosSimulatorArm64Test by getting {
            resources.srcDir("src/commonMain/resources")
            kotlin.srcDir(layout.buildDirectory.dir("generated/iosTestConstants"))
        }
        androidMain.dependencies {
            implementation(libs.joni)
        }
    }
}

private fun org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget.configureOnigurumaCinterop(konanTargetName: String) {
    val onigurumaHeaders = project.file("native/oniguruma-src/src")

    compilations.getByName("main") {
        cinterops {
            val oniguruma by creating {
                defFile(project.file("src/nativeInterop/cinterop/oniguruma.def"))
                packageName = "oniguruma"
                compilerOpts("-I${onigurumaHeaders.absolutePath}")
            }
        }
    }
}

val buildOnigurumaIos by tasks.registering(Exec::class) {
    group = "native"
    description = "Build static libonig archives for iOS Kotlin/Native targets."
    workingDir = project.file("native")
    commandLine("bash", "build-oniguruma-ios.sh")
    inputs.files(
        project.file("native/build-oniguruma-ios.sh"),
        project.file("native/oniguruma-version"),
        project.file("native/config.h"),
    )
    outputs.dir(project.file("native/libs"))
}

tasks.withType<CInteropProcess>().configureEach {
    dependsOn(buildOnigurumaIos)
}

val syncIosTestResources by tasks.registering(Copy::class) {
    description = "Stage TextMate grammar resources beside the iOS simulator test binary."
    dependsOn("iosSimulatorArm64TestProcessResources")
    from(layout.buildDirectory.dir("processedResources/iosSimulatorArm64/test"))
    into(layout.buildDirectory.dir("bin/iosSimulatorArm64/debugTest"))
}

val generateIosTestResourceRoot by tasks.registering {
    description = "Generate a compile-time constant for iOS simulator test resource lookup."
    dependsOn(syncIosTestResources)
    val outputDirectory = layout.buildDirectory.dir("generated/iosTestConstants")
    outputs.dir(outputDirectory)
    doLast {
        val resourceRoot =
            layout.buildDirectory
                .dir("bin/iosSimulatorArm64/debugTest")
                .get()
                .asFile
                .absolutePath
        val packageDir = outputDirectory.get().dir("dev/opencode/mobile/markdown").asFile
        packageDir.mkdirs()
        packageDir.resolve("IosTestResourceRoot.kt").writeText(
            """
            package dev.opencode.mobile.markdown

            internal const val IOS_TEST_RESOURCE_ROOT: String = "${resourceRoot.replace("\\", "\\\\").replace("\"", "\\\"")}"
            """.trimIndent(),
        )
    }
}

tasks.named("compileTestKotlinIosSimulatorArm64") {
    dependsOn(generateIosTestResourceRoot)
}

tasks.named<KotlinNativeTest>("iosSimulatorArm64Test") {
    dependsOn(syncIosTestResources)
}

android {
    namespace = "dev.opencode.mobile.markdown"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    sourceSets {
        getByName("main") {
            assets.srcDir("src/commonMain/resources")
        }
        getByName("test") {
            resources.srcDir("src/commonMain/resources")
        }
    }
}

tasks.withType<org.gradle.api.tasks.Copy>().configureEach {
    if (name.contains("iosSimulatorArm64Test", ignoreCase = true) &&
        name.contains("ProcessResources", ignoreCase = true)
    ) {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
}
