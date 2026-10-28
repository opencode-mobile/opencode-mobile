plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
}

// Release CI injects google-services.json; a clone without it builds Firebase-free.
if (file("google-services.json").isFile) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

val releaseArtifactTaskRequested =
    gradle.startParameter.taskNames.any { taskName ->
        taskName.contains("assembleRelease", ignoreCase = true) ||
            taskName.contains("bundleRelease", ignoreCase = true)
    }
val releaseEnvironmentNames =
    listOf(
        "ANDROID_UPLOAD_KEYSTORE_PATH",
        "ANDROID_UPLOAD_STORE_PASSWORD",
        "ANDROID_UPLOAD_KEY_ALIAS",
        "ANDROID_UPLOAD_KEY_PASSWORD",
        "ANDROID_VERSION_CODE",
        "ANDROID_VERSION_NAME",
    )
val releaseEnvironment =
    releaseEnvironmentNames.associateWith { name ->
        providers.environmentVariable(name).orNull?.takeIf(String::isNotBlank)
    }

// A Highlighting Lab build (#872) for App Distribution is signed with the upload key when CI
// provides it, so it installs over earlier distributed builds; locally it stays debug-signed.
val benchmarkArtifactTaskRequested =
    gradle.startParameter.taskNames.any { taskName -> taskName.contains("assembleBenchmark", ignoreCase = true) }
val uploadSigningAvailable =
    releaseEnvironmentNames.filter { it.startsWith("ANDROID_UPLOAD_") }.all { releaseEnvironment[it] != null }

if (releaseArtifactTaskRequested) {
    val missingEnvironment = releaseEnvironment.filterValues { it == null }.keys
    if (missingEnvironment.isNotEmpty()) {
        throw GradleException(
            "Release builds require CI-provided environment variables: " +
                missingEnvironment.sorted().joinToString(", "),
        )
    }
}

val releaseVersionCode = releaseEnvironment.getValue("ANDROID_VERSION_CODE")?.toIntOrNull()
val releaseVersionName = releaseEnvironment.getValue("ANDROID_VERSION_NAME")
val semanticVersionPattern = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?$")
val appVersionFile = rootProject.projectDir.resolve("../VERSION")
val defaultVersionName =
    appVersionFile
        .takeIf { it.isFile }
        ?.readText()
        ?.trim()
        .orEmpty()
if (defaultVersionName.isEmpty() || !Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$").matches(defaultVersionName)) {
    throw GradleException(
        "Repo VERSION must be MAJOR.MINOR.PATCH with integers only (got: '$defaultVersionName').",
    )
}
val verifyCrashlyticsValue =
    providers
        .environmentVariable("ANDROID_VERIFY_CRASHLYTICS")
        .orNull
        ?.takeIf(String::isNotBlank)
        ?.lowercase()
val verifyCrashlytics =
    verifyCrashlyticsValue?.toBooleanStrictOrNull()
        ?: if (verifyCrashlyticsValue == null) {
            false
        } else {
            throw GradleException("ANDROID_VERIFY_CRASHLYTICS must be true or false.")
        }
if (releaseArtifactTaskRequested && (releaseVersionCode == null || releaseVersionCode <= 0)) {
    throw GradleException("ANDROID_VERSION_CODE must be a positive integer for release builds.")
}
if (releaseArtifactTaskRequested && !semanticVersionPattern.matches(requireNotNull(releaseVersionName))) {
    throw GradleException(
        "ANDROID_VERSION_NAME must match MAJOR.MINOR.PATCH[-PRERELEASE], for example 0.1.0-internal.1.",
    )
}

ktlint {
    android.set(true)
    ignoreFailures.set(false)
    filter {
        exclude("**/OpenCodeIcons.kt")
        exclude("**/GeneratedSnapshotEvidence/**")
    }
}

android {
    namespace = "dev.opencode.mobile"
    // Compose BOM 2026.09 and hilt-navigation-compose 1.4 require compile SDK 37.
    // targetSdk stays 36 so the app does not opt in to new runtime behavior.
    compileSdk = 37
    // Pin NDK so release builds can package native debug symbols for Play.
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "dev.opencode.mobile"
        minSdk = 24
        targetSdk = 36
        versionCode = releaseVersionCode ?: 1
        versionName = releaseVersionName ?: defaultVersionName
    }

    signingConfigs {
        if (releaseArtifactTaskRequested || (benchmarkArtifactTaskRequested && uploadSigningAvailable)) {
            create("release") {
                storeFile = file(requireNotNull(releaseEnvironment.getValue("ANDROID_UPLOAD_KEYSTORE_PATH")))
                storePassword = requireNotNull(releaseEnvironment.getValue("ANDROID_UPLOAD_STORE_PASSWORD"))
                keyAlias = requireNotNull(releaseEnvironment.getValue("ANDROID_UPLOAD_KEY_ALIAS"))
                keyPassword = requireNotNull(releaseEnvironment.getValue("ANDROID_UPLOAD_KEY_PASSWORD"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // Resource shrinking stays off until minify is proven on Play-internal.
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release")
            manifestPlaceholders["crashlyticsVerificationEnabled"] = verifyCrashlytics
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            ndk {
                // Function names for Play / Android Vitals native stacks without FULL size cost.
                debugSymbolLevel = "SYMBOL_TABLE"
            }
        }
        // Release-like build for on-device Highlighting Lab numbers (#872): R8 on, not
        // debuggable, Firebase-free like debug, and signed with the debug key unless CI
        // provides the upload key for App Distribution.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            manifestPlaceholders["crashlyticsVerificationEnabled"] = false
            proguardFiles("proguard-benchmark.pro")
            ndk {
                debugSymbolLevel = "NONE"
            }
        }
    }
    compileOptions {
        // minSdk 24 has no java.time; desugaring backports it to API 24-25.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        aidl = false
        // BuildConfig.DEBUG gates logcat output and the Ktor Logging plugin (#755).
        buildConfig = true
        shaders = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    sourceSets {
        // The Highlighting Lab ships in debug and benchmark only; release has a no-op seam.
        getByName("debug") {
            java.srcDir("src/lab/java")
        }
        getByName("benchmark") {
            java.srcDir("src/lab/java")
            // Debug's Firebase-free feedback and observability seams.
            java.srcDir("src/debug/java")
        }
        getByName("testDebug") {
            java.srcDir("src/testLab/java")
        }
        getByName("test") {
            resources.srcDir("../../shared/sse-recovery")
            resources.srcDir("../../shared/feedback")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(project(":markdown-core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.uiautomator)

    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // Hilt's transitive kotlin-metadata-jvm stops at metadata 2.2. Kotlin 2.4 needs 2.4.x on the processor classpath.
    ksp(libs.kotlin.metadata.jvm)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.auth)
    implementation(libs.ktor.client.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    // QR pairing only. ZXing's QR reader stays in dex; bundled ML Kit barcode
    // scanning would ship a multi-megabyte native model this screen never uses.
    implementation(libs.zxing.core)

    releaseImplementation(platform(libs.firebase.bom))
    releaseImplementation(libs.firebase.crashlytics)
    releaseImplementation(libs.firebase.analytics)
    releaseImplementation(libs.firebase.config)
    releaseImplementation(libs.firebase.appcheck.playintegrity)
}
