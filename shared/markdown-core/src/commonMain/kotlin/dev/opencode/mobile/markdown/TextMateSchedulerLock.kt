package dev.opencode.mobile.markdown

/**
 * Short critical sections around [HighlightCoreHighlightingEngine] state, so a newer revision
 * can cancel a call still running on another thread.
 */
internal interface TextMateSchedulerLock {
    fun <T> withLock(block: () -> T): T
}

internal expect fun createTextMateSchedulerLock(): TextMateSchedulerLock
