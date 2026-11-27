import Foundation
import Testing
@testable import OpenCode_Mobile

struct SessionChatComposerStateTests {
    @Test func selectedVariantLabel_titleCasesEffort() {
        var state = SessionChatComposerState(
            text: "",
            selectedModel: nil,
            modelOptions: [],
            selectedMode: SelectableAgent.defaultID,
            selectedVariant: "high"
        )
        #expect(state.selectedVariantLabel.resolved == "High")
        state.selectedVariant = "very-high"
        #expect(state.selectedVariant == "very-high")
        #expect(state.selectedVariantLabel.resolved == "Very High")
        state.selectedVariant = nil
        #expect(state.selectedVariantLabel.resolved == "Default")
    }

    /// The model chip is app text only while loading or with no model chosen.
    @Test func selectedModelLabel_keysOnlyLoadingAndPlaceholder() {
        var state = SessionChatComposerState(text: "", selectedModel: nil, modelOptions: [], selectedMode: SelectableAgent.defaultID)
        guard case let .localized(loading) = state.selectedModelLabel.storage else {
            Issue.record("loading is not localised")
            return
        }
        #expect(loading.key == "session_chat.model_chip.loading")
        #expect(String(localized: loading) == "Loading model\u{2026}")
        state.isModelSelectionReady = true
        guard case let .localized(placeholder) = state.selectedModelLabel.storage else {
            Issue.record("placeholder is not localised")
            return
        }
        #expect(placeholder.key == "session_chat.model_chip.placeholder")
        #expect(String(localized: placeholder) == "Model")
    }

    /// The server's variant names stay verbatim; only the app's own Default is looked up.
    @Test func variantText_keysOnlyTheAppDefault() {
        #expect(SessionChatVariantText.label("high") == .verbatim("High"))
        for variant in [nil, "Default", "default"] as [String?] {
            guard case let .localized(resource) = SessionChatVariantText.label(variant).storage else {
                Issue.record("\(variant ?? "nil") is not localised")
                continue
            }
            #expect(resource.key == "session_chat.variant_default")
            #expect(String(localized: resource) == "Default")
        }
    }

    @Test func selectedModelLabel_titleCasesIdFallback() {
        let option = ModelSelectionOption(
            providerID: "xai",
            providerName: "xAI",
            modelID: "grok-4.6",
            modelName: "",
            variants: []
        )
        let state = SessionChatComposerState(
            text: "",
            selectedModel: ModelSelection(option: option),
            modelOptions: [option],
            selectedMode: SelectableAgent.defaultID,
            isModelSelectionReady: true
        )
        #expect(state.selectedModelLabel == .verbatim("Grok 4.6"))
    }
}
