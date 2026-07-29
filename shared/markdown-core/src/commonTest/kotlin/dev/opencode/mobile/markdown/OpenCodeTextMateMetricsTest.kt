package dev.opencode.mobile.markdown

import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertTrue

class OpenCodeTextMateMetricsTest {
    @Test
    fun highlighterRecordsBoundedStageMetricsWithoutSourceIdentity() {
        val events = mutableListOf<TextMateMetricEvent>()
        val sink = OpenCodeTextMateMetricsSink(events::add)
        val descriptor =
            TextMateGrammarDescriptor(
                name = "phase-one-metrics",
                displayName = "Phase One Metrics",
                scopeName = "source.phase-one-metrics",
                resourcePath = "metrics.json",
                embeddedLanguages = emptyList(),
                aliases = emptyList(),
            )
        val grammar =
            TextMateGrammar(
                name = descriptor.name,
                scopeName = descriptor.scopeName,
                patterns =
                    listOf(
                        TextMatePattern(
                            match = "\\bphaseOneMetricUnique\\b",
                            name = "keyword.control.metrics",
                        ),
                    ),
            )
        val payload = TextMateGrammarPayload(descriptor, buildJsonObject {}, grammar)
        val manifest =
            TextMateGrammarManifest(
                schemaVersion = 1,
                generator = "test",
                shikiVersion = "test",
                languages =
                    listOf(
                        TextMateGrammarManifestEntry(
                            language = descriptor.name,
                            displayName = descriptor.displayName,
                            aliases = emptyList(),
                            grammarModule = "test",
                            grammars = listOf(descriptor),
                        ),
                    ),
            )
        val bundle = TextMateGrammarBundle.fromPreloaded(manifest, listOf(payload), metricsSink = sink)
        val highlighter =
            OpenCodeSyntaxHighlighter(
                grammarBundle = bundle,
                textMateTokenizer = OpenCodeTextMateNativeTokenizer(KotlinRegexTextMateRegexBackend, sink),
                metricsSink = sink,
            )

        val result = highlighter.highlight(descriptor.name, "phaseOneMetricUnique")

        assertTrue(result.spans.isNotEmpty())
        val stages = events.mapTo(mutableSetOf()) { event -> event.stage }
        assertTrue(TextMateMetricStage.GrammarLoad in stages)
        assertTrue(TextMateMetricStage.GrammarLookup in stages)
        assertTrue(TextMateMetricStage.RuleResolve in stages)
        assertTrue(TextMateMetricStage.RegexCompile in stages)
        assertTrue(TextMateMetricStage.RegexSearch in stages)
        assertTrue(TextMateMetricStage.Tokenize in stages)
        assertTrue(TextMateMetricStage.Repair in stages)
        assertTrue(events.all { event -> event.backend?.contains("phaseOneMetricUnique") != true })
    }
}
