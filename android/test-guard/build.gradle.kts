plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

// Test-only support shared by the unit tests of :app, :hexcode and :opencode-kit: blocks real
// network access and catches process-wide state that leaks between test classes. Depend on it with
// testImplementation and apply ../gradle/test-guard.gradle.kts to the module (see TestIsolationGuard).
kotlin {
    jvmToolchain(17)
}

apply(from = rootProject.file("gradle/test-guard.gradle.kts"))

dependencies {
    implementation(libs.junit.platform.launcher)
    runtimeOnly(libs.junit.vintage.engine)
    // Only for the Dispatchers.Main probe; every module that uses the guard has it on its test classpath.
    compileOnly(libs.kotlinx.coroutines.test)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.vintage.engine)
}
