package dev.opencode.mobile.markdown

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal actual fun createTextMateSchedulerLock(): TextMateSchedulerLock =
    object : TextMateSchedulerLock {
        private val lock = ReentrantLock()

        override fun <T> withLock(block: () -> T): T = lock.withLock(block)
    }
