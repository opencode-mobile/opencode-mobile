import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ktlint)
}

// Hex's speech engine for Hex Code on Android: transcribe.cpp (the engine Hex desktop runs) and the same
// Parakeet V2 model file, both pinned in hex-upstream.json by scripts/sync-hex.mjs.
@Suppress("UNCHECKED_CAST")
val hexPin = JsonSlurper().parse(file("../../hex-upstream.json")) as Map<String, Any>

@Suppress("UNCHECKED_CAST")
val model = hexPin.getValue("androidModel") as Map<String, Any>

android {
    namespace = "dev.opencode.voiceengine"
    compileSdk = 37
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 26
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
        buildConfigField(
            "String",
            "MODEL_URL",
            "\"https://huggingface.co/${model["repository"]}/resolve/${model["revision"]}/${model["filename"]}\"",
        )
        buildConfigField("String", "MODEL_FILE", "\"${model["filename"]}\"")
        buildConfigField("long", "MODEL_BYTES", "${model["bytes"]}L")
        buildConfigField("String", "MODEL_SHA256", "\"${model["sha256"]}\"")
    }

    buildFeatures { buildConfig = true }

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
}
