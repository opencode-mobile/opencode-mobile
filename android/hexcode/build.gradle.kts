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

// Hex Code: the voice-first OpenCode app. It shares OpenCode Mobile's data and domain code through :opencode-kit, and
// has its own UI kit (designsystem/).
android {
    namespace = "dev.opencode.hexcode"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.opencode.hexcode"
        // 26 so the adaptive launcher icon needs no bitmap fallbacks
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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
