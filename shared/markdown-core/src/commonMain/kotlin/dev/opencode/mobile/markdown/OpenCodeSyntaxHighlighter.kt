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
    private val fallbackHighlighter: OpenCodeCodeHighlighter = OpenCodeSemanticFallbackHighlighter,
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
                    .partitionNonOverlappingSpans()
                    .consolidateWithinIdentifiers(code)
                    .highlightPrivateFields(code)
                    .normalizeWebSyntaxRoles(code, languageOrAlias)
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

        private fun List<CodeSpan>.partitionNonOverlappingSpans(): List<CodeSpan> {
            if (isEmpty()) return emptyList()

            val boundaries =
                flatMap { span -> listOf(span.start, span.end) }
                    .distinct()
                    .sorted()
            if (boundaries.size < 2) return this

            val result = mutableListOf<CodeSpan>()
            for (index in 0 until boundaries.lastIndex) {
                val segmentStart = boundaries[index]
                val segmentEnd = boundaries[index + 1]
                if (segmentStart >= segmentEnd) continue

                val covering = filter { span -> span.start <= segmentStart && span.end >= segmentEnd }
                if (covering.isEmpty()) continue

                val winner =
                    covering.maxWith(
                        compareBy<CodeSpan> { span ->
                            val overlapStart = maxOf(span.start, segmentStart)
                            val overlapEnd = minOf(span.end, segmentEnd)
                            overlapEnd - overlapStart
                        }.thenByDescending { span -> span.end - span.start }
                            .thenBy { span -> span.role.coalescePriority() },
                    )
                if (winner.role == CodeTokenRole.Plain) continue

                result += winner.copy(start = segmentStart, end = segmentEnd)
            }

            return result
        }

        private val identifierRegex = Regex("""#?[A-Za-z_][A-Za-z0-9_]*""")

        private fun List<CodeSpan>.consolidateWithinIdentifiers(code: String): List<CodeSpan> {
            if (isEmpty()) return emptyList()

            val mutable = toMutableList()

            identifierRegex.findAll(code).forEach { match ->
                val wordStart = match.range.first
                val wordEnd = match.range.last + 1
                val intersecting =
                    mutable.filter { span -> span.end > wordStart && span.start < wordEnd }
                if (intersecting.isEmpty()) return@forEach

                val hasMultipleRoles = intersecting.map { it.role }.toSet().size > 1
                val hasCoverageGaps =
                    (wordStart until wordEnd).any { index ->
                        intersecting.none { span -> span.start <= index && span.end > index }
                    }
                if (!hasMultipleRoles && !hasCoverageGaps) return@forEach

                val dominantRole =
                    intersecting
                        .maxWith(
                            compareBy<CodeSpan> { span ->
                                val overlapStart = maxOf(span.start, wordStart)
                                val overlapEnd = minOf(span.end, wordEnd)
                                overlapEnd - overlapStart
                            }.thenByDescending { span -> span.end - span.start }
                                .thenBy { span -> span.role.coalescePriority() },
                        ).role

                mutable.removeAll(intersecting.toSet())
                mutable += CodeSpan(wordStart, wordEnd, dominantRole)
            }

            return mutable.sortedBy { it.start }
        }

        private val javaScriptLanguageAliases =
            setOf(
                "javascript",
                "js",
                "jsx",
                "typescript",
                "ts",
                "tsx",
                "mjs",
                "cjs",
                "mts",
                "cts",
            )

        private val webExpressionKeywordIdentifiers =
            setOf(
                "new",
                "typeof",
                "instanceof",
                "delete",
                "void",
            )

        private fun List<CodeSpan>.normalizeWebSyntaxRoles(
            code: String,
            languageOrAlias: String?,
        ): List<CodeSpan> {
            val language = normalizedLanguage(languageOrAlias)
            val isJavaScriptFamily = language != null && language in javaScriptLanguageAliases
            val mutable = toMutableList()

            identifierRegex.findAll(code).forEach { match ->
                val word = match.value
                val wordStart = match.range.first
                val wordEnd = match.range.last + 1
                val intersecting =
                    mutable.filter { span -> span.end > wordStart && span.start < wordEnd }
                if (intersecting.any { it.role == CodeTokenRole.String || it.role == CodeTokenRole.Comment }) {
                    return@forEach
                }

                val targetRole =
                    when {
                        word == "constructor" -> CodeTokenRole.Keyword
                        isJavaScriptFamily && word == "this" -> CodeTokenRole.Property
                        isJavaScriptFamily && word in webExpressionKeywordIdentifiers -> CodeTokenRole.Keyword
                        else -> return@forEach
                    }

                mutable.removeAll(intersecting.toSet())
                mutable += CodeSpan(wordStart, wordEnd, targetRole)
            }

            if (isJavaScriptFamily) {
                Regex("""\bconstructor\s*\(\s*([a-z][A-Za-z0-9_]*)""").findAll(code).forEach { match ->
                    val wordStart = match.groups[1]!!.range.first
                    val wordEnd = match.groups[1]!!.range.last + 1
                    val intersecting =
                        mutable.filter { span -> span.end > wordStart && span.start < wordEnd }
                    if (intersecting.any { it.role == CodeTokenRole.String || it.role == CodeTokenRole.Comment }) {
                        return@forEach
                    }
                    mutable.removeAll(intersecting.toSet())
                    mutable += CodeSpan(wordStart, wordEnd, CodeTokenRole.Variable)
                }
            }

            return mutable.sortedBy { it.start }
        }

        private fun List<CodeSpan>.highlightPrivateFields(code: String): List<CodeSpan> {
            val mutable = toMutableList()

            Regex("""this\.(#[A-Za-z_][A-Za-z0-9_]*)""").findAll(code).forEach { match ->
                val fieldRange = match.groups[1]!!.range
                val fieldStart = fieldRange.first
                val fieldEnd = fieldRange.last + 1
                val intersecting =
                    mutable.filter { span -> span.end > fieldStart && span.start < fieldEnd }
                if (intersecting.any { it.role == CodeTokenRole.String || it.role == CodeTokenRole.Comment }) {
                    return@forEach
                }
                mutable.removeAll(intersecting.toSet())
                mutable += CodeSpan(fieldStart, fieldEnd, CodeTokenRole.Property)
            }

            Regex("""(?m)^\s+(#[A-Za-z_][A-Za-z0-9_]*);""").findAll(code).forEach { match ->
                val fieldRange = match.groups[1]!!.range
                val fieldStart = fieldRange.first
                val fieldEnd = fieldRange.last + 1
                mutable.removeAll { span -> span.end > fieldStart && span.start < fieldEnd }
            }

            return mutable.sortedBy { it.start }
        }

        private fun CodeTokenRole.coalescePriority(): Int =
            when (this) {
                CodeTokenRole.Punctuation -> 0
                CodeTokenRole.Operator -> 1
                CodeTokenRole.Plain -> 2
                CodeTokenRole.Variable -> 3
                CodeTokenRole.Property -> 4
                CodeTokenRole.Function -> 5
                CodeTokenRole.Number -> 6
                CodeTokenRole.Constant -> 7
                CodeTokenRole.String -> 8
                CodeTokenRole.Comment -> 9
                CodeTokenRole.Keyword -> 10
                CodeTokenRole.Type -> 11
                CodeTokenRole.Tag -> 12
                CodeTokenRole.Attribute -> 13
                CodeTokenRole.Inserted -> 14
                CodeTokenRole.Deleted -> 15
                CodeTokenRole.Warning -> 16
                CodeTokenRole.Critical -> 17
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

object OpenCodeSemanticFallbackHighlighter : OpenCodeCodeHighlighter {
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
