package dev.opencode.mobile.highlight.bench

internal actual fun platformThreadGuard(): HighlightBenchCallGuard = HighlightBenchThreadGuard()
