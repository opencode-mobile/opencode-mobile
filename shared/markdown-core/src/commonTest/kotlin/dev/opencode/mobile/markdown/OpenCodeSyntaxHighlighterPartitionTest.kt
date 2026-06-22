package dev.opencode.mobile.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenCodeSyntaxHighlighterPartitionTest {
    @Test
    fun partitionNonOverlappingSpansMergesHttpClientStylePartialOverlaps() {
        val highlighter =
            OpenCodeSyntaxHighlighter(
                grammarBundle = bundledGrammarBundle(),
                textMateTokenizer =
                    object : OpenCodeTextMateTokenizer {
                        override fun tokenize(
                            grammar: TextMateGrammarPayload,
                            code: String,
                            grammarBundle: TextMateGrammarBundle?,
                        ): TextMateTokenizationResult =
                            TextMateTokenizationResult(
                                tokens =
                                    listOf(
                                        TextMateScopeToken(0, 10, listOf("source.js", "entity.name.type.class.js")),
                                        TextMateScopeToken(3, 4, listOf("source.js", "variable.other.property.js")),
                                        TextMateScopeToken(5, 6, listOf("source.js", "variable.other.property.js")),
                                    ),
                            )
                    },
            )

        val result = highlighter.highlight("javascript", "HttpClient")

        assertEquals(listOf(CodeSpan(0, 10, CodeTokenRole.Type)), result.spans)
    }

    @Test
    fun partitionNonOverlappingSpansRemovesDuplicateConstKeywordRanges() {
        val highlighter =
            OpenCodeSyntaxHighlighter(
                grammarBundle = bundledGrammarBundle(),
                textMateTokenizer =
                    object : OpenCodeTextMateTokenizer {
                        override fun tokenize(
                            grammar: TextMateGrammarPayload,
                            code: String,
                            grammarBundle: TextMateGrammarBundle?,
                        ): TextMateTokenizationResult =
                            TextMateTokenizationResult(
                                tokens =
                                    listOf(
                                        TextMateScopeToken(0, 5, listOf("source.js", "storage.type.js")),
                                        TextMateScopeToken(0, 5, listOf("source.js", "keyword.control.js")),
                                    ),
                            )
                    },
            )

        val result = highlighter.highlight("javascript", "const x = 1")

        assertEquals(1, result.spans.count { it.role == CodeTokenRole.Keyword && it.start == 0 && it.end == 5 })
    }

    @Test
    fun consolidateWithinIdentifiersUnifiesFragmentedPatchAndPathTokens() {
        val highlighter =
            OpenCodeSyntaxHighlighter(
                grammarBundle = bundledGrammarBundle(),
                textMateTokenizer =
                    object : OpenCodeTextMateTokenizer {
                        override fun tokenize(
                            grammar: TextMateGrammarPayload,
                            code: String,
                            grammarBundle: TextMateGrammarBundle?,
                        ): TextMateTokenizationResult =
                            TextMateTokenizationResult(
                                tokens =
                                    listOf(
                                        TextMateScopeToken(0, 5, listOf("source.js", "entity.name.function.js")),
                                        TextMateScopeToken(4, 5, listOf("source.js", "entity.name.type.js")),
                                        TextMateScopeToken(5, 6, listOf("source.js", "punctuation.definition.parameters.js")),
                                        TextMateScopeToken(6, 10, listOf("source.js", "variable.parameter.js")),
                                        TextMateScopeToken(8, 10, listOf("source.js", "entity.name.type.js")),
                                    ),
                            )
                    },
            )

        val result = highlighter.highlight("javascript", "patch(path")

        assertEquals(CodeSpan(0, 5, CodeTokenRole.Function), result.spans[0])
        assertEquals(CodeSpan(5, 6, CodeTokenRole.Punctuation), result.spans[1])
        val pathSpan = result.spans.single { it.start == 6 && it.end == 10 }
        assertTrue(
            pathSpan.role == CodeTokenRole.Variable || pathSpan.role == CodeTokenRole.Type,
            "Expected path token to use one semantic role, got ${pathSpan.role}",
        )
    }

    @Test
    fun consolidateWithinIdentifiersFillsSameRoleCoverageGaps() {
        val highlighter =
            OpenCodeSyntaxHighlighter(
                grammarBundle = bundledGrammarBundle(),
                textMateTokenizer =
                    object : OpenCodeTextMateTokenizer {
                        override fun tokenize(
                            grammar: TextMateGrammarPayload,
                            code: String,
                            grammarBundle: TextMateGrammarBundle?,
                        ): TextMateTokenizationResult =
                            TextMateTokenizationResult(
                                tokens =
                                    listOf(
                                        TextMateScopeToken(3, 4, listOf("source.js", "entity.name.function.js")),
                                        TextMateScopeToken(5, 6, listOf("source.js", "entity.name.function.js")),
                                        TextMateScopeToken(7, 8, listOf("source.js", "entity.name.function.js")),
                                    ),
                            )
                    },
            )

        val result = highlighter.highlight("javascript", "withCache")

        assertEquals(listOf(CodeSpan(0, 9, CodeTokenRole.Function)), result.spans)
    }

    @Test
    fun highlightPrivateFieldsUnifiesHashPrefixedIdentifiers() {
        val highlighter =
            OpenCodeSyntaxHighlighter(
                grammarBundle = bundledGrammarBundle(),
                textMateTokenizer =
                    object : OpenCodeTextMateTokenizer {
                        override fun tokenize(
                            grammar: TextMateGrammarPayload,
                            code: String,
                            grammarBundle: TextMateGrammarBundle?,
                        ): TextMateTokenizationResult =
                            TextMateTokenizationResult(
                                tokens =
                                    listOf(
                                        TextMateScopeToken(0, 11, scopes = listOf("source.js", "meta.class.js")),
                                        TextMateScopeToken(21, 29, scopes = listOf("source.js", "entity.name.type.class.js")),
                                    ),
                            )
                    },
            )

        val code = "  #timeout;\n    this.#baseUrl"
        val result = highlighter.highlight("javascript", code)

        assertEquals(CodeSpan(2, 10, CodeTokenRole.Variable), result.spans[0])
        assertEquals(CodeSpan(21, 29, CodeTokenRole.Variable), result.spans[1])
    }

    @Test
    fun highlightPrivateFieldsKeepsStringsAndCommentsIntact() {
        val highlighter =
            OpenCodeSyntaxHighlighter(
                grammarBundle = bundledGrammarBundle(),
                textMateTokenizer =
                    object : OpenCodeTextMateTokenizer {
                        override fun tokenize(
                            grammar: TextMateGrammarPayload,
                            code: String,
                            grammarBundle: TextMateGrammarBundle?,
                        ): TextMateTokenizationResult =
                            TextMateTokenizationResult(
                                tokens =
                                    listOf(
                                        TextMateScopeToken(0, 10, listOf("source.js", "string.quoted.js")),
                                        TextMateScopeToken(11, 22, listOf("source.js", "comment.line.js")),
                                    ),
                            )
                    },
            )

        val result = highlighter.highlight("javascript", "\"#timeout\" // #timeout")

        assertEquals(CodeSpan(0, 10, CodeTokenRole.String), result.spans[0])
        assertEquals(CodeSpan(11, 22, CodeTokenRole.Comment), result.spans[1])
    }

    private fun bundledGrammarBundle(): TextMateGrammarBundle = testJavascriptGrammarBundle()
}
