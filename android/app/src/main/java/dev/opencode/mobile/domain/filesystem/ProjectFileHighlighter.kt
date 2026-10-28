package dev.opencode.mobile.domain.filesystem

import dev.opencode.mobile.HighlightingEngineSeam
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

    /**
     * A session that colors [source] a range of lines at a time, or null to use [highlight] on
     * the whole file. Only the #872 developer engine returns one.
     */
    fun openSession(
        path: String,
        grammar: String,
        source: String,
    ): ProjectFileHighlightSession? = null
}

/**
 * One open file colored progressively (#872): the File Viewer asks for the visible lines first,
 * then the rest in chunks. Lines are those of [dev.opencode.mobile.ui.components.OpenCodeCodeLines.split];
 * offsets index into the whole source. Calls come one at a time, off the main thread.
 */
interface ProjectFileHighlightSession {
    /**
     * Colors lines [startLine, endLine), stopping once [deadlineMillis] passes. Coloring far below
     * the lines colored so far first runs a pass that only computes line states, so a call may
     * return before it reaches [startLine]; calling again continues from where it stopped.
     */
    fun highlight(
        startLine: Int,
        endLine: Int,
        deadlineMillis: Double,
    ): ProjectFileLineSpans
}

/** Spans for lines [startLine, endLine) of a [ProjectFileHighlightSession]; empty when none were reached. */
data class ProjectFileLineSpans(
    val startLine: Int,
    val endLine: Int,
    val spans: List<CodeSpan>,
)

/**
 * One whole-file pass on the shared TextMate engine (same one Session Chat code fences use), off
 * the main thread, or a progressive session when the #872 developer engine is selected. The semantic fallback covers a TextMate result that is not attachable (failed,
 * cancelled, or over budget, as in `attachTextMateResult`) and the user turning TextMate off.
 */
class TextMateProjectFileHighlighter internal constructor(
    private val engine: TextMateHighlightingEngine,
    private val workDispatcher: CoroutineDispatcher,
    private val sessionFactory: (grammar: String, source: String) -> ProjectFileHighlightSession? = { _, _ -> null },
    private val textMateEnabled: () -> Boolean,
) : ProjectFileHighlighter {
    @Inject
    constructor(engine: TextMateHighlightingEngine) : this(
        engine,
        Dispatchers.Default,
        HighlightingEngineSeam::fileSession,
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

    /** The developer engine's session when it is selected and knows [grammar]; null keeps the whole-file pass. */
    override fun openSession(
        path: String,
        grammar: String,
        source: String,
    ): ProjectFileHighlightSession? = if (textMateEnabled()) sessionFactory(grammar, source) else null

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
