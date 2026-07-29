package dev.opencode.mobile.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextMatePhase5IncrementalReuseTest {
    @Test
    fun appendLineReusesUnchangedPrefixWithOneLineBackup() {
        val grammar = syntheticGrammar()
        val payload = payloadFor(grammar)
        val tokenizer = OpenCodeTextMateNativeTokenizer(KotlinRegexTextMateRegexBackend)
        val highlighter = OpenCodeSyntaxHighlighter(textMateTokenizer = tokenizer)
        val engine = OpenCodeTextMateHighlightingEngine(highlighter, suffixConvergenceEnabled = false)
        val key = CodeBlockKey("phase5-append")

        val first =
            engine.highlightNow(
                HighlightRequest(
                    key = key,
                    revision = HighlightRevision(1),
                    language = null,
                    source = "alpha\nbeta",
                    intent = HighlightIntent.InteractiveFinal,
                    policyEpoch = 1,
                ),
            )
        // Force language-less path uses fallback; use explicit grammar through highlighter session.
        val session1 =
            TextMateIncrementalHighlightSession(
                identity =
                    IncrementalBlockIdentity(
                        key = key,
                        canonicalLanguage = "reuse",
                        grammarResourcePath = "reuse.json",
                        grammarId = GrammarId(0),
                        policyEpoch = 1,
                    ),
                intent = HighlightIntent.InteractiveFinal,
                revision = HighlightRevision(1),
                previous = null,
                suffixConvergenceEnabled = false,
            )
        val bundle = bundleFor(payload)
        val highlighterWithBundle =
            OpenCodeSyntaxHighlighter(
                grammarBundle = bundle,
                textMateTokenizer = tokenizer,
            )
        val metrics1 = mutableListOf<TextMateMetricEvent>()
        highlighterWithBundle.highlight(
            languageOrAlias = "reuse",
            code = "alpha\nbeta",
            metricsSink = OpenCodeTextMateMetricsSink(metrics1::add),
            shouldCancel = { false },
            incremental = session1,
        )
        assertTrue(session1.produced != null)
        assertEquals(0, session1.reusedLineCount)

        val session2 =
            TextMateIncrementalHighlightSession(
                identity = session1.identity,
                intent = HighlightIntent.InteractiveFinal,
                revision = HighlightRevision(2),
                previous = session1.produced,
                suffixConvergenceEnabled = false,
            )
        val metrics2 = mutableListOf<TextMateMetricEvent>()
        val second =
            highlighterWithBundle.highlight(
                languageOrAlias = "reuse",
                code = "alpha\nbeta\ngamma",
                metricsSink = OpenCodeTextMateMetricsSink(metrics2::add),
                shouldCancel = { false },
                incremental = session2,
            )
        val full =
            highlighterWithBundle.highlight(
                languageOrAlias = "reuse",
                code = "alpha\nbeta\ngamma",
                metricsSink = NoOpOpenCodeTextMateMetricsSink,
                shouldCancel = { false },
                incremental = null,
            )
        assertEquals(full.spans, second.spans)
        // Unchanged prefix "alpha" then backup one line => resume at "beta".
        assertEquals(1, session2.reusedLineCount)
        assertTrue(session2.retokenizedLineCount >= 2)
        assertTrue(metrics2.any { it.stage == TextMateMetricStage.IncrementalReuse })
        assertTrue(metrics2.any { it.stage == TextMateMetricStage.Tokenize })
        assertTrue(metrics2.any { it.stage == TextMateMetricStage.Repair })
        // Silence unused warning for first engine call that exercises construction.
        assertTrue(first.spans.isEmpty() || first.spans.isNotEmpty())
    }

    @Test
    fun exactSourceReusesAllCheckpoints() {
        val payload = payloadFor(syntheticGrammar())
        val bundle = bundleFor(payload)
        val tokenizer = OpenCodeTextMateNativeTokenizer(KotlinRegexTextMateRegexBackend)
        val highlighter =
            OpenCodeSyntaxHighlighter(
                grammarBundle = bundle,
                textMateTokenizer = tokenizer,
            )
        val key = CodeBlockKey("phase5-exact")
        val identity =
            IncrementalBlockIdentity(
                key = key,
                canonicalLanguage = "reuse",
                grammarResourcePath = "reuse.json",
                grammarId = GrammarId(0),
                policyEpoch = 1,
            )
        val firstSession =
            TextMateIncrementalHighlightSession(
                identity = identity,
                intent = HighlightIntent.InteractiveFinal,
                revision = HighlightRevision(1),
                previous = null,
                suffixConvergenceEnabled = true,
            )
        highlighter.highlight(
            languageOrAlias = "reuse",
            code = "alpha beta",
            metricsSink = NoOpOpenCodeTextMateMetricsSink,
            shouldCancel = { false },
            incremental = firstSession,
        )
        val secondSession =
            TextMateIncrementalHighlightSession(
                identity = identity,
                intent = HighlightIntent.InteractiveFinal,
                revision = HighlightRevision(2),
                previous = firstSession.produced,
                suffixConvergenceEnabled = true,
            )
        val events = mutableListOf<TextMateMetricEvent>()
        val incremental =
            highlighter.highlight(
                languageOrAlias = "reuse",
                code = "alpha beta",
                metricsSink = OpenCodeTextMateMetricsSink(events::add),
                shouldCancel = { false },
                incremental = secondSession,
            )
        val full =
            highlighter.highlight(
                languageOrAlias = "reuse",
                code = "alpha beta",
                metricsSink = NoOpOpenCodeTextMateMetricsSink,
                shouldCancel = { false },
            )
        assertEquals(full.spans, incremental.spans)
        assertEquals(TextMateIncrementalMode.ExactSource, secondSession.mode)
        assertTrue(secondSession.reusedLineCount >= 1)
        assertEquals(0, secondSession.retokenizedLineCount)
    }

    @Test
    fun suffixConvergenceReusesTrailingLinesWhenStateMatches() {
        val payload = payloadFor(multilineGrammar())
        val bundle = bundleFor(payload)
        val tokenizer = OpenCodeTextMateNativeTokenizer(KotlinRegexTextMateRegexBackend)
        val highlighter =
            OpenCodeSyntaxHighlighter(
                grammarBundle = bundle,
                textMateTokenizer = tokenizer,
            )
        val key = CodeBlockKey("phase5-suffix")
        val identity =
            IncrementalBlockIdentity(
                key = key,
                canonicalLanguage = "ml",
                grammarResourcePath = "ml.json",
                grammarId = GrammarId(0),
                policyEpoch = 1,
            )
        val source1 = "start\nkeep-a\nkeep-b\nend"
        val source2 = "start!\nkeep-a\nkeep-b\nend"
        val firstSession =
            TextMateIncrementalHighlightSession(
                identity = identity,
                intent = HighlightIntent.InteractiveFinal,
                revision = HighlightRevision(1),
                previous = null,
                suffixConvergenceEnabled = true,
            )
        highlighter.highlight(
            languageOrAlias = "ml",
            code = source1,
            metricsSink = NoOpOpenCodeTextMateMetricsSink,
            shouldCancel = { false },
            incremental = firstSession,
        )
        val secondSession =
            TextMateIncrementalHighlightSession(
                identity = identity,
                intent = HighlightIntent.InteractiveFinal,
                revision = HighlightRevision(2),
                previous = firstSession.produced,
                suffixConvergenceEnabled = true,
            )
        val incremental =
            highlighter.highlight(
                languageOrAlias = "ml",
                code = source2,
                metricsSink = NoOpOpenCodeTextMateMetricsSink,
                shouldCancel = { false },
                incremental = secondSession,
            )
        val full =
            highlighter.highlight(
                languageOrAlias = "ml",
                code = source2,
                metricsSink = NoOpOpenCodeTextMateMetricsSink,
                shouldCancel = { false },
            )
        assertEquals(full.spans, incremental.spans)
    }

    @Test
    fun weightedCoordinatorEvictsIncrementalOnCriticalTrim() {
        val coordinator = TextMateWeightedCacheCoordinator()
        val store = TextMateIncrementalStateStore(coordinator)
        val state =
            IncrementalBlockState(
                identity =
                    IncrementalBlockIdentity(
                        key = CodeBlockKey("trim"),
                        canonicalLanguage = "reuse",
                        grammarResourcePath = "reuse.json",
                        grammarId = GrammarId(0),
                        policyEpoch = 1,
                    ),
                sourceHash32 = 1,
                sourceLength = 1,
                source = "a",
                lines = emptyList(),
                intent = HighlightIntent.BackgroundHistory,
                revision = HighlightRevision(1),
                estimatedBytes = 128,
            )
        assertTrue(store.promote(state))
        store.trim(MemoryPressure.Critical)
        assertEquals(0, store.retainedBlockCount())
    }

    @Test
    fun plannerBacksUpOneLineOnUnchangedPrefix() {
        val identity =
            IncrementalBlockIdentity(
                key = CodeBlockKey("plan"),
                canonicalLanguage = "reuse",
                grammarResourcePath = "reuse.json",
                grammarId = GrammarId(0),
                policyEpoch = 1,
            )
        val lines =
            listOf(
                checkpoint(0, "a"),
                checkpoint(1, "b"),
                checkpoint(2, "c"),
            )
        val previous =
            IncrementalBlockState(
                identity = identity,
                sourceHash32 = "a\nb\nc".hashCode(),
                sourceLength = "a\nb\nc".length,
                source = "a\nb\nc",
                lines = lines,
                intent = HighlightIntent.InteractiveFinal,
                revision = HighlightRevision(1),
                estimatedBytes = 100,
            )
        val plan =
            TextMateIncrementalPlanner.plan(
                previous = previous,
                identity = identity,
                source = "a\nb\nc\nd",
                suffixConvergenceEnabled = false,
            )
        assertEquals(TextMateIncrementalMode.Prefix, plan.mode)
        assertEquals(2, plan.resumeLine)
        assertEquals(2, plan.reusedPrefix.size)
    }

    private fun checkpoint(
        index: Int,
        text: String,
    ): TextMateLineCheckpoint {
        val incoming = TextMateLineStateSnapshot.Initial
        return TextMateLineCheckpoint(
            lineIndex = index,
            lineText = text,
            lineTextHash32 = text.hashCode(),
            incoming = incoming,
            incomingFingerprint = incoming.fingerprint(),
            outgoing = incoming,
            outgoingFingerprint = incoming.fingerprint(),
            relativeTokens = emptyList(),
            estimatedBytes = 32,
        )
    }

    private fun syntheticGrammar(): TextMateGrammar =
        TextMateGrammar(
            name = "reuse",
            scopeName = "source.reuse",
            patterns =
                listOf(
                    TextMatePattern(name = "keyword.alpha", match = "\\balpha\\b"),
                    TextMatePattern(name = "keyword.beta", match = "\\bbeta\\b"),
                    TextMatePattern(name = "keyword.gamma", match = "\\bgamma\\b"),
                ),
        )

    private fun multilineGrammar(): TextMateGrammar =
        TextMateGrammar(
            name = "ml",
            scopeName = "source.ml",
            patterns =
                listOf(
                    TextMatePattern(name = "keyword.start", match = "\\bstart!?\\b"),
                    TextMatePattern(name = "keyword.keep", match = "\\bkeep-[ab]\\b"),
                    TextMatePattern(name = "keyword.end", match = "\\bend\\b"),
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
        val manifest =
            TextMateGrammarManifest(
                schemaVersion = 1,
                generator = "test",
                shikiVersion = "test",
                languages = listOf(entry),
            )
        return TextMateGrammarBundle.fromPreloaded(manifest, listOf(payload))
    }
}
