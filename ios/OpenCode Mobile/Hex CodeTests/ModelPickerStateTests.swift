import Foundation
import OpenCodeKit
import Testing
@testable import Hex_Code

/// The model test gate: Start only after a pass for the exact model and level shown.
struct ModelPickerStateTests {
    static let sonnet = ModelSelectionOption(
        providerID: "anthropic", providerName: "Anthropic", modelID: "claude-sonnet-5-5",
        modelName: "Claude Sonnet 5.5", variants: ["low", "medium", "high"]
    )
    static let opus = ModelSelectionOption(
        providerID: "anthropic", providerName: "Anthropic", modelID: "claude-opus-5-5",
        modelName: "Claude Opus 5.5", variants: ["low", "medium", "high", "max"]
    )
    static let gpt = ModelSelectionOption(
        providerID: "openai", providerName: "OpenAI", modelID: "gpt-5", modelName: "GPT-5", variants: ["minimal", "high"]
    )
    static let qwen = ModelSelectionOption(
        providerID: "ollama", providerName: "Ollama", modelID: "qwen3-coder", modelName: "Qwen3 Coder", variants: []
    )
    static let catalog = ModelSelectionCatalog(options: [sonnet, opus, gpt, qwen], serverDefault: opus)

    @Test func startsOnServerDefaultWithDefaultLevel() {
        let picker = ModelPickerState(catalog: Self.catalog)
        #expect(picker.choice == ModelChoice(providerID: "anthropic", modelID: "claude-opus-5-5", variant: nil))
        #expect(picker.selectedLevel == "Default")
        #expect(picker.reasoningLevels == ["Default", "low", "medium", "high", "max"])
        #expect(picker.currentTest == .notRun)
        #expect(!picker.canStart)
    }

    @Test func startsOnSavedChoiceWhenStillOffered() {
        let saved = ModelChoice(providerID: "openai", modelID: "gpt-5", variant: "high")
        #expect(ModelPickerState(catalog: Self.catalog, saved: saved).choice == saved)
        let gone = ModelChoice(providerID: "openai", modelID: "gpt-4", variant: nil)
        #expect(ModelPickerState(catalog: Self.catalog, saved: gone).choice?.modelID == "claude-opus-5-5")
    }

    @Test func startIsOfferedOnlyAfterAPass() {
        var picker = ModelPickerState(catalog: Self.catalog)
        let tested = picker.beginTest()!
        #expect(picker.isTesting)
        #expect(!picker.canStart)
        #expect(picker.beginTest() == nil)
        picker.finishTest(tested, result: .passed(seconds: 1.2))
        #expect(picker.canStart)
    }

    @Test func failureKeepsStartHidden() {
        var picker = ModelPickerState(catalog: Self.catalog)
        let tested = picker.beginTest()!
        picker.finishTest(tested, result: .failed("Google isn’t set up"))
        #expect(picker.currentTest == .failed("Google isn’t set up"))
        #expect(!picker.canStart)
    }

    @Test func changingTheLevelClearsAPass() {
        var picker = ModelPickerState(catalog: Self.catalog)
        let tested = picker.beginTest()!
        picker.finishTest(tested, result: .passed(seconds: 1))
        picker.selectLevel("high")
        #expect(picker.choice?.variant == "high")
        #expect(picker.currentTest == .notRun)
        #expect(!picker.canStart)
        picker.selectLevel("Default")
        #expect(picker.choice?.variant == nil)
        // Back on the tested choice, its pass counts again.
        #expect(picker.canStart)
    }

    @Test func changingTheModelClearsAPassAndTheLevel() {
        var picker = ModelPickerState(catalog: Self.catalog)
        picker.selectLevel("max")
        let tested = picker.beginTest()!
        picker.finishTest(tested, result: .passed(seconds: 1))
        picker.select(Self.sonnet)
        #expect(picker.choice == ModelChoice(providerID: "anthropic", modelID: "claude-sonnet-5-5", variant: nil))
        #expect(!picker.canStart)
    }

    @Test func aLateResultForAnotherChoiceIsDropped() {
        var picker = ModelPickerState(catalog: Self.catalog)
        let first = picker.beginTest()!
        picker.select(Self.gpt)
        let second = picker.beginTest()!
        picker.finishTest(first, result: .passed(seconds: 1))
        #expect(picker.currentTest == .testing)
        picker.finishTest(second, result: .failed("No key"))
        #expect(picker.currentTest == .failed("No key"))
    }

    @Test func modelWithoutVariantsHasNoLevels() {
        var picker = ModelPickerState(catalog: Self.catalog)
        picker.select(Self.qwen)
        #expect(picker.reasoningLevels.isEmpty)
    }

    @Test func groupsByProviderInCatalogOrderAndMarksDefaultAndChoice() {
        let picker = ModelPickerState(catalog: Self.catalog)
        let sections = picker.sections
        #expect(sections.map(\.title) == ["Anthropic", "OpenAI", "Ollama"])
        #expect(sections[0].rows.map(\.option.modelID) == ["claude-sonnet-5-5", "claude-opus-5-5"])
        #expect(sections[0].rows[1].isServerDefault)
        #expect(sections[0].rows[1].isSelected)
        #expect(sections[0].rows.allSatisfy { $0.providerCaption == nil })
    }

    @Test func searchMatchesNameIdAndProviderInOneFlatList() {
        var picker = ModelPickerState(catalog: Self.catalog)
        picker.query = "openai"
        #expect(picker.sections.count == 1)
        #expect(picker.sections[0].title == nil)
        #expect(picker.sections[0].rows.map(\.option.modelID) == ["gpt-5"])
        #expect(picker.sections[0].rows[0].providerCaption == "OpenAI")
        picker.query = "opus"
        #expect(picker.sections[0].rows.map(\.option.modelID) == ["claude-opus-5-5"])
        picker.query = "qwen3-coder"
        #expect(picker.sections[0].rows.map(\.option.modelID) == ["qwen3-coder"])
        picker.query = "nothing like this"
        #expect(picker.sections.isEmpty)
    }
}

struct ModelTesterTests {
    @Test func bodyCarriesModelRefWithVariantInside() throws {
        let body = ModelTester.body(for: ModelChoice(providerID: "anthropic", modelID: "claude-opus-5-5", variant: "high"))
        let json = try #require(String(data: JSONEncoder().encode(body), encoding: .utf8))
        #expect(json.contains(#""prompt":"Reply with OK""#))
        #expect(json.contains(#""providerID":"anthropic""#))
        #expect(json.contains(#""id":"claude-opus-5-5""#))
        #expect(json.contains(#""variant":"high""#))
    }

    @Test func defaultLevelSendsNoVariant() throws {
        let body = ModelTester.body(for: ModelChoice(providerID: "openai", modelID: "gpt-5", variant: nil))
        let json = try #require(String(data: JSONEncoder().encode(body), encoding: .utf8))
        #expect(!json.contains("variant"))
    }

    @Test func aReplyPasses() {
        let data = Data(#"{"data":{"text":"OK"}}"#.utf8)
        #expect(ModelTester.result(status: 200, data: data, seconds: 1.4) == .passed(seconds: 1.4))
    }

    @Test func anEmptyReplyFails() {
        let data = Data(#"{"data":{"text":"  "}}"#.utf8)
        #expect(ModelTester.result(status: 200, data: data, seconds: 1) == .failed(ModelTestMessages.emptyReply))
    }

    @Test func aServerErrorShowsItsOwnMessage() {
        let data = Data(#"{"_tag":"ServiceUnavailableError","message":"Provider google is not configured\nmore"}"#.utf8)
        #expect(ModelTester.result(status: 503, data: data, seconds: 1) == .failed("Provider google is not configured"))
    }

    @Test func anErrorWithoutAMessageNamesTheStatus() {
        #expect(ModelTester.result(status: 500, data: Data(), seconds: 1) == .failed(ModelTestMessages.httpStatus(500)))
        #expect(ModelTester.result(status: 401, data: Data(), seconds: 1) == .failed(ModelTestMessages.signedOut))
    }
}
