package dev.opencode.mobile.markdown

data class SyntaxHighlightResult(
    val spans: List<CodeSpan>,
    val diagnostics: List<MarkdownDiagnostic>,
    val source: SyntaxHighlightSource = SyntaxHighlightSource.Unknown,
    val canonicalLanguage: String? = null,
)

enum class SyntaxHighlightSource {
    /** Colored by the #872 highlight-core library ([OpenCodeHighlightCoreCodeHighlighter]). */
    HighlightCore,

    /** Block structure only; empty spans so chrome paints before coloring. */
    StructureOnly,
    Unknown,
}

/** Colors one code fence synchronously, for [OpenCodeMarkdownParser.parse]. */
interface OpenCodeCodeHighlighter {
    fun supportsLanguage(languageOrAlias: String?): Boolean

    fun highlight(
        languageOrAlias: String?,
        code: String,
    ): SyntaxHighlightResult
}

/**
 * Fence-aware highlighter that returns no token spans. Used for structure-first
 * streaming publication so code-block chrome/monospace paint before highlight-core
 * colors upgrade in place.
 */
object OpenCodeStructureOnlyHighlighter : OpenCodeCodeHighlighter {
    override fun supportsLanguage(languageOrAlias: String?): Boolean = true

    override fun highlight(
        languageOrAlias: String?,
        code: String,
    ): SyntaxHighlightResult =
        SyntaxHighlightResult(
            spans = emptyList(),
            diagnostics = emptyList(),
            source = SyntaxHighlightSource.StructureOnly,
        )
}
