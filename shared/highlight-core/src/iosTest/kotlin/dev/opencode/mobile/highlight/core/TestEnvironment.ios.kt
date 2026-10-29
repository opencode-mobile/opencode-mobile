package dev.opencode.mobile.highlight.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

// On the simulator, set SIMCTL_CHILD_<name> so the variable reaches the test process.
@OptIn(ExperimentalForeignApi::class)
internal actual fun testEnvironment(name: String): String? = getenv(name)?.toKString()
