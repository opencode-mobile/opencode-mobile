import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ktlint)
}

// Hex's speech engine for Hex Code on Android: transcribe.cpp (the engine Hex desktop runs), pinned in
// hex-upstream.json by scripts/sync-hex.mjs. The models it can run are listed in src/main/assets/lab/models.json.

// sherpa-onnx (Apache-2.0, ONNX Runtime inside), the second engine the speech model lab compares. Its Android
// release is fetched once, checked against this checksum and unpacked into the build folder, so its native
// libraries stay out of the repo.
val sherpaVersion = "2.0.1"
val sherpaSha256 = "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"
val sherpaDir =
    layout.buildDirectory
        .dir("sherpa-onnx-$sherpaVersion")
        .get()
        .asFile
if (!File(sherpaDir, "classes.jar").isFile) {
    sherpaDir.mkdirs()
    val aar = File(sherpaDir, "sherpa-onnx.aar")
    uri("https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar")
        .toURL()
        .openStream()
        .use { input -> aar.outputStream().use { input.copyTo(it) } }
    val sha =
        MessageDigest
            .getInstance("SHA-256")
            .digest(aar.readBytes())
            .joinToString("") { "%02x".format(it) }
    check(sha == sherpaSha256) { "The sherpa-onnx download does not match its pinned checksum" }
    copy {
        from(zipTree(aar)) { include("classes.jar", "jni/arm64-v8a/**") }
        into(sherpaDir)
    }
    aar.delete()
}

android {
    namespace = "dev.opencode.voiceengine"
    compileSdk = 37
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 26
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
    }

    sourceSets["main"].jniLibs.srcDir(File(sherpaDir, "jni"))

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

dependencies {
    api(libs.kotlinx.coroutines.android)
    implementation(files(File(sherpaDir, "classes.jar")))
    // Google's LiteRT (Apache-2.0), the third engine the lab compares, with its GPU accelerator
    implementation(libs.litert)
    implementation(libs.litert.gpu)
}
