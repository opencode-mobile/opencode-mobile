package dev.opencode.mobile.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenCodeSemanticFallbackIosTest {
    @Test
    fun unterminatedLongJavaScriptStringDoesNotOverflowTheNativeStack() {
        val code = "const value = \"${"a".repeat(3_968)}"

        val result = OpenCodeSemanticFallbackHighlighter.highlight("javascript", code)

        assertTrue(result.spans.any { span -> span.role == CodeTokenRole.Keyword })
        val stringSpan = result.spans.single { span -> span.role == CodeTokenRole.String }
        assertEquals(code.indexOf('"'), stringSpan.start)
        assertEquals(code.length, stringSpan.end)
    }
}
