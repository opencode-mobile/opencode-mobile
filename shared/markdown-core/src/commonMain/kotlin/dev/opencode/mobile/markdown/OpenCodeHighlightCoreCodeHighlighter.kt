package dev.opencode.mobile.markdown

import dev.opencode.mobile.highlight.core.HighlightCancellation
import dev.opencode.mobile.highlight.core.HighlightCore
import dev.opencode.mobile.highlight.core.HighlightDeadline
import dev.opencode.mobile.highlight.core.HighlightStatus

/**
 * One-shot #872 highlight-core coloring for [OpenCodeMarkdownParser.parse], on the caller's thread.
 * Each call has its own [DEADLINE_MILLIS] deadline: past it the fence keeps its highlighted
 * prefix and the rest stays plain. A language highlight-core does not support (or no language)
 * comes back with no spans; a lexer failure comes back with no spans and a diagnostic.
 *
 * For fences that are highlighted again as they stream, [HighlightCoreHighlightingEngine] keeps a
 * session per fence and continues past the deadline on the next request.
 */
object OpenCodeHighlightCoreCodeHighlighter : OpenCodeCodeHighlighter {
    /** The per-call deadline, the same as the engine's default. */
    const val DEADLINE_MILLIS: Double = HighlightCoreHighlightingEngine.DEFAULT_DEADLINE_MILLIS

    override fun supportsLanguage(languageOrAlias: String?): Boolean = languageOrAlias != null && HighlightCore.supports(languageOrAlias)

    override fun highlight(
        languageOrAlias: String?,
        code: String,
    ): SyntaxHighlightResult = highlight(languageOrAlias, code, HighlightDeadline.after(DEADLINE_MILLIS))

    /** [highlight] with a caller's [deadline], for tests. */
    internal fun highlight(
        languageOrAlias: String?,
        code: String,
        deadline: HighlightDeadline,
    ): SyntaxHighlightResult {
        val language =
            languageOrAlias?.let(HighlightCore::canonicalLanguage)
                ?: return SyntaxHighlightResult(emptyList(), emptyList(), SyntaxHighlightSource.HighlightCore)
        val call = HighlightCore.highlight(language, code, HighlightCancellation.Never, deadline)
        // Nothing cancels these calls, so Complete and Partial are the only other statuses.
        if (call.status == HighlightStatus.Failed) {
            val message = call.failure ?: "highlight-core failed on '$language'; the fence stays plain."
            return SyntaxHighlightResult(
                spans = emptyList(),
                diagnostics = listOf(MarkdownDiagnostic(MarkdownDiagnosticKind.HighlighterFallback, message)),
                source = SyntaxHighlightSource.HighlightCore,
                canonicalLanguage = language,
            )
        }
        return SyntaxHighlightResult(
            spans = HighlightCoreHighlightingEngine.toCodeSpans(call.spans.toIntArray()),
            diagnostics = emptyList(),
            source = SyntaxHighlightSource.HighlightCore,
            canonicalLanguage = language,
        )
    }
}
