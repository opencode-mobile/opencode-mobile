package dev.opencode.mobile.domain.text

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleCaseIdentifierTest {
    @Test
    fun displayName_titleCasesModelVariantAndEffortIds() {
        assertEquals("Build", TitleCaseIdentifier.displayName("build"))
        assertEquals("Grok 4.6", TitleCaseIdentifier.displayName("grok-4.6"))
        assertEquals("High", TitleCaseIdentifier.displayName("high"))
        assertEquals("Very High", TitleCaseIdentifier.displayName("very-high"))
        assertEquals("Default", TitleCaseIdentifier.displayName("Default"))
        assertEquals("Code Reviewer", TitleCaseIdentifier.displayName("code-reviewer"))
        assertEquals("Claude Sonnet 4", TitleCaseIdentifier.displayName("Claude Sonnet 4"))
    }
}
