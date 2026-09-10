import Foundation
import Testing
@testable import OpenCode_Mobile

struct ModelSelectionOptionTests {
    private let option = ModelSelectionOption(
        providerID: "openrouter",
        providerName: "OpenRouter",
        modelID: "claude-sonnet",
        modelName: "Claude Sonnet",
        variants: []
    )

    @Test func modelLabel_titleCasesIdWhenNameIsBlank() {
        let unnamed = ModelSelectionOption(
            providerID: "xai",
            providerName: "xAI",
            modelID: "grok-4.6",
            modelName: "",
            variants: []
        )
        #expect(unnamed.modelLabel == "Grok 4.6")
        #expect(option.modelLabel == "Claude Sonnet")
    }

    @Test func matchesFilter_emptyQueryMatchesAll() {
        #expect(option.matchesFilter(""))
        #expect(option.matchesFilter("   "))
    }

    @Test func matchesFilter_matchesDisplayLabelModelAndProviderFields() {
        #expect(option.matchesFilter("claude"))
        #expect(option.matchesFilter("OpenRouter"))
        #expect(option.matchesFilter("sonnet"))
        #expect(option.matchesFilter("openrouter"))
    }

    @Test func matchesFilter_isCaseInsensitive() {
        #expect(option.matchesFilter("CLAUDE"))
        #expect(option.matchesFilter("openrouter"))
    }

    @Test func matchesFilter_rejectsUnrelatedQueries() {
        #expect(!option.matchesFilter("gpt"))
        #expect(!option.matchesFilter("anthropic"))
    }

    @Test func catalogProviders_preserveFirstProviderOrderAndGroupModels() {
        let secondOpenRouterModel = ModelSelectionOption(
            providerID: "openrouter",
            providerName: "OpenRouter",
            modelID: "gpt-oss",
            modelName: "GPT OSS",
            variants: []
        )
        let anthropicModel = ModelSelectionOption(
            providerID: "anthropic",
            providerName: "Anthropic",
            modelID: "claude-opus",
            modelName: "Claude Opus",
            variants: []
        )
        let catalog = ModelSelectionCatalog(
            options: [option, anthropicModel, secondOpenRouterModel],
            serverDefault: nil
        )

        #expect(catalog.providers.map(\.id) == ["openrouter", "anthropic"])
        #expect(catalog.providers[0].models.map(\.modelID) == ["claude-sonnet", "gpt-oss"])
        #expect(catalog.providers[1].models.map(\.modelID) == ["claude-opus"])
    }
}
