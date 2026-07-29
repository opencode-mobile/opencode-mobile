package dev.opencode.mobile.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextMatePhase5DifferentialOracleTest {
    @Test
    fun differentialScriptsMatchFullReparse() {
        val cases =
            listOf(
                DiffCase("D1-append-lines", "alpha\nbeta", "alpha\nbeta\ngamma"),
                DiffCase("D2-append-chars", "alpha\nbet", "alpha\nbeta"),
                DiffCase("D3-insert-middle", "alpha\ngamma", "alpha\nbeta\ngamma"),
                DiffCase("D4-delete-middle", "alpha\nbeta\ngamma", "alpha\ngamma"),
                DiffCase("D5-replace-middle", "alpha\nbeta\ngamma", "alpha\ndelta\ngamma"),
                DiffCase("D6-edit-first", "alpha\nbeta", "ALPHA\nbeta"),
                DiffCase("D12-next-line-change", "alpha\nbeta\ngamma", "alpha\nbeta\ngamma!"),
                DiffCase("D13-exact-same", "alpha\nbeta", "alpha\nbeta"),
                DiffCase("D14-empty-line", "alpha\n\nbeta", "alpha\n\nbeta\ngamma"),
            )
        cases.forEach { case ->
            assertIncrementalEqualsFullReparse(case)
        }
    }

    @Test
    fun multilineOpenRuleScriptsMatchFullReparse() {
        val grammar =
            TextMateGrammar(
                name = "stringy",
                scopeName = "source.stringy",
                patterns =
                    listOf(
                        TextMatePattern(
                            name = "string.quoted",
                            begin = "\"",
                            end = "\"",
                        ),
                        TextMatePattern(name = "keyword.other", match = "\\bkeep\\b"),
                    ),
            )
        val cases =
            listOf(
                DiffCase("D7-edit-after-opener", "\"hello\nkeep", "\"hello!\nkeep"),
                DiffCase("D8-edit-inside", "\"hel\nlo\"", "\"hello\nlo\""),
                DiffCase("D10-empty-while-open", "\"a\n\nb\"", "\"a\n\nb\"\nkeep"),
            )
        cases.forEach { case ->
            assertIncrementalEqualsFullReparse(case, grammar)
        }
    }

    private data class DiffCase(
        val id: String,
        val before: String,
        val after: String,
    )

    private fun assertIncrementalEqualsFullReparse(
        case: DiffCase,
        grammar: TextMateGrammar = defaultGrammar(),
    ) {
        val payload = payloadFor(grammar)
        val bundle = bundleFor(payload)
        val tokenizer = OpenCodeTextMateNativeTokenizer(KotlinRegexTextMateRegexBackend)
        val highlighter =
            OpenCodeSyntaxHighlighter(
                grammarBundle = bundle,
                textMateTokenizer = tokenizer,
            )
        val key = CodeBlockKey("oracle-${case.id}")
        val identity =
            IncrementalBlockIdentity(
                key = key,
                canonicalLanguage = grammar.name,
                grammarResourcePath = payload.descriptor.resourcePath,
                grammarId = GrammarId(0),
                policyEpoch = 1,
            )
        val first =
            TextMateIncrementalHighlightSession(
                identity = identity,
                intent = HighlightIntent.InteractiveFinal,
                revision = HighlightRevision(1),
                previous = null,
                suffixConvergenceEnabled = true,
            )
        highlighter.highlight(
            languageOrAlias = grammar.name,
            code = case.before,
            metricsSink = NoOpOpenCodeTextMateMetricsSink,
            shouldCancel = { false },
            incremental = first,
        )
        assertTrue(first.produced != null, "${case.id}: expected checkpoints after first highlight")

        val second =
            TextMateIncrementalHighlightSession(
                identity = identity,
                intent = HighlightIntent.InteractiveFinal,
                revision = HighlightRevision(2),
                previous = first.produced,
                suffixConvergenceEnabled = true,
            )
        val incremental =
            highlighter.highlight(
                languageOrAlias = grammar.name,
                code = case.after,
                metricsSink = NoOpOpenCodeTextMateMetricsSink,
                shouldCancel = { false },
                incremental = second,
            )
        val full =
            highlighter.highlight(
                languageOrAlias = grammar.name,
                code = case.after,
                metricsSink = NoOpOpenCodeTextMateMetricsSink,
                shouldCancel = { false },
            )
        assertEquals(
            full.spans.map { SpanView(it.start, it.end, it.role) },
            incremental.spans.map { SpanView(it.start, it.end, it.role) },
            "${case.id}: incremental spans must equal full reparse",
        )
    }

    private data class SpanView(
        val start: Int,
        val end: Int,
        val role: CodeTokenRole,
    )

    private fun defaultGrammar(): TextMateGrammar =
        TextMateGrammar(
            name = "reuse",
            scopeName = "source.reuse",
            patterns =
                listOf(
                    TextMatePattern(name = "keyword.alpha", match = "\\balpha\\b"),
                    TextMatePattern(name = "keyword.beta", match = "\\bbeta\\b"),
                    TextMatePattern(name = "keyword.gamma", match = "\\bgamma\\b"),
                    TextMatePattern(name = "keyword.delta", match = "\\bdelta\\b"),
                    TextMatePattern(name = "keyword.ALPHA", match = "\\bALPHA\\b"),
                ),
        )

    private fun payloadFor(grammar: TextMateGrammar): TextMateGrammarPayload {
        val descriptor =
            TextMateGrammarDescriptor(
                name = grammar.name,
                displayName = grammar.name,
                scopeName = grammar.scopeName,
                resourcePath = "${grammar.name}.json",
                embeddedLanguages = emptyList(),
                aliases = emptyList(),
            )
        return TextMateGrammarPayload(descriptor, grammar, GrammarId(0))
    }

    private fun bundleFor(payload: TextMateGrammarPayload): TextMateGrammarBundle {
        val entry =
            TextMateGrammarManifestEntry(
                language = payload.descriptor.name,
                displayName = payload.descriptor.displayName,
                aliases = emptyList(),
                grammarModule = "test",
                grammars = listOf(payload.descriptor),
            )
        return TextMateGrammarBundle.fromPreloaded(
            TextMateGrammarManifest(
                schemaVersion = 1,
                generator = "test",
                shikiVersion = "test",
                languages = listOf(entry),
            ),
            listOf(payload),
        )
    }
}
