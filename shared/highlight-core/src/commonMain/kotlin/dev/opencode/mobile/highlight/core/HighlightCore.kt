package dev.opencode.mobile.highlight.core

/**
 * Entry point of the #872 highlighting library: which languages it knows and a one-shot call.
 * Pure and synchronous, with no threads or global mutable state; the apps own scheduling.
 * For streaming text or large files, use a [HighlightSession].
 */
object HighlightCore {
    /** Canonical ids of the supported languages. */
    val languages: List<String>
        get() = HighlightLanguages.ids

    /** True when [language] (an id or alias, any case) has a lexer. */
    fun supports(language: String): Boolean = HighlightLanguages.lexer(language) != null

    /** The canonical id for an id or alias, for example "json" for "jsonc"; null when unsupported. */
    fun canonicalLanguage(language: String): String? = HighlightLanguages.canonical(language)

    /** Highlights all of [text]; an unsupported language comes back complete with no spans. */
    fun highlight(
        language: String,
        text: String,
        cancel: HighlightCancellation,
        deadline: HighlightDeadline,
        limits: HighlightLimits = HighlightLimits.standard,
    ): HighlightCallResult = HighlightSession(language, limits).open(text, Int.MAX_VALUE, cancel, deadline)
}

/** Language ids and aliases to lexers. Lexers are immutable objects shared by every session. */
internal object HighlightLanguages {
    private class Entry(
        val id: String,
        val aliases: List<String>,
        val lexer: HighlightLexer,
    )

    private val registered =
        listOf(
            Entry("json", listOf("jsonc", "json5", "jsonl"), JsonLexer),
            Entry("diff", listOf("patch", "udiff"), DiffLexer),
            Entry("typescript", listOf("ts", "tsx", "mts", "cts"), CLikeLanguages.typeScript),
            Entry("javascript", listOf("js", "jsx", "mjs", "cjs"), CLikeLanguages.javaScript),
            Entry("kotlin", listOf("kt", "kts"), CLikeLanguages.kotlin),
            Entry("swift", emptyList(), CLikeLanguages.swift),
            Entry("python", listOf("py", "py3", "python3", "pyi"), PythonLexer),
        )

    private val byName: Map<String, Entry> =
        buildMap {
            registered.forEach { entry ->
                put(entry.id, entry)
                entry.aliases.forEach { put(it, entry) }
            }
        }

    val ids: List<String> = registered.map { it.id }

    fun normalize(language: String): String = language.trim().lowercase()

    fun lexer(language: String): HighlightLexer? = byName[normalize(language)]?.lexer

    fun canonical(language: String): String? = byName[normalize(language)]?.id
}
