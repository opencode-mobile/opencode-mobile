package dev.opencode.mobile.markdown

data class SyntaxHighlightResult(
    val spans: List<CodeSpan>,
    val diagnostics: List<MarkdownDiagnostic>,
)

interface OpenCodeCodeHighlighter {
    fun supportsLanguage(languageOrAlias: String?): Boolean

    fun highlight(
        languageOrAlias: String?,
        code: String,
    ): SyntaxHighlightResult
}

interface OpenCodeTextMateTokenizer {
    fun tokenize(
        grammar: TextMateGrammarPayload,
        code: String,
        grammarBundle: TextMateGrammarBundle? = null,
    ): TextMateTokenizationResult
}

data class TextMateTokenizationResult(
    val tokens: List<TextMateScopeToken>,
    val diagnostics: List<MarkdownDiagnostic> = emptyList(),
)

class OpenCodeSyntaxHighlighter(
    private val grammarBundle: TextMateGrammarBundle? = null,
    private val textMateTokenizer: OpenCodeTextMateTokenizer? = null,
    private val fallbackHighlighter: OpenCodeCodeHighlighter = NativeSemanticFallbackHighlighter,
) : OpenCodeCodeHighlighter {
    override fun supportsLanguage(languageOrAlias: String?): Boolean =
        normalizedLanguage(languageOrAlias)?.let { language ->
            grammarBundle?.manifest?.contains(language) ?: fallbackHighlighter.supportsLanguage(language)
        } ?: true

    override fun highlight(
        languageOrAlias: String?,
        code: String,
    ): SyntaxHighlightResult {
        val grammar = normalizedLanguage(languageOrAlias)?.let { language -> grammarBundle?.primaryGrammarFor(language) }
        val tokenizer = textMateTokenizer
        if (grammar != null && tokenizer != null) {
            val tokenization = tokenizer.tokenize(grammar, code, grammarBundle)
            val spans =
                tokenization.tokens
                    .map(OpenCodeTextMateScopeMapper::codeSpanFor)
                    .filter { it.role != CodeTokenRole.Plain && it.start < it.end }
                    .mergeAdjacentSemanticSpans(code)
            if (spans.isNotEmpty()) {
                return SyntaxHighlightResult(
                    spans = spans,
                    diagnostics = tokenization.diagnostics,
                )
            }
        }

        return fallbackHighlighter.highlight(languageOrAlias, code)
    }

    companion object {
        val default: OpenCodeSyntaxHighlighter = OpenCodeSyntaxHighlighter()

        private fun normalizedLanguage(languageOrAlias: String?): String? =
            languageOrAlias?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

        private fun List<CodeSpan>.mergeAdjacentSemanticSpans(code: String): List<CodeSpan> =
            fold(emptyList()) { merged, span ->
                val previous = merged.lastOrNull()
                if (previous != null && previous.end == span.start && previous.canMergeWith(span, code)) {
                    merged.dropLast(1) + previous.copy(end = span.end)
                } else {
                    merged + span
                }
            }

        private fun CodeSpan.canMergeWith(
            next: CodeSpan,
            code: String,
        ): Boolean {
            if (role != next.role) return false
            if (role != CodeTokenRole.Punctuation) return true

            val currentText = code.substring(start, end)
            val nextText = code.substring(next.start, next.end)
            return !currentText.isStringDelimiterPunctuation() && !nextText.isStringDelimiterPunctuation()
        }

        private fun String.isStringDelimiterPunctuation(): Boolean = this == "\"" || this == "'"
    }
}

internal object NativeSemanticFallbackHighlighter : OpenCodeCodeHighlighter {
    private val supportedLanguageAliases =
        setOf(
            "bash",
            "cjs",
            "css",
            "cts",
            "diff",
            "go",
            "html",
            "javascript",
            "js",
            "json",
            "kotlin",
            "kt",
            "kts",
            "markdown",
            "md",
            "mjs",
            "mts",
            "patch",
            "python",
            "py",
            "rust",
            "rs",
            "shell",
            "shellscript",
            "sh",
            "swift",
            "typescript",
            "ts",
            "yaml",
            "yml",
            "zsh",
        )

    private val keywordRoles =
        mapOf(
            "fun" to CodeTokenRole.Keyword,
            "func" to CodeTokenRole.Keyword,
            "let" to CodeTokenRole.Keyword,
            "val" to CodeTokenRole.Keyword,
            "var" to CodeTokenRole.Keyword,
            "class" to CodeTokenRole.Keyword,
            "struct" to CodeTokenRole.Keyword,
            "enum" to CodeTokenRole.Keyword,
            "interface" to CodeTokenRole.Keyword,
            "object" to CodeTokenRole.Keyword,
            "import" to CodeTokenRole.Keyword,
            "package" to CodeTokenRole.Keyword,
            "return" to CodeTokenRole.Keyword,
            "if" to CodeTokenRole.Keyword,
            "else" to CodeTokenRole.Keyword,
            "for" to CodeTokenRole.Keyword,
            "while" to CodeTokenRole.Keyword,
            "switch" to CodeTokenRole.Keyword,
            "when" to CodeTokenRole.Keyword,
            "async" to CodeTokenRole.Keyword,
            "await" to CodeTokenRole.Keyword,
            "const" to CodeTokenRole.Keyword,
            "type" to CodeTokenRole.Keyword,
            "interface" to CodeTokenRole.Keyword,
            "public" to CodeTokenRole.Keyword,
            "private" to CodeTokenRole.Keyword,
            "internal" to CodeTokenRole.Keyword,
            "true" to CodeTokenRole.Constant,
            "false" to CodeTokenRole.Constant,
            "null" to CodeTokenRole.Constant,
            "nil" to CodeTokenRole.Constant,
        )

    private val tokenRegex =
        Regex(
            pattern =
                """"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|//.*$|#.*$|/\*.*?\*/|\b\d+(?:\.\d+)?\b|\b[A-Za-z_][A-Za-z0-9_]*\b|[{}()\[\].,;:+\-*/%=<>!&|?]""",
            options = setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL),
        )

    override fun supportsLanguage(languageOrAlias: String?): Boolean {
        val normalized = languageOrAlias?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return true
        return normalized in supportedLanguageAliases
    }

    override fun highlight(
        languageOrAlias: String?,
        code: String,
    ): SyntaxHighlightResult =
        SyntaxHighlightResult(
            spans = highlightFallbackSpans(languageOrAlias, code),
            diagnostics =
                listOf(
                    MarkdownDiagnostic(
                        MarkdownDiagnosticKind.HighlighterFallback,
                        "TextMate highlighter feasibility gate is pending; semantic native fallback tokens were used.",
                    ),
                ),
        )

    private fun highlightFallbackSpans(
        language: String?,
        code: String,
    ): List<CodeSpan> {
        if (code.isEmpty()) return emptyList()
        return tokenRegex
            .findAll(code)
            .map { match ->
                val value = match.value
                CodeSpan(
                    start = match.range.first,
                    end = match.range.last + 1,
                    role = roleFor(language, value),
                )
            }.filter { it.role != CodeTokenRole.Plain }
            .toList()
    }

    private fun roleFor(
        language: String?,
        value: String,
    ): CodeTokenRole =
        when {
            value.startsWith("//") || value.startsWith("#") || value.startsWith("/*") -> CodeTokenRole.Comment
            value.startsWith("\"") || value.startsWith("'") -> CodeTokenRole.String
            value.firstOrNull()?.isDigit() == true -> CodeTokenRole.Number
            value in keywordRoles -> keywordRoles.getValue(value)
            language == "json" && value.matches(Regex("""[A-Za-z_][A-Za-z0-9_]*""")) -> CodeTokenRole.Property
            value.matches(Regex("""[{}()\[\].,;:]""")) -> CodeTokenRole.Punctuation
            value.matches(Regex("""[+\-*/%=<>!&|?]+""")) -> CodeTokenRole.Operator
            value.firstOrNull()?.isUpperCase() == true -> CodeTokenRole.Type
            else -> CodeTokenRole.Plain
        }
}
