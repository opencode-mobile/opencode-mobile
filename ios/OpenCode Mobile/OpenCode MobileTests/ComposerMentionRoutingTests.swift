import Foundation
import Testing
@testable import OpenCode_Mobile

struct ComposerMentionRoutingTests {
    private let explore = MentionableAgent(name: "explore", description: "Read-only explorer")
    private let general = MentionableAgent(name: "general", description: "General helper")
    private var agents: [MentionableAgent] { [explore, general] }

    @Test func atQuery_afterWhitespace_returnsFilter() {
        #expect(ComposerMentionRouting.atSuggestionQuery("hello @", cursor: 7) == "")
        #expect(ComposerMentionRouting.atSuggestionQuery("hello @ex", cursor: 9) == "ex")
    }

    @Test func atQuery_atStart_returnsFilter() {
        #expect(ComposerMentionRouting.atSuggestionQuery("@", cursor: 1) == "")
        #expect(ComposerMentionRouting.atSuggestionQuery("@gen", cursor: 4) == "gen")
    }

    @Test func atQuery_emailLike_isClosed() {
        #expect(ComposerMentionRouting.atSuggestionQuery("hello@ex", cursor: 8) == nil)
    }

    @Test func atQuery_whitespaceAfterAt_isClosed() {
        #expect(ComposerMentionRouting.atSuggestionQuery("hello @ ex", cursor: 10) == nil)
    }

    @Test func atQuery_usesTextBeforeCursorOnly() {
        #expect(ComposerMentionRouting.atSuggestionQuery("hello @ex more", cursor: 9) == "ex")
        #expect(ComposerMentionRouting.atSuggestionQuery("hello @ex more", cursor: 14) == nil)
    }

    @Test func atQuery_completedMentionAtCaret_isClosed() {
        let mentions = [AgentMention(id: "m1", name: "explore", start: 0, end: 8)]
        #expect(
            ComposerMentionRouting.atSuggestionQuery("@explore", cursor: 8, mentions: mentions) == nil
        )
        #expect(ComposerMentionRouting.atSuggestionQuery("@ex", cursor: 3) == "ex")
    }

    @Test func suggestionMode_atWinsOverSlash() {
        #expect(ComposerMentionRouting.suggestionMode(text: "/init @", cursor: 7) == .at(query: ""))
        #expect(ComposerMentionRouting.suggestionMode(text: "/in", cursor: 3) == .slash(query: "in"))
        #expect(ComposerMentionRouting.suggestionMode(text: "hello", cursor: 5) == .none)
    }

    @Test func filterAgents_matchesNameAndDescription() {
        #expect(ComposerMentionRouting.filterAgents(agents, query: "ex").map(\.name) == ["explore"])
        #expect(ComposerMentionRouting.filterAgents(agents, query: "helper").map(\.name) == ["general"])
        #expect(ComposerMentionRouting.filterAgents(agents, query: "") == agents)
    }

    @Test func insertMention_replacesAtTokenAndTracksRange() {
        let result = ComposerMentionRouting.insertMention(
            text: "ask @ex",
            cursor: 7,
            agentName: "explore",
            mentionID: "m1"
        )
        #expect(result.text == "ask @explore ")
        #expect(result.cursor == 13)
        #expect(result.mentions == [AgentMention(id: "m1", name: "explore", start: 4, end: 12)])
    }

    @Test func insertMention_allowsMultipleMentions() {
        let first = ComposerMentionRouting.insertMention(
            text: "@",
            cursor: 1,
            agentName: "explore",
            mentionID: "m1"
        )
        let secondText = first.text + "and @"
        let second = ComposerMentionRouting.insertMention(
            text: secondText,
            cursor: secondText.count,
            agentName: "general",
            mentionID: "m2",
            existingMentions: first.mentions
        )
        #expect(second.text == "@explore and @general ")
        #expect(second.mentions.map(\.name) == ["explore", "general"])
    }

    @Test func reconcile_partialDeleteInsideMention_removesWholeToken() {
        let result = ComposerMentionRouting.reconcileAfterEdit(
            previousText: "ask @explore please",
            previousMentions: [AgentMention(id: "m1", name: "explore", start: 4, end: 12)],
            proposedText: "ask @explor please",
            proposedCursor: 11
        )
        #expect(result.text == "ask  please")
        #expect(result.mentions.isEmpty)
        #expect(result.cursor == 4)
    }

    @Test func reconcile_editBeforeMention_shiftsRanges() {
        let result = ComposerMentionRouting.reconcileAfterEdit(
            previousText: "ask @explore",
            previousMentions: [AgentMention(id: "m1", name: "explore", start: 4, end: 12)],
            proposedText: "please ask @explore",
            proposedCursor: 7
        )
        #expect(result.text == "please ask @explore")
        #expect(result.mentions == [AgentMention(id: "m1", name: "explore", start: 11, end: 19)])
    }

    @Test func reconcile_typedAtWithoutSelection_staysPlainText() {
        let result = ComposerMentionRouting.reconcileAfterEdit(
            previousText: "hello ",
            previousMentions: [],
            proposedText: "hello @ex",
            proposedCursor: 9
        )
        #expect(result.text == "hello @ex")
        #expect(result.mentions.isEmpty)
    }

    @Test func agentParts_forSelectedMentionsOnly() {
        let parts = ComposerMentionRouting.agentParts(
            text: "@explore and @general",
            mentions: [
                AgentMention(id: "m1", name: "explore", start: 0, end: 8),
                AgentMention(id: "m2", name: "general", start: 13, end: 21),
            ]
        )
        #expect(parts.count == 2)
        #expect(parts[0].name == "explore")
        #expect(parts[0].sourceValue == "@explore")
        #expect(parts[0].start == 0)
        #expect(parts[0].end == 8)
        #expect(parts[1].name == "general")
        #expect(parts[1].start == 13)
        #expect(parts[1].end == 21)
    }
}
