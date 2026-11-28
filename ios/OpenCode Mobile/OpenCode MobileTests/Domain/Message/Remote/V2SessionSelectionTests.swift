import Foundation
import Testing
@testable import OpenCode_Mobile

struct V2SessionSelectionTests {
    private let gpt5 = V2PromptModelRefDTO(id: "gpt-5", providerID: "openai", variant: nil)

    @Test func newSessionWithoutAgentOrModelPostsBoth() {
        let switches = V2SelectionSwitches.resolve(current: session(agent: nil, model: nil), agent: "build", model: request(gpt5))

        #expect(switches.agent == "build")
        #expect(switches.model?.model == gpt5)
    }

    @Test func unchangedAgentAndModelPostsNothing() {
        let switches = V2SelectionSwitches.resolve(current: session(agent: "build", model: gpt5), agent: "build", model: request(gpt5))

        #expect(switches.agent == nil)
        #expect(switches.model == nil)
    }

    @Test func changedAgentPostsAgentOnly() {
        let switches = V2SelectionSwitches.resolve(current: session(agent: "build", model: gpt5), agent: "plan", model: request(gpt5))

        #expect(switches.agent == "plan")
        #expect(switches.model == nil)
    }

    @Test func switchingBackToEarlierAgentPostsIt() {
        let switches = V2SelectionSwitches.resolve(current: session(agent: "plan", model: gpt5), agent: "build", model: nil)

        #expect(switches.agent == "build")
    }

    @Test func changedVariantPostsModel() {
        let high = V2PromptModelRefDTO(id: "gpt-5", providerID: "openai", variant: "high")

        #expect(V2SelectionSwitches.resolve(current: session(agent: nil, model: gpt5), agent: nil, model: request(high)).model?.model == high)
        #expect(V2SelectionSwitches.resolve(current: session(agent: nil, model: high), agent: nil, model: request(gpt5)).model?.model == gpt5)
    }

    @Test func defaultVariantMatchesNoVariantLikeTheServer() {
        let current = V2PromptModelRefDTO(id: "gpt-5", providerID: "openai", variant: "default")

        #expect(V2SelectionSwitches.resolve(current: session(agent: nil, model: current), agent: nil, model: request(gpt5)).model == nil)
    }

    @Test func otherProviderOrModelPostsModel() {
        let azure = V2PromptModelRefDTO(id: "gpt-5", providerID: "azure", variant: nil)
        let mini = V2PromptModelRefDTO(id: "gpt-5-mini", providerID: "openai", variant: nil)
        let current = session(agent: nil, model: gpt5)

        #expect(V2SelectionSwitches.resolve(current: current, agent: nil, model: request(azure)).model?.model == azure)
        #expect(V2SelectionSwitches.resolve(current: current, agent: nil, model: request(mini)).model?.model == mini)
    }

    @Test func unknownSessionPostsEveryPick() {
        let switches = V2SelectionSwitches.resolve(current: nil, agent: "build", model: request(gpt5))

        #expect(switches.agent == "build")
        #expect(switches.model?.model == gpt5)
    }

    @Test func emptyOrMissingPicksPostNothing() {
        let switches = V2SelectionSwitches.resolve(current: nil, agent: "", model: nil)

        #expect(switches.agent == nil)
        #expect(switches.model == nil)
    }

    @Test func sessionSelectionDecodesAgentAndModelFromSession() throws {
        let json = #"{"id":"ses_1","title":"t","agent":"plan","model":{"id":"gpt-5","providerID":"openai","variant":"high"},"cost":0}"#

        let decoded = try JSONDecoder().decode(V2SessionSelectionDTO.self, from: Data(json.utf8))

        #expect(decoded == V2SessionSelectionDTO(agent: "plan", model: V2PromptModelRefDTO(id: "gpt-5", providerID: "openai", variant: "high")))
    }

    @Test func sessionSelectionWithoutAgentOrModelDecodesAsUnset() throws {
        let decoded = try JSONDecoder().decode(V2SessionSelectionDTO.self, from: Data(#"{"id":"ses_1","title":"t"}"#.utf8))

        #expect(decoded == V2SessionSelectionDTO(agent: nil, model: nil))
    }

    private func session(agent: String?, model: V2PromptModelRefDTO?) -> V2SessionSelectionDTO {
        V2SessionSelectionDTO(agent: agent, model: model)
    }

    private func request(_ model: V2PromptModelRefDTO) -> V2SwitchModelRequestDTO {
        V2SwitchModelRequestDTO(model: model)
    }
}
