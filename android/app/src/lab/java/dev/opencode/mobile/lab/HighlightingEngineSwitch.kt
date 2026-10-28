package dev.opencode.mobile.lab

import dev.opencode.mobile.highlight.core.HighlightCancellation
import dev.opencode.mobile.markdown.HighlightCoreHighlightingEngine
import dev.opencode.mobile.markdown.HighlightRequest
import dev.opencode.mobile.markdown.HighlightResult
import dev.opencode.mobile.markdown.MemoryPressure
import dev.opencode.mobile.markdown.TextMateHighlightingEngine
import dev.opencode.mobile.markdown.TextMateSchedulerSnapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job

/**
 * The Session Chat engine in developer builds: [textMate] unless the Highlighting engine setting
 * picks highlight-core, read per request so switching takes effect at once. highlight-core runs
 * on the caller's thread (the message preparer's worker), never on TextMate's owner thread, and
 * stops when the calling coroutine is cancelled; its unsupported languages go to [textMate].
 */
class HighlightingEngineSwitch(
    private val textMate: TextMateHighlightingEngine,
    private val highlightCore: HighlightCoreHighlightingEngine = HighlightCoreHighlightingEngine(textMate),
    private val usesHighlightCore: () -> Boolean = { HighlightingEnginePreferences.usesHighlightCore },
) : TextMateHighlightingEngine {
    private class JobCancellation(
        private val job: Job,
    ) : HighlightCancellation {
        override val isCancelled: Boolean
            get() = !job.isActive
    }

    override suspend fun highlight(request: HighlightRequest): HighlightResult {
        if (!usesHighlightCore()) {
            // Switched back: free the sessions now rather than at the next memory trim.
            if (highlightCore.sessionCount > 0) highlightCore.trimNow(MemoryPressure.Critical)
            return textMate.highlight(request)
        }
        if (!highlightCore.supports(request.language)) return textMate.highlight(request)
        return highlightCore.highlightNow(request, JobCancellation(currentCoroutineContext().job))
    }

    override suspend fun trim(level: MemoryPressure) {
        // Trims the TextMate engine too: it is highlight-core's fallback.
        highlightCore.trim(level)
    }

    override fun schedulerSnapshot(): TextMateSchedulerSnapshot? = textMate.schedulerSnapshot()
}
