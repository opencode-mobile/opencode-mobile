package dev.opencode.mobile.domain.model

import org.junit.Assert.assertEquals
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
    fun modelLabel_titleCasesIdWhenNameIsBlank() {
        val unnamed =
            ModelSelectionOption(
                providerId = "xai",
                providerName = "xAI",
                modelId = "grok-4.6",
                modelName = "",
            )
        assertEquals("Grok 4.6", unnamed.modelLabel)
        assertEquals("Claude Sonnet", option.modelLabel)
    }

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

    @Test
    fun catalogProviders_preserveFirstProviderOrderAndGroupModels() {
        val secondOpenRouterModel =
            ModelSelectionOption(
                providerId = "openrouter",
                providerName = "OpenRouter",
                modelId = "gpt-oss",
                modelName = "GPT OSS",
            )
        val anthropicModel =
            ModelSelectionOption(
                providerId = "anthropic",
                providerName = "Anthropic",
                modelId = "claude-opus",
                modelName = "Claude Opus",
            )
        val catalog =
            ModelSelectionCatalog(
                options = listOf(option, anthropicModel, secondOpenRouterModel),
                serverDefault = null,
            )

        assertEquals(listOf("openrouter", "anthropic"), catalog.providers.map { it.id })
        assertEquals(listOf("claude-sonnet", "gpt-oss"), catalog.providers[0].models.map { it.modelId })
        assertEquals(listOf("claude-opus"), catalog.providers[1].models.map { it.modelId })
    }
}
