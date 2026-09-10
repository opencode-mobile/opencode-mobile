package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.model.ModelSelection
import dev.opencode.mobile.domain.model.ModelSelectionOption
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionChatComposerStateTest {
    @Test
    fun selectedVariantLabel_titleCasesEffort() {
        val state = SessionChatComposerState(selectedVariant = "high")
        assertEquals("High", state.selectedVariantLabel)
        val hyphenated = SessionChatComposerState(selectedVariant = "very-high")
        assertEquals("very-high", hyphenated.selectedVariant)
        assertEquals("Very High", hyphenated.selectedVariantLabel)
        assertEquals("Default", SessionChatComposerState().selectedVariantLabel)
    }

    @Test
    fun selectedModelLabel_titleCasesIdFallback() {
        val option =
            ModelSelectionOption(
                providerId = "xai",
                providerName = "xAI",
                modelId = "grok-4.6",
                modelName = "",
            )
        val state =
            SessionChatComposerState(
                selectedModel = ModelSelection(option),
                isModelSelectionReady = true,
            )
        assertEquals("Grok 4.6", state.selectedModelLabel)
    }
}
