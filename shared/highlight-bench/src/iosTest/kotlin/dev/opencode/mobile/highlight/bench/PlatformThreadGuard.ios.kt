package dev.opencode.mobile.highlight.bench

import platform.Foundation.NSThread

internal actual fun platformThreadGuard(): HighlightBenchCallGuard = HighlightBenchThreadGuard()

internal actual fun onOtherThread(block: () -> Unit) {
    NSThread(block = block).start()
}
