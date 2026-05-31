package dev.opencode.mobile.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSelectionOptionTest {
    private val option =
        ModelSelectionOption(
            providerId = "openrouter",
            providerName = "OpenRouter",
            modelId = "claude-sonnet",
            modelName = "Claude Sonnet",
        )

    @Test
    fun matchesFilter_emptyQueryMatchesAll() {
        assertTrue(option.matchesFilter(""))
        assertTrue(option.matchesFilter("   "))
    }

    @Test
    fun matchesFilter_matchesDisplayLabelModelAndProviderFields() {
        assertTrue(option.matchesFilter("claude"))
        assertTrue(option.matchesFilter("OpenRouter"))
        assertTrue(option.matchesFilter("sonnet"))
        assertTrue(option.matchesFilter("openrouter"))
    }

    @Test
    fun matchesFilter_isCaseInsensitive() {
        assertTrue(option.matchesFilter("CLAUDE"))
        assertTrue(option.matchesFilter("OPENROUTER"))
    }

    @Test
    fun matchesFilter_rejectsUnrelatedQueries() {
        assertFalse(option.matchesFilter("gpt"))
        assertFalse(option.matchesFilter("anthropic"))
    }
}
