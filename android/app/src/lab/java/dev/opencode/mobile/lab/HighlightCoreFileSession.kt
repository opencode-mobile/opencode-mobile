package dev.opencode.mobile.lab

import dev.opencode.mobile.domain.filesystem.ProjectFileHighlightSession
import dev.opencode.mobile.domain.filesystem.ProjectFileLineSpans
import dev.opencode.mobile.highlight.core.HighlightCancellation
import dev.opencode.mobile.highlight.core.HighlightCore
import dev.opencode.mobile.highlight.core.HighlightDeadline
import dev.opencode.mobile.highlight.core.HighlightRole
import dev.opencode.mobile.highlight.core.HighlightSession
import dev.opencode.mobile.highlight.core.HighlightStatus
import dev.opencode.mobile.markdown.CodeSpan
import dev.opencode.mobile.markdown.CodeTokenRole

/**
 * A File Viewer file on a highlight-core [HighlightSession] (#872). Opening indexes the lines
 * and colors none; [highlight] colors a line range, running the state-only pass first when the
 * range is below the lines lexed so far. Lines a call does not reach stay plain, never guessed.
 */
class HighlightCoreFileSession private constructor(
    private val session: HighlightSession,
) : ProjectFileHighlightSession {
    override fun highlight(
        startLine: Int,
        endLine: Int,
        deadlineMillis: Double,
    ): ProjectFileLineSpans {
        val result = session.highlight(startLine, endLine, HighlightCancellation.Never, HighlightDeadline.after(deadlineMillis))
        // A lexer failure leaves the range plain for good, so the caller moves on.
        if (result.status == HighlightStatus.Failed) return ProjectFileLineSpans(result.startLine, result.endLine, emptyList())
        val until = if (result.highlightedEndLine >= session.lineCount) session.length else session.lineStart(result.highlightedEndLine)
        val spans = result.spans
        val codeSpans = ArrayList<CodeSpan>(spans.count)
        for (index in 0 until spans.count) {
            if (spans.start(index) >= until) break
            codeSpans += CodeSpan(spans.start(index), minOf(spans.end(index), until), roles[spans.roleCode(index)])
        }
        return ProjectFileLineSpans(result.startLine, result.highlightedEndLine, codeSpans)
    }

    companion object {
        /** highlight-core role codes to design-system roles, one to one by name. */
        private val roles = HighlightRole.entries.map { CodeTokenRole.valueOf(it.name) }.toTypedArray()

        /** A session for [source], or null when highlight-core does not know [language]. */
        fun open(
            language: String,
            source: String,
        ): HighlightCoreFileSession? {
            if (!HighlightCore.supports(language)) return null
            val session = HighlightSession(language)
            session.open(source, 0, HighlightCancellation.Never, HighlightDeadline.never)
            return HighlightCoreFileSession(session)
        }
    }
}
