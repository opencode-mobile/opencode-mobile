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
}
