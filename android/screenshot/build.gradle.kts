plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.paparazzi)
}

android {
    namespace = "dev.opencode.mobile.screenshot"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        aidl = false
        buildConfig = false
        shaders = false
    }
    sourceSets {
        getByName("main") {
            java.srcDir("../app/src/main/java/dev/opencode/mobile/theme")
            java.srcDir("../app/src/main/java/dev/opencode/mobile/ui/icons")
            assets.srcDir("../app/src/main/assets")
            res.srcDir("../app/src/main/res")
        }
    }
}

kotlin {
    jvmToolchain(21)
}

// recordPaparazziDebug is for throwaway snapshot evidence only.
// Do not add verifyPaparazzi* to check or Android Checks, and do not let
// testDebugUnitTest pick up gitignored snapshot tests on CI.
tasks.matching { it.name.startsWith("verifyPaparazzi") }.configureEach {
    enabled = false
}
val paparazziRequested =
    gradle.startParameter.taskNames.any { taskName ->
        taskName.contains("Paparazzi", ignoreCase = true)
    }
tasks.matching { it.name.startsWith("test") && it.name.contains("UnitTest") }.configureEach {
    enabled = paparazziRequested
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
