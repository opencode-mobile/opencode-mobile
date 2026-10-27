package dev.opencode.mobile.markdown

import platform.Foundation.NSLock

internal actual fun createTextMateSchedulerLock(): TextMateSchedulerLock =
    object : TextMateSchedulerLock {
        private val lock = NSLock()

        override fun <T> withLock(block: () -> T): T {
            lock.lock()
            try {
                return block()
            } finally {
                lock.unlock()
            }
        }
    }
