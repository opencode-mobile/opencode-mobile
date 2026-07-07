package dev.opencode.mobile.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenCodeTextMateGrammarResourceAndroidTest {
    @Test
    fun bundledTextMateGrammarResourcesLoadFromAndroidClasspath() {
        val manifestText = readClasspathResource("textmate-grammars/manifest.json")
        assertNotNull(manifestText, "manifest resource should be on the unit test classpath")

        val decodedManifest =
            runCatching { OpenCodeTextMateGrammarManifest.decode(manifestText) }
                .getOrElse { error("manifest decode failed: ${it.message}") }
        assertTrue(decodedManifest.languages.isNotEmpty())

        val result =
            OpenCodeTextMateGrammarBundleLoader.loadBundled(
                reader =
                    OpenCodeTextMateResourceReader { resourcePath ->
                        readClasspathResource(resourcePath)
                    },
            )
        val bundle = assertNotNull(result.bundle, result.diagnostics.joinToString { it.message })

        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.joinToString { it.message })
        assertTrue(
            bundle.grammars.isEmpty(),
            "Bundled grammars should load lazily; expected none before first lookup",
        )

        TextMateGrammarResourceFixtures.all.forEach { fixture ->
            val payload =
                assertNotNull(
                    bundle.grammarForScope(fixture.scopeName),
                    "${fixture.scopeName} diagnostics=${bundle.diagnostics.joinToString { it.message }}",
                )
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

        assertTrue(
            bundle.diagnostics.isEmpty(),
            bundle.diagnostics.joinToString("\n") { "${it.resourcePath}: ${it.message}" },
        )
        assertEquals(TextMateGrammarResourceFixtures.all.size, bundle.grammars.size)
    }

    private fun readClasspathResource(resourcePath: String): String? =
        javaClass.classLoader
            ?.getResource(resourcePath)
            ?.readText(Charsets.UTF_8)
}
