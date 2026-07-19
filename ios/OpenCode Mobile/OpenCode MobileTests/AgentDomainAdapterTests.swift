import Foundation
import Testing
@testable import OpenCode_Mobile

struct AgentDomainAdapterTests {
    private let adapter = AgentDomainAdapter()

    @Test func toMentionableAgents_filtersPrimaryAndHidden() {
        let agents = adapter.toMentionableAgents([
            AgentDTO(name: "build", description: "Primary", mode: "primary", hidden: false),
            AgentDTO(name: "explore", description: "Explorer", mode: "subagent", hidden: false),
            AgentDTO(name: "title", description: nil, mode: "subagent", hidden: true),
            AgentDTO(name: "reviewer", description: "  ", mode: "all", hidden: nil),
            AgentDTO(name: "general", description: "General", mode: "subagent", hidden: false),
        ])
        #expect(agents.map(\.name) == ["explore", "reviewer", "general"])
        #expect(agents[0].description == "Explorer")
        #expect(agents[1].description == nil)
    }
}
