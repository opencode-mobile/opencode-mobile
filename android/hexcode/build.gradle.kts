import com.android.build.api.variant.BuildConfigField
import java.io.ByteArrayOutputStream
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ktlint)
}

// App Distribution (.github/workflows/hexcode-app-distribution.yml) signs release builds with OpenCode Mobile's
// upload key and stamps the version, both from the environment as in :app. Without them, release stays unsigned and
// keeps the version below.
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
val uploadEnvironment = releaseEnvironment.filterKeys { it.startsWith("ANDROID_UPLOAD_") }
val missingUploadEnvironment = uploadEnvironment.filterValues { it == null }.keys.sorted()
val uploadSigningAvailable = missingUploadEnvironment.isEmpty()
val releaseArtifactTaskRequested =
    gradle.startParameter.taskNames.any { it.contains("assembleRelease", ignoreCase = true) }
// Half a signing setup would quietly ship an unsigned APK, so a release build stops instead.
if (releaseArtifactTaskRequested && !uploadSigningAvailable && missingUploadEnvironment.size < uploadEnvironment.size) {
    throw GradleException("Hex Code release signing is missing ${missingUploadEnvironment.joinToString(", ")}.")
}
val releaseVersionCodeValue = releaseEnvironment.getValue("ANDROID_VERSION_CODE")
val releaseVersionCode = releaseVersionCodeValue?.toIntOrNull()
if (releaseVersionCodeValue != null && (releaseVersionCode == null || releaseVersionCode <= 0)) {
    throw GradleException("ANDROID_VERSION_CODE must be a positive integer (got: '$releaseVersionCodeValue').")
}
val releaseVersionName = releaseEnvironment.getValue("ANDROID_VERSION_NAME")
val semanticVersionPattern = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?$")
if (releaseVersionName != null && !semanticVersionPattern.matches(releaseVersionName)) {
    throw GradleException(
        "ANDROID_VERSION_NAME must match MAJOR.MINOR.PATCH[-PRERELEASE], for example 0.1.0-hex.1 " +
            "(got: '$releaseVersionName').",
    )
}

// Hex Code: the voice-first OpenCode app. It shares OpenCode Mobile's data and domain code through :opencode-kit, and
// has its own UI kit (designsystem/).
android {
    testOptions {
        // android.util.Log and friends return defaults in unit tests, as in :opencode-kit.
        unitTests.isReturnDefaultValues = true
    }

    namespace = "dev.opencode.hexcode"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.opencode.hexcode"
        // 26 so the adaptive launcher icon needs no bitmap fallbacks
        minSdk = 26
        targetSdk = 36
        // App Distribution reads the marketing version from versionName; keep it MAJOR.MINOR.PATCH.
        versionCode = releaseVersionCode ?: 1
        versionName = releaseVersionName ?: "0.1.0"
    }

    signingConfigs {
        if (uploadSigningAvailable) {
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
        }
    }

    compileOptions {
        // :opencode-kit keeps OpenCode Mobile's API 24 floor, which backports java.time.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

/** The checkout's short commit for Settings › Developer › Build, or nothing when git or the checkout is missing. */
abstract class GitCommit : ValueSource<String, GitCommit.Params> {
    interface Params : ValueSourceParameters {
        val dir: Property<String>
    }

    @get:Inject
    abstract val exec: ExecOperations

    override fun obtain(): String? =
        runCatching {
            val out = ByteArrayOutputStream()
            val result =
                exec.exec {
                    commandLine("git", "-C", parameters.dir.get(), "rev-parse", "--short", "HEAD")
                    standardOutput = out
                    errorOutput = ByteArrayOutputStream()
                    isIgnoreExitValue = true
                }
            out.toString().trim().takeIf { result.exitValue == 0 && it.isNotEmpty() }
        }.getOrNull()
}

val gitCommit = providers.of(GitCommit::class) { parameters.dir.set(rootDir.path) }.orElse("")

// Set lazily, so a new commit doesn't invalidate the configuration cache; only BuildConfig regenerates.
androidComponents {
    onVariants { variant ->
        variant.buildConfigFields?.put(
            "GIT_COMMIT",
            gitCommit.map { BuildConfigField("String", "\"$it\"", "Short git commit, empty when unknown") },
        )
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":opencode-kit"))
    implementation(project(":voice-engine"))
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.ktor.client.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    // Holds the system splash until home's first state is ready (app-plan.md, Home › Cold start).
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // The pairing camera; :opencode-kit decodes its frames.
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.serialization.kotlinx.json)
}
