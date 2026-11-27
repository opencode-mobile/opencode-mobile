package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.model.ModelSelection
import dev.opencode.mobile.domain.model.ModelSelectionOption
import dev.opencode.mobile.domain.text.UiText
import dev.opencode.mobile.ui.text.english
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionChatComposerStateTest {
    @Test
    fun selectedVariantLabel_titleCasesEffort() {
        val state = SessionChatComposerState(selectedVariant = "high")
        assertEquals("High", state.selectedVariantLabel.english())
        val hyphenated = SessionChatComposerState(selectedVariant = "very-high")
        assertEquals("very-high", hyphenated.selectedVariant)
        assertEquals("Very High", hyphenated.selectedVariantLabel.english())
        assertEquals("Default", SessionChatComposerState().selectedVariantLabel.english())
    }

    /** The server's variant names stay verbatim; only the app's own Default is a resource. */
    @Test
    fun variantText_keysOnlyTheAppDefault() {
        assertEquals(UiText.verbatim("High"), SessionChatVariantText.label("high"))
        listOf(null, "Default", "default").forEach { variant ->
            assertEquals(UiText.resource(R.string.session_chat_variant_default), SessionChatVariantText.label(variant))
        }
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
