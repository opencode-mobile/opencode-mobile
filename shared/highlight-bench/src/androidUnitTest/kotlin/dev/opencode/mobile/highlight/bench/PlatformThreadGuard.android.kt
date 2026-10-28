package dev.opencode.mobile.highlight.bench

internal actual fun platformThreadGuard(): HighlightBenchCallGuard = HighlightBenchThreadGuard()

internal actual fun onOtherThread(block: () -> Unit) {
    Thread(block).start()
}
