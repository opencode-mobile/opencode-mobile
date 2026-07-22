plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ktlint)
}

val releaseTaskRequested =
    gradle.startParameter.taskNames.any { taskName ->
        taskName.contains("Release", ignoreCase = true)
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

if (releaseTaskRequested) {
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

if (releaseTaskRequested && (releaseVersionCode == null || releaseVersionCode <= 0)) {
    throw GradleException("ANDROID_VERSION_CODE must be a positive integer for release builds.")
}
if (releaseTaskRequested && !semanticVersionPattern.matches(requireNotNull(releaseVersionName))) {
    throw GradleException(
        "ANDROID_VERSION_NAME must match MAJOR.MINOR.PATCH[-PRERELEASE], for example 0.1.0-internal.1.",
    )
}

ktlint {
    android.set(true)
    ignoreFailures.set(false)
    filter {
        exclude("**/OpenCodeIcons.kt")
    }
}

android {
    namespace = "dev.opencode.mobile"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.opencode.mobile"
        minSdk = 24
        targetSdk = 36
        versionCode = releaseVersionCode ?: 1
        versionName = releaseVersionName ?: "1.0"
    }

    signingConfigs {
        if (releaseTaskRequested) {
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
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        aidl = false
        buildConfig = false
        shaders = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(project(":markdown-core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
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

    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
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
}
