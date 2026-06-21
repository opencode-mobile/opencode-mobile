package dev.opencode.mobile.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenCodeTextMateGrammarResourceAndroidTest {
    @Test
    fun bundledTextMateGrammarResourcesLoadFromAndroidClasspath() {
        val result =
            OpenCodeTextMateGrammarBundleLoader.loadBundled(
                reader =
                    OpenCodeTextMateResourceReader { resourcePath ->
                        readClasspathResource(resourcePath)
                    },
            )
        val bundle = assertNotNull(result.bundle)

        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.joinToString { it.message })
        assertTrue(bundle.diagnostics.isEmpty(), bundle.diagnostics.joinToString { it.message })
        assertEquals(TextMateGrammarResourceFixtures.all.size, bundle.grammars.size)

        TextMateGrammarResourceFixtures.all.forEach { fixture ->
            val payload = assertNotNull(bundle.grammarForScope(fixture.scopeName), fixture.scopeName)
            val summary = OpenCodeTextMateGrammarDecoder.summarize(payload.grammar)

            assertEquals(fixture.resourcePath, payload.descriptor.resourcePath)
            assertEquals(fixture.name, payload.grammar.name)
            assertEquals(fixture.scopeName, payload.grammar.scopeName)
            assertTrue(summary.patternCount > 0, "${fixture.resourcePath} should expose tokenizer patterns")
            assertTrue(
                summary.includeCount + summary.matchRuleCount + summary.beginEndRuleCount > 0,
                "${fixture.resourcePath} should expose at least one tokenizer rule",
            )
        }
    }

    private fun readClasspathResource(resourcePath: String): String? =
        javaClass.classLoader
            ?.getResourceAsStream(resourcePath)
            ?.bufferedReader()
            ?.use { reader -> reader.readText() }
}
