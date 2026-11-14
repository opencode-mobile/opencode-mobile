package dev.opencode.mobile.domain.message

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.math.max
import kotlin.math.min

object PromptSendProgress {
    const val VISIBLE_BODY_THRESHOLD: Long = 32_768

    fun percent(
        bytesSent: Long,
        bytesTotal: Long,
    ): Int {
        if (bytesTotal <= 0) return 0
        val raw = ((bytesSent.toDouble() / bytesTotal.toDouble()) * 100).toInt()
        return min(99, max(0, raw))
    }

    fun shouldShow(
        bytesTotal: Long,
        hasAttachments: Boolean,
    ): Boolean = hasAttachments || bytesTotal >= VISIBLE_BODY_THRESHOLD

    fun label(percent: Int): String = if (percent <= 0) "Sending…" else "Sending $percent%"
}

class PromptSendProgressReporter(
    val onProgress: (Long, Long) -> Unit,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PromptSendProgressReporter>
}
