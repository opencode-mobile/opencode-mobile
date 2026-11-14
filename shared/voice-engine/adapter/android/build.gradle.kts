import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ktlint)
}

// Hex Code's on-device speech engines on Android: sherpa-onnx (the default), Google's LiteRT and Android's own
// recognizer, with a small native library (src/main/cpp/) that pins their threads to the fast cores. The models
// they run are listed in src/main/assets/lab/models.json.

// sherpa-onnx (Apache-2.0, ONNX Runtime inside), the second engine the speech model lab compares. Its Android
// release is fetched, checked against this checksum and unpacked into the build folder, so its native libraries
// stay out of the repo. The fetch is a task, not configuration-time code: this module is configured on every build
// of the Android project (OpenCode Mobile's release builds and the iOS markdown-core build phase included), and
// only tasks that actually build voice-engine may touch the network.
val sherpaVersion = "2.0.1"
val sherpaSha256 = "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"

@CacheableTask
abstract class FetchSherpaOnnx : DefaultTask() {
    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:OutputFile
    abstract val classesJar: RegularFileProperty

    @get:OutputDirectory
    abstract val jniDir: DirectoryProperty

    @get:Inject
    abstract val archives: ArchiveOperations

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun fetch() {
        val aar = File(temporaryDir, "sherpa-onnx.aar")
        URI("https://github.com/k2-fsa/sherpa-onnx/releases/download/v${version.get()}/sherpa-onnx-${version.get()}.aar")
            .toURL()
            .openStream()
            .use { input -> aar.outputStream().use { input.copyTo(it) } }
        val sha =
            MessageDigest
                .getInstance("SHA-256")
                .digest(aar.readBytes())
                .joinToString("") { "%02x".format(it) }
        check(sha == sha256.get()) { "The sherpa-onnx download does not match its pinned checksum" }
        val jar = classesJar.get().asFile
        files.copy {
            from(archives.zipTree(aar)) { include("classes.jar") }
            into(jar.parentFile)
            rename { jar.name }
        }
        files.sync {
            from(archives.zipTree(aar)) { include("jni/arm64-v8a/**") }
            into(jniDir)
            eachFile { path = path.removePrefix("jni/") }
            includeEmptyDirs = false
        }
        aar.delete()
    }
}

val fetchSherpaOnnx =
    tasks.register<FetchSherpaOnnx>("fetchSherpaOnnx") {
        version.set(sherpaVersion)
        sha256.set(sherpaSha256)
        classesJar.set(layout.buildDirectory.file("sherpa-onnx-$sherpaVersion/classes.jar"))
        jniDir.set(layout.buildDirectory.dir("sherpa-onnx-$sherpaVersion/jni"))
    }

android {
    namespace = "dev.opencode.voiceengine"
    compileSdk = 37
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 26
        ndk { abiFilters += "arm64-v8a" }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(fetchSherpaOnnx, FetchSherpaOnnx::jniDir)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.android)
    implementation(files(fetchSherpaOnnx.flatMap { it.classesJar }))
    // Google's LiteRT (Apache-2.0), the third engine the lab compares, with its GPU accelerator
    implementation(libs.litert)
    implementation(libs.litert.gpu)
}
