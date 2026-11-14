plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
}

// OpenCode Kit: the data and domain code OpenCode Mobile (:app) and Hex Code (:hexcode) share. No UI lives here; each
// app has its own UI kit. Code moved here from :app keeps its `dev.opencode.mobile.*` packages, so :app reads it as before.
android {
    namespace = "dev.opencode.kit"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        // minSdk 24 has no java.time; desugaring backports it to API 24-25.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        // BuildConfig.DEBUG gates logcat output and the Ktor Logging plugin (#755).
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Hilt annotations only (`@ApplicationContext`); :app runs the Hilt processor.
    compileOnly(libs.hilt.android)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    api(libs.javax.inject)
    api(libs.androidx.core.ktx)
    api(libs.kotlinx.serialization.json)
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.auth)
    implementation(libs.ktor.client.logging)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // Decodes pairing QR codes from camera frames; each app draws its own camera preview.
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}
