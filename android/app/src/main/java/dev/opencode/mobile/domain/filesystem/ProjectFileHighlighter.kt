package dev.opencode.mobile.domain.filesystem

import dev.opencode.mobile.domain.preferences.MarkdownHighlightPreferences
import dev.opencode.mobile.markdown.CodeBlockKey
import dev.opencode.mobile.markdown.CodeSpan
import dev.opencode.mobile.markdown.HighlightIntent
import dev.opencode.mobile.markdown.HighlightRequest
import dev.opencode.mobile.markdown.HighlightRevision
import dev.opencode.mobile.markdown.OpenCodeSemanticFallbackHighlighter
import dev.opencode.mobile.markdown.TextMateHighlightingEngine
import dev.opencode.mobile.markdown.TextMateParseOutcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/** Syntax spans for a File Viewer text file; offsets index into the given source. */
interface ProjectFileHighlighter {
    suspend fun highlight(
        path: String,
        grammar: String,
        source: String,
    ): List<CodeSpan>
}

/**
 * One whole-file pass on the shared TextMate engine (same one Session Chat code fences use), off
 * the main thread. The semantic fallback covers a TextMate result that is not attachable (failed,
 * cancelled, or over budget, as in `attachTextMateResult`) and the user turning TextMate off.
 */
class TextMateProjectFileHighlighter internal constructor(
    private val engine: TextMateHighlightingEngine,
    private val workDispatcher: CoroutineDispatcher,
    private val textMateEnabled: () -> Boolean,
) : ProjectFileHighlighter {
    @Inject
    constructor(engine: TextMateHighlightingEngine) : this(
        engine,
        Dispatchers.Default,
        { MarkdownHighlightPreferences.textMateEnabled },
    )

    private val revisions = AtomicLong(0)

    override suspend fun highlight(
        path: String,
        grammar: String,
        source: String,
    ): List<CodeSpan> =
        withContext(workDispatcher) {
            if (!textMateEnabled()) return@withContext fallback(grammar, source)
            val result =
                engine.highlight(
                    HighlightRequest(
                        key = CodeBlockKey("files:${path.length}:$path"),
                        revision = HighlightRevision(revisions.incrementAndGet()),
                        language = grammar,
                        source = source,
                        intent = HighlightIntent.InteractiveFinal,
                        policyEpoch = MarkdownHighlightPreferences.epoch.toLong(),
                    ),
                )
            if (result.outcome in unattachableOutcomes) fallback(grammar, source) else result.spans
        }

    private fun fallback(
        grammar: String,
        source: String,
    ): List<CodeSpan> = OpenCodeSemanticFallbackHighlighter.highlight(grammar, source).spans

    private companion object {
        /** Mirrors the outcomes `attachTextMateResult` rejects for Session Chat fences. */
        val unattachableOutcomes =
            setOf(
                TextMateParseOutcome.Failed,
                TextMateParseOutcome.Cancelled,
                TextMateParseOutcome.BudgetExceeded,
            )
    }
}
