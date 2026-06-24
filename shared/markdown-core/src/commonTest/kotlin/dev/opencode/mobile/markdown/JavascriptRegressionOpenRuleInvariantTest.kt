package dev.opencode.mobile.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject

class JavascriptRegressionOpenRuleInvariantTest {
    @Test
    fun multilineCommentDoesNotCloseFromTheFollowingLine() {
        val grammar =
            TextMateGrammar(
                name = "comments",
                scopeName = "source.test",
                patterns =
                    listOf(
                        TextMatePattern(
                            name = "comment.block.test",
                            begin = "/\\*",
                            end = "\\*/",
                        ),
                        TextMatePattern(name = "keyword.test", match = "\\bafter\\b"),
                    ),
            )
        val payload =
            TextMateGrammarPayload(
                descriptor =
                    TextMateGrammarDescriptor(
                        name = "comments",
                        displayName = "Comments",
                        scopeName = grammar.scopeName,
                        resourcePath = "test",
                        embeddedLanguages = emptyList(),
                        aliases = emptyList(),
                    ),
                json = buildJsonObject { },
                grammar = grammar,
            )
        val tokenizer = OpenCodeTextMateNativeTokenizer(KotlinRegexTextMateRegexBackend)

        val first = tokenizer.tokenizeLine(payload, "/* foo", initialState = TextMateLineState.Initial)
        val second = tokenizer.tokenizeLine(payload, "bar */ after", initialState = first.state)

        assertTrue(first.state.openRules.isNotEmpty(), "comment state closed from the next line")
        assertTrue(second.state.openRules.isEmpty(), "comment state did not close on its own line")
        assertEquals(1, second.tokens.count { it.scopes.contains("keyword.test") })
    }

    @Test
    fun javascriptRegressionFixturesLeaveNoOpenRulesAtEof() {
        val fixtureIds =
            listOf(
                "javascript-request-repro",
                "javascript-http-error-repro",
                "javascript-private-field-template",
                "javascript-event-emitter-repro",
                "javascript-http-client-class-repro",
                "javascript-patch-path-repro",
            )

        fixtureIds.forEach { fixtureId ->
            val fixture = HighlighterGoldenFixtures.all.single { it.id == fixtureId }
            val bundle = testJavascriptGrammarBundle()
            val grammar = bundle.primaryGrammarFor("javascript") ?: error("missing javascript grammar")
            val tokenizer = OpenCodeTextMateNativeTokenizer(KotlinRegexTextMateRegexBackend)
            var state = TextMateLineState.Initial
            fixture.code.split("\n").forEach { line ->
                state = tokenizer.tokenizeLine(grammar, line, bundle, initialState = state).state
            }

            val leakedTemplates = state.openRules.filter { it.endPattern == "`" }
            val leakedClasses = state.openRules.filter { it.pattern.name?.contains("class") == true }
            assertTrue(
                leakedTemplates.isEmpty(),
                "$fixtureId leaked template rules: ${leakedTemplates.map { it.endPattern }}",
            )
            assertTrue(
                leakedClasses.isEmpty(),
                "$fixtureId leaked class rules: ${leakedClasses.map { it.pattern.name }}",
            )
        }
    }
}
