package dev.opencode.mobile.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpClientPrivateFieldHighlighterDiagnosticTest {
    @Test
    fun privateFieldDeclarationsAndMemberAccessUseConsistentRoles() {
        val fixture =
            HighlighterGoldenFixtures.all.single { it.id == "javascript-http-client-class-repro" }
        val highlighter =
            OpenCodeSyntaxHighlighter(
                grammarBundle = bundledGrammarBundle(),
                textMateTokenizer = OpenCodeTextMateNativeTokenizer(KotlinRegexTextMateRegexBackend),
            )

        val result = highlighter.highlight(fixture.language, fixture.code)
        val privateFields = listOf("#baseUrl", "#headers", "#interceptors", "#timeout")

        privateFields.forEach { field ->
            val declarationStart = fixture.code.indexOf(field)
            assertTrue(declarationStart >= 0, "missing declaration for $field")
            val declarationEnd = declarationStart + field.length
            val declarationSpan =
                result.spans.singleOrNull { span ->
                    span.start <= declarationStart && span.end >= declarationEnd && span.role != CodeTokenRole.Plain
                }
            assertTrue(
                declarationSpan != null,
                "$field declaration at $declarationStart..$declarationEnd missing span in ${result.spans}",
            )
            assertEquals(
                CodeTokenRole.Variable,
                declarationSpan.role,
                "$field declaration should use Variable role",
            )
        }

        val memberAccessRoles =
            privateFields.map { field ->
                val needle = "this.$field"
                val start = fixture.code.indexOf(needle)
                assertTrue(start >= 0, "missing member access for $needle")
                val fieldStart = start + "this.".length
                val fieldEnd = fieldStart + field.length
                val span =
                    result.spans.single { span ->
                        span.start <= fieldStart && span.end >= fieldEnd && span.role != CodeTokenRole.Plain
                    }
                assertEquals(
                    CodeTokenRole.Variable,
                    span.role,
                    "$needle should use Variable role",
                )
                span.role
            }
        assertEquals(1, memberAccessRoles.distinct().size)
    }

    private fun bundledGrammarBundle(): TextMateGrammarBundle = testJavascriptGrammarBundle()
}
