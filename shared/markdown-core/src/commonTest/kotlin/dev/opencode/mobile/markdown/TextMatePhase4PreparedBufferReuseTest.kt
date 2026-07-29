package dev.opencode.mobile.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextMatePhase4PreparedBufferReuseTest {
    @Test
    fun preparedLineBufferReusesOneUtf8IndexMapAcrossNativeSearches() {
        val events = mutableListOf<TextMateMetricEvent>()
        val sink = OpenCodeTextMateMetricsSink(events::add)
        val grammar =
            TextMateGrammar(
                name = "reuse",
                scopeName = "source.reuse",
                patterns =
                    listOf(
                        TextMatePattern(name = "kw.a", match = "\\balpha\\b"),
                        TextMatePattern(name = "kw.b", match = "\\bbeta\\b"),
                        TextMatePattern(name = "kw.c", match = "\\bgamma\\b"),
                        TextMatePattern(name = "kw.d", match = "\\bdelta\\b"),
                        TextMatePattern(name = "kw.e", match = "\\bepsilon\\b"),
                        TextMatePattern(name = "kw.f", match = "\\bzeta\\b"),
                        TextMatePattern(name = "kw.g", match = "\\beta\\b"),
                        TextMatePattern(name = "kw.h", match = "\\btheta\\b"),
                        TextMatePattern(name = "kw.i", match = "\\biota\\b"),
                        TextMatePattern(name = "kw.j", match = "\\bkappa\\b"),
                    ),
            )
        val descriptor =
            TextMateGrammarDescriptor(
                name = "reuse",
                displayName = "Reuse",
                scopeName = grammar.scopeName,
                resourcePath = "reuse.json",
                embeddedLanguages = emptyList(),
                aliases = emptyList(),
            )
        val payload = TextMateGrammarPayload(descriptor, grammar, GrammarId(0))
        val tokenizer = OpenCodeTextMateNativeTokenizer(KotlinRegexTextMateRegexBackend, sink)

        tokenizer.tokenizeLine(payload, "alpha beta gamma delta epsilon zeta eta theta iota kappa")

        val prepared = events.count { it.stage == TextMateMetricStage.PreparedLineBuffer }
        val maps = events.count { it.stage == TextMateMetricStage.Utf8IndexMap }
        val searches = events.count { it.stage == TextMateMetricStage.RegexSearch }
        assertEquals(prepared, maps, "each prepared buffer builds exactly one UTF-8 index map")
        assertTrue(prepared >= 1, "at least one prepared line buffer should be built")
        assertTrue(searches >= 10, "multi-rule line should perform many regex searches")
        assertTrue(maps < searches, "maps must be reused across searches on the same line")
    }

    @Test
    fun grammarProgramDropsRawJsonAndAssignsStableIds() {
        val grammar =
            TextMateGrammar(
                name = "ids",
                scopeName = "source.ids",
                patterns =
                    listOf(
                        TextMatePattern(match = "a", name = "a"),
                        TextMatePattern(begin = "b", end = "c", name = "bc"),
                    ),
                repository =
                    mapOf(
                        "repo" to TextMatePattern(match = "r", name = "repo"),
                    ),
            )
        val program = TextMateGrammarProgram.compile(grammar, GrammarId(7))
        assertEquals(GrammarId(7), program.grammarId)
        assertEquals(listOf(RuleId(0), RuleId(1)), program.rootRuleIds)
        assertTrue(program.rules.size >= 3)
        assertTrue(program.estimatedBytes > 0)
        assertEquals(RuleId(2), program.repositoryRuleIds["repo"])
    }

    @Test
    fun weightedCoordinatorSkipsLeasedEntriesOnCriticalTrim() {
        val coordinator = TextMateWeightedCacheCoordinator(
            evictionBudgetBytes = 1_000,
            admissionBudgetBytes = 2_000,
        )
        assertTrue(
            coordinator.promote(
                key = "regex:a",
                category = TextMateWeightedCacheCoordinator.Category.CompiledRegex,
                estimatedBytes = 100,
            ),
        )
        assertTrue(
            coordinator.promote(
                key = "regex:b",
                category = TextMateWeightedCacheCoordinator.Category.CompiledRegex,
                estimatedBytes = 100,
            ),
        )
        coordinator.lease("regex:a")
        coordinator.trim(MemoryPressure.Critical)
        assertTrue(coordinator.contains("regex:a"))
        assertTrue(!coordinator.contains("regex:b"))
        coordinator.release("regex:a")
        assertTrue(coordinator.removeIfUnleased("regex:a"))
    }
}
