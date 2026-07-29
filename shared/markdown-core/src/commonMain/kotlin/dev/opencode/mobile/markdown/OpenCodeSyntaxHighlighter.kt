package dev.opencode.mobile.markdown

import kotlin.time.TimeSource

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
    private val metricsSink: OpenCodeTextMateMetricsSink = NoOpOpenCodeTextMateMetricsSink,
) : OpenCodeCodeHighlighter {
    override fun supportsLanguage(languageOrAlias: String?): Boolean =
        normalizedLanguage(languageOrAlias)?.let { language ->
            grammarBundle?.manifest?.contains(language) == true || fallbackHighlighter.supportsLanguage(language)
        } ?: true

    override fun highlight(
        languageOrAlias: String?,
        code: String,
    ): SyntaxHighlightResult {
        val grammarLookupStartedAt = if (metricsSink.isEnabled) TimeSource.Monotonic.markNow() else null
        val grammar = normalizedLanguage(languageOrAlias)?.let { language -> grammarBundle?.primaryGrammarFor(language) }
        grammarLookupStartedAt?.let {
            metricsSink.record(
                TextMateMetricEvent(
                    stage = TextMateMetricStage.GrammarLookup,
                    outcome = if (grammar == null) TextMateMetricOutcome.Failure else TextMateMetricOutcome.Success,
                    durationNanoseconds = it.elapsedNow().inWholeNanoseconds,
                    sourceCharacters = code.length,
                ),
            )
        }
        val tokenizer = textMateTokenizer
        if (grammar != null && tokenizer != null) {
            val tokenization = tokenizer.tokenize(grammar, code, grammarBundle)
            val repairStartedAt = if (metricsSink.isEnabled) TimeSource.Monotonic.markNow() else null
            val spans =
                tokenization.tokens
                    .map(OpenCodeTextMateScopeMapper::codeSpanFor)
                    .filter { it.role != CodeTokenRole.Plain && it.start < it.end }
                    .partitionNonOverlappingSpans()
                    .consolidateWithinIdentifiers(code)
                    .highlightPrivateFields(code)
                    .normalizeWebSyntaxRoles(code, languageOrAlias)
                    .normalizeJavaSyntaxRoles(code, languageOrAlias)
                    .enhanceCppSyntaxRoles(code, languageOrAlias)
                    .enhancePhpSyntaxRoles(code, languageOrAlias)
                    .partitionNonOverlappingSpans()
                    .consolidateWithinIdentifiers(code)
                    .mergeAdjacentSemanticSpans(code)
                    .highlightPythonTripleQuotedStrings(code, languageOrAlias)
            repairStartedAt?.let {
                metricsSink.record(
                    TextMateMetricEvent(
                        stage = TextMateMetricStage.Repair,
                        outcome = TextMateMetricOutcome.Success,
                        durationNanoseconds = it.elapsedNow().inWholeNanoseconds,
                        sourceCharacters = code.length,
                    ),
                )
            }
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
            buildList(size) {
                for (span in this@mergeAdjacentSemanticSpans) {
                    val previous = lastOrNull()
                    if (previous != null && previous.end == span.start && previous.canMergeWith(span, code)) {
                        removeAt(lastIndex)
                        add(previous.copy(end = span.end))
                    } else {
                        add(span)
                    }
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
        private val numberRegex = Regex("""\b(?:0[xX][0-9A-Fa-f]+|\d+(?:\.\d+)?(?:[eE][+-]?\d+)?[uUlLfF]*)\b""")

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

        private val cppLanguageAliases =
            setOf(
                "c",
                "h",
                "cpp",
                "c++",
                "cc",
                "cxx",
                "hpp",
                "h++",
                "hh",
                "objc",
                "objective-c",
                "objectivec",
            )

        private val phpLanguageAliases =
            setOf(
                "php",
                "php3",
                "php4",
                "php5",
            )

        private val cppKeywordIdentifiers =
            setOf(
                "alignas",
                "alignof",
                "asm",
                "auto",
                "break",
                "case",
                "catch",
                "class",
                "concept",
                "const",
                "consteval",
                "constexpr",
                "constinit",
                "continue",
                "decltype",
                "default",
                "delete",
                "do",
                "else",
                "enum",
                "explicit",
                "export",
                "extern",
                "final",
                "for",
                "friend",
                "goto",
                "if",
                "import",
                "inline",
                "mutable",
                "namespace",
                "new",
                "noexcept",
                "operator",
                "override",
                "private",
                "protected",
                "public",
                "requires",
                "return",
                "sizeof",
                "static",
                "static_assert",
                "switch",
                "template",
                "this",
                "throw",
                "try",
                "typedef",
                "typename",
                "using",
                "virtual",
                "while",
            )

        private val cppConstantIdentifiers =
            setOf(
                "false",
                "nullptr",
                "NULL",
                "true",
            )

        private val cppTypeIdentifiers =
            setOf(
                "bool",
                "char",
                "char8_t",
                "char16_t",
                "char32_t",
                "double",
                "float",
                "int",
                "long",
                "short",
                "signed",
                "size_t",
                "std",
                "string",
                "struct",
                "unsigned",
                "vector",
                "void",
                "wchar_t",
            )

        private val phpKeywordIdentifiers =
            setOf(
                "abstract",
                "as",
                "catch",
                "class",
                "clone",
                "const",
                "declare",
                "default",
                "echo",
                "else",
                "elseif",
                "extends",
                "final",
                "finally",
                "fn",
                "for",
                "foreach",
                "function",
                "global",
                "if",
                "implements",
                "interface",
                "namespace",
                "new",
                "private",
                "protected",
                "public",
                "readonly",
                "return",
                "static",
                "switch",
                "throw",
                "trait",
                "try",
                "use",
                "while",
                "yield",
            )

        private val phpTypeIdentifiers =
            setOf(
                "array",
                "bool",
                "callable",
                "false",
                "float",
                "int",
                "iterable",
                "mixed",
                "never",
                "null",
                "object",
                "self",
                "string",
                "true",
                "void",
            )

        private val phpConstantIdentifiers =
            setOf(
                "PHP_EOL",
                "PHP_VERSION",
                "__CLASS__",
                "__DIR__",
                "__FILE__",
                "__FUNCTION__",
                "__LINE__",
                "__METHOD__",
                "__NAMESPACE__",
                "__TRAIT__",
            )

        private val webExpressionKeywordIdentifiers =
            setOf(
                "new",
                "typeof",
                "instanceof",
                "delete",
                "void",
                "catch",
                "return",
            )

        private val webConstantIdentifiers =
            setOf(
                "true",
                "false",
                "null",
                "undefined",
                "NaN",
                "Infinity",
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
                        isJavaScriptFamily && word in webConstantIdentifiers -> CodeTokenRole.Constant
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

        private fun List<CodeSpan>.normalizeJavaSyntaxRoles(
            code: String,
            languageOrAlias: String?,
        ): List<CodeSpan> {
            val language = normalizedLanguage(languageOrAlias)
            if (language != "java") return this

            val mutable = toMutableList()
            javaImportRegex.findAll(code).forEach { match ->
                val spans =
                    listOfNotNull(
                        match.groups[1]?.let { group ->
                            CodeSpan(group.range.first, group.range.last + 1, CodeTokenRole.Keyword)
                        },
                        match.groups[2]?.let { group ->
                            CodeSpan(group.range.first, group.range.last + 1, CodeTokenRole.Keyword)
                        },
                        match.groups[3]?.let { group ->
                            CodeSpan(group.range.first, group.range.last + 1, CodeTokenRole.Function)
                        },
                        match.groups[4]?.let { group ->
                            CodeSpan(group.range.first, group.range.last + 1, CodeTokenRole.Punctuation)
                        },
                    )
                val start = spans.minOfOrNull { span -> span.start } ?: return@forEach
                val end = spans.maxOfOrNull { span -> span.end } ?: return@forEach
                mutable.removeAll { span -> span.end > start && span.start < end }
                mutable += spans
            }

            return mutable.sortedBy { it.start }
        }

        private fun List<CodeSpan>.enhanceCppSyntaxRoles(
            code: String,
            languageOrAlias: String?,
        ): List<CodeSpan> {
            val language = normalizedLanguage(languageOrAlias)
            if (language !in cppLanguageAliases || code.isEmpty()) return this

            val spans = toMutableList()
            val protectedRanges = mutableListOf<IntRange>()
            var index = 0

            fun addSpan(
                start: Int,
                end: Int,
                role: CodeTokenRole,
            ) {
                if (start < end) {
                    spans += CodeSpan(start, end, role)
                }
            }

            while (index < code.length) {
                val current = code[index]
                val next = code.getOrNull(index + 1)
                when {
                    current == '/' && next == '/' -> {
                        val end = code.indexOf('\n', index).let { found -> if (found == -1) code.length else found }
                        addSpan(index, end, CodeTokenRole.Comment)
                        protectedRanges += index until end
                        index = end
                    }
                    current == '/' && next == '*' -> {
                        val end = code.indexOf("*/", index + 2).let { found -> if (found == -1) code.length else found + 2 }
                        addSpan(index, end, CodeTokenRole.Comment)
                        protectedRanges += index until end
                        index = end
                    }
                    current == '"' || current == '\'' -> {
                        val quote = current
                        var cursor = index + 1
                        var escaped = false
                        while (cursor < code.length) {
                            val char = code[cursor]
                            if (escaped) {
                                escaped = false
                            } else if (char == '\\') {
                                escaped = true
                            } else if (char == quote) {
                                cursor += 1
                                break
                            } else if (char == '\n') {
                                break
                            }
                            cursor += 1
                        }
                        addSpan(index, cursor, CodeTokenRole.String)
                        protectedRanges += index until cursor
                        index = cursor
                    }
                    else -> index += 1
                }
            }

            fun isProtected(position: Int): Boolean = protectedRanges.any { range -> position in range }

            numberRegex.findAll(code).forEach { match ->
                if (!isProtected(match.range.first)) {
                    addSpan(match.range.first, match.range.last + 1, CodeTokenRole.Number)
                }
            }

            identifierRegex.findAll(code).forEach { match ->
                val word = match.value
                val start = match.range.first
                val end = match.range.last + 1
                if (isProtected(start)) return@forEach

                val role =
                    when {
                        word in cppKeywordIdentifiers -> CodeTokenRole.Keyword
                        word in cppConstantIdentifiers -> CodeTokenRole.Constant
                        word in cppTypeIdentifiers || word.firstOrNull()?.isUpperCase() == true -> CodeTokenRole.Type
                        code.nextNonWhitespace(end) == '(' -> CodeTokenRole.Function
                        else -> null
                    }
                if (role != null) {
                    addSpan(start, end, role)
                }
            }

            return spans.sortedBy { it.start }
        }

        private fun List<CodeSpan>.enhancePhpSyntaxRoles(
            code: String,
            languageOrAlias: String?,
        ): List<CodeSpan> {
            val language = normalizedLanguage(languageOrAlias)
            if (language !in phpLanguageAliases || code.isEmpty()) return this

            val spans = toMutableList()
            val protectedRanges = mutableListOf<IntRange>()
            var index = 0

            fun addSpan(
                start: Int,
                end: Int,
                role: CodeTokenRole,
            ) {
                if (start < end) {
                    spans += CodeSpan(start, end, role)
                }
            }

            while (index < code.length) {
                val current = code[index]
                val next = code.getOrNull(index + 1)
                when {
                    code.startsWith("<?php", index) -> {
                        addSpan(index, index + 5, CodeTokenRole.Keyword)
                        index += 5
                    }
                    code.startsWith("<?=", index) -> {
                        addSpan(index, index + 3, CodeTokenRole.Keyword)
                        index += 3
                    }
                    code.startsWith("?>", index) -> {
                        addSpan(index, index + 2, CodeTokenRole.Keyword)
                        index += 2
                    }
                    current == '/' && next == '/' -> {
                        val end = code.indexOf('\n', index).let { found -> if (found == -1) code.length else found }
                        addSpan(index, end, CodeTokenRole.Comment)
                        protectedRanges += index until end
                        index = end
                    }
                    current == '#' -> {
                        val end = code.indexOf('\n', index).let { found -> if (found == -1) code.length else found }
                        addSpan(index, end, CodeTokenRole.Comment)
                        protectedRanges += index until end
                        index = end
                    }
                    current == '/' && next == '*' -> {
                        val end = code.indexOf("*/", index + 2).let { found -> if (found == -1) code.length else found + 2 }
                        addSpan(index, end, CodeTokenRole.Comment)
                        protectedRanges += index until end
                        index = end
                    }
                    current == '"' || current == '\'' -> {
                        val quote = current
                        var cursor = index + 1
                        var escaped = false
                        while (cursor < code.length) {
                            val char = code[cursor]
                            if (escaped) {
                                escaped = false
                            } else if (char == '\\') {
                                escaped = true
                            } else if (char == quote) {
                                cursor += 1
                                break
                            } else if (char == '\n' && quote == '\'') {
                                break
                            }
                            cursor += 1
                        }
                        addSpan(index, cursor, CodeTokenRole.String)
                        protectedRanges += index until cursor
                        index = cursor
                    }
                    else -> index += 1
                }
            }

            fun isProtected(position: Int): Boolean = protectedRanges.any { range -> position in range }

            Regex("""\$[A-Za-z_][A-Za-z0-9_]*""").findAll(code).forEach { match ->
                if (!isProtected(match.range.first)) {
                    addSpan(match.range.first, match.range.last + 1, CodeTokenRole.Variable)
                }
            }

            numberRegex.findAll(code).forEach { match ->
                if (!isProtected(match.range.first)) {
                    addSpan(match.range.first, match.range.last + 1, CodeTokenRole.Number)
                }
            }

            identifierRegex.findAll(code).forEach { match ->
                val word = match.value
                val start = match.range.first
                val end = match.range.last + 1
                if (isProtected(start)) return@forEach

                val role =
                    when {
                        word in phpKeywordIdentifiers -> CodeTokenRole.Keyword
                        word in phpConstantIdentifiers -> CodeTokenRole.Constant
                        word in phpTypeIdentifiers || word.firstOrNull()?.isUpperCase() == true -> CodeTokenRole.Type
                        code.nextNonWhitespace(end) == '(' -> CodeTokenRole.Function
                        else -> null
                    }
                if (role != null) {
                    addSpan(start, end, role)
                }
            }

            Regex("""(?:->|::|\?->|\?\?|=>|[+\-*/%=<>!&|.]+)""").findAll(code).forEach { match ->
                if (!isProtected(match.range.first)) {
                    addSpan(match.range.first, match.range.last + 1, CodeTokenRole.Operator)
                }
            }

            return spans.sortedBy { it.start }
        }

        private fun String.nextNonWhitespace(start: Int): Char? {
            var index = start
            while (index < length && this[index].isWhitespace()) {
                index += 1
            }
            return getOrNull(index)
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

        private fun List<CodeSpan>.highlightPythonTripleQuotedStrings(
            code: String,
            languageOrAlias: String?,
        ): List<CodeSpan> {
            val language = normalizedLanguage(languageOrAlias)
            if (language != "python" && language != "py") return this

            val spans = toMutableList()
            var cursor = 0
            while (cursor < code.length) {
                val doubleQuoteStart = code.indexOf("\"\"\"", cursor).takeIf { it >= 0 }
                val singleQuoteStart = code.indexOf("'''", cursor).takeIf { it >= 0 }
                val start =
                    listOfNotNull(doubleQuoteStart, singleQuoteStart)
                        .minOrNull()
                        ?: break
                val delimiter = code.substring(start, start + 3)
                val prefixStart = pythonStringPrefixStart(code, start)
                val prefix = code.substring(prefixStart, start)
                if ('f' in prefix.lowercase()) {
                    cursor = start + 3
                    continue
                }

                // Skip triple-quote sequences that are not real string openers:
                // inside a comment, or embedded within a longer string literal
                // that started earlier. A genuine docstring delimiter is either
                // uncovered or begins its own string span at this position.
                val coveringStart = spans.filter { span -> span.start <= start && span.end > start }
                val embeddedInCommentOrString =
                    coveringStart.any { span ->
                        span.role == CodeTokenRole.Comment ||
                            (span.role == CodeTokenRole.String && span.start < prefixStart)
                    }
                if (embeddedInCommentOrString) {
                    cursor = start + 3
                    continue
                }

                val end = code.indexOf(delimiter, start + 3).takeIf { it >= 0 } ?: break
                spans.removeAll { span -> span.end > prefixStart && span.start < end + 3 }
                spans += CodeSpan(prefixStart, end + 3, CodeTokenRole.String)
                cursor = end + 3
            }

            return spans.sortedBy { it.start }
        }

        private fun pythonStringPrefixStart(
            code: String,
            quoteStart: Int,
        ): Int {
            var index = quoteStart
            while (index > 0 && code[index - 1] in pythonStringPrefixCharacters) {
                index -= 1
            }
            return index
        }

        private val javaImportRegex =
            Regex("""(?m)^\s*(import)\s+(?:(static)\s+)?([A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*|\.\*)*)\s*(;)""")

        private val pythonStringPrefixCharacters = setOf('r', 'R', 'u', 'U', 'b', 'B', 'f', 'F')

        private fun CodeTokenRole.coalescePriority(): Int =
            when (this) {
                CodeTokenRole.Punctuation -> 0
                CodeTokenRole.Operator -> 1
                CodeTokenRole.Plain -> 2
                CodeTokenRole.Variable -> 3
                CodeTokenRole.Property -> 4
                CodeTokenRole.Type -> 5
                CodeTokenRole.Number -> 6
                CodeTokenRole.Constant -> 7
                CodeTokenRole.String -> 8
                CodeTokenRole.Comment -> 9
                CodeTokenRole.Keyword -> 10
                CodeTokenRole.Function -> 11
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
            "scratch",
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

    private val punctuationCharacters = setOf('{', '}', '(', ')', '[', ']', '.', ',', ';', ':')
    private val operatorCharacters = setOf('+', '-', '*', '/', '%', '=', '<', '>', '!', '&', '|', '?')

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
        if (language == "scratch") return emptyList()
        if (code.isEmpty()) return emptyList()
        return buildList {
            var cursor = 0
            while (cursor < code.length) {
                val start = cursor
                val end =
                    when {
                        code[cursor] == '"' || code[cursor] == '\'' ->
                            quotedTokenEnd(code, cursor, code[cursor])
                        code.startsWith("//", cursor) || code[cursor] == '#' ->
                            lineTokenEnd(code, cursor)
                        code.startsWith("/*", cursor) ->
                            blockCommentEnd(code, cursor)
                        code[cursor].isDigit() ->
                            numberTokenEnd(code, cursor)
                        code[cursor].isAsciiIdentifierStart() ->
                            identifierTokenEnd(code, cursor)
                        code[cursor] in punctuationCharacters ->
                            cursor + 1
                        code[cursor] in operatorCharacters ->
                            operatorTokenEnd(code, cursor)
                        else -> {
                            cursor += 1
                            continue
                        }
                    }
                val role = roleFor(language, code.substring(start, end))
                if (role != CodeTokenRole.Plain) {
                    add(CodeSpan(start = start, end = end, role = role))
                }
                cursor = end
            }
        }
    }

    private fun quotedTokenEnd(
        code: String,
        start: Int,
        quote: Char,
    ): Int {
        var cursor = start + 1
        while (cursor < code.length) {
            when (code[cursor]) {
                '\\' -> cursor = (cursor + 2).coerceAtMost(code.length)
                quote -> return cursor + 1
                else -> cursor += 1
            }
        }
        return code.length
    }

    private fun lineTokenEnd(
        code: String,
        start: Int,
    ): Int {
        val lineEnd = code.indexOf('\n', startIndex = start)
        return if (lineEnd < 0) code.length else lineEnd
    }

    private fun blockCommentEnd(
        code: String,
        start: Int,
    ): Int {
        val closing = code.indexOf("*/", startIndex = start + 2)
        return if (closing < 0) code.length else closing + 2
    }

    private fun numberTokenEnd(
        code: String,
        start: Int,
    ): Int {
        var cursor = start
        while (cursor < code.length && code[cursor].isDigit()) cursor += 1
        if (cursor + 1 < code.length && code[cursor] == '.' && code[cursor + 1].isDigit()) {
            cursor += 1
            while (cursor < code.length && code[cursor].isDigit()) cursor += 1
        }
        return cursor
    }

    private fun identifierTokenEnd(
        code: String,
        start: Int,
    ): Int {
        var cursor = start + 1
        while (cursor < code.length && code[cursor].isAsciiIdentifierPart()) cursor += 1
        return cursor
    }

    private fun operatorTokenEnd(
        code: String,
        start: Int,
    ): Int {
        var cursor = start + 1
        while (cursor < code.length && code[cursor] in operatorCharacters) cursor += 1
        return cursor
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
            language == "json" && value.firstOrNull()?.isAsciiIdentifierStart() == true -> CodeTokenRole.Property
            value.length == 1 && value[0] in punctuationCharacters -> CodeTokenRole.Punctuation
            value.isNotEmpty() && value.all { character -> character in operatorCharacters } -> CodeTokenRole.Operator
            value.firstOrNull()?.isUpperCase() == true -> CodeTokenRole.Type
            else -> CodeTokenRole.Plain
        }

    private fun Char.isAsciiIdentifierStart(): Boolean = this == '_' || this in 'A'..'Z' || this in 'a'..'z'

    private fun Char.isAsciiIdentifierPart(): Boolean = isAsciiIdentifierStart() || isDigit()
}
