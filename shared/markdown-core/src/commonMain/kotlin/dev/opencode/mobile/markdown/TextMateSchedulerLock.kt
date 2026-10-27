package dev.opencode.mobile.markdown

/**
 * Short critical sections around [TextMateRequestScheduler] state, so admits can
 * run on another thread while a highlight job executes and cancel it.
 */
internal interface TextMateSchedulerLock {
    fun <T> withLock(block: () -> T): T
}

internal expect fun createTextMateSchedulerLock(): TextMateSchedulerLock
