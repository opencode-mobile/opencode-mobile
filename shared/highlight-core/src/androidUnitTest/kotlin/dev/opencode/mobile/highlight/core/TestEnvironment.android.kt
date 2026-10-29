package dev.opencode.mobile.highlight.core

internal actual fun testEnvironment(name: String): String? = System.getenv(name)
