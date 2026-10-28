package dev.opencode.mobile.domain.filesystem

import dev.opencode.mobile.markdown.CodeSpan
import javax.inject.Inject

/** Syntax coloring for File Viewer text files. */
interface ProjectFileHighlighter {
    /**
     * A session that colors [source] a range of lines at a time, or null when [grammar] has no
     * highlighter and the file stays plain.
     */
    fun openSession(
        path: String,
        grammar: String,
        source: String,
    ): ProjectFileHighlightSession?
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

/** File Viewer coloring on the #872 highlight-core library, one [HighlightCoreFileSession] per open file. */
class HighlightCoreProjectFileHighlighter
    @Inject
    constructor() : ProjectFileHighlighter {
        override fun openSession(
            path: String,
            grammar: String,
            source: String,
        ): ProjectFileHighlightSession? = HighlightCoreFileSession.open(grammar, source)
    }
