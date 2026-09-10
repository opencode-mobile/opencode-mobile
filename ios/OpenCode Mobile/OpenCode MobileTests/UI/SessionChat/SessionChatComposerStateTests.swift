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
        #expect(state.selectedVariantLabel == "High")
        state.selectedVariant = nil
        #expect(state.selectedVariantLabel == "Default")
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
        #expect(state.selectedModelLabel == "Grok 4.6")
    }
}
