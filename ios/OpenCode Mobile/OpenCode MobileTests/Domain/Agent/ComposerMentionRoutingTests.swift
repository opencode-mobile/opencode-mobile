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

    @Test func suggestionMode_closedWhenMentionsAndSlashAreOff() {
        #expect(
            ComposerMentionRouting.suggestionMode(text: "@ex", cursor: 3, allowsMentions: false) == .none
        )
        #expect(
            ComposerMentionRouting.suggestionMode(text: "/in", cursor: 3, allowsSlashCommands: false) == .none
        )
        #expect(
            ComposerMentionRouting.suggestionMode(text: "/in", cursor: 3, allowsMentions: false) == .slash(query: "in")
        )
    }

    @Test func withoutMentionTokens_dropsMentionShapedTokensAndKeepsAddresses() {
        #expect(ComposerMentionRouting.withoutMentionTokens(text: "hello @codex please") == "hello please")
        #expect(ComposerMentionRouting.withoutMentionTokens(text: "email a@b.com") == "email a@b.com")
        #expect(ComposerMentionRouting.withoutMentionTokens(text: "@codex") == "")
        #expect(ComposerMentionRouting.withoutMentionTokens(text: "ping @") == "ping")
        #expect(ComposerMentionRouting.withoutMentionTokens(text: "see @ later") == "see @ later")
        #expect(ComposerMentionRouting.withoutMentionTokens(text: "@codex hello") == "hello")
        #expect(
            ComposerMentionRouting.withoutMentionTokens(
                text: "ask @explore now",
                mentions: [AgentMention(id: "1", name: "explore", start: 4, end: 12)]
            ) == "ask now"
        )
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

    @Test func insertFileMention_shiftsAgentMentionsAndKeepsThemSeparate() {
        let agents = [AgentMention(id: "m1", name: "explore", start: 0, end: 8)]
        let result = ComposerMentionRouting.insertFileMention(
            text: "@explore look at @App",
            cursor: 22,
            path: "src/App.kt",
            mentionID: "f1",
            existingMentions: agents
        )
        #expect(result.text == "@explore look at @src/App.kt ")
        #expect(result.mentions == agents)
        #expect(result.fileMentions == [FileMention(id: "f1", path: "src/App.kt", start: 17, end: 28)])
    }

    @Test func reconcile_atomicDeleteOfFileMentionShiftsFollowingAgent() {
        let result = ComposerMentionRouting.reconcileAfterEdit(
            previousText: "@src/App.kt @explore",
            previousMentions: [AgentMention(id: "m1", name: "explore", start: 12, end: 20)],
            proposedText: "@src/App.k @explore",
            proposedCursor: 9,
            previousFileMentions: [FileMention(id: "f1", path: "src/App.kt", start: 0, end: 11)]
        )
        #expect(result.text.trimmingCharacters(in: .whitespacesAndNewlines) == "@explore")
        #expect(result.fileMentions.isEmpty)
        #expect(result.mentions.count == 1)
        #expect(result.mentions[0].start == 1)
        let mention = result.mentions[0]
        let token = result.text[result.text.index(result.text.startIndex, offsetBy: mention.start) ..< result.text.index(result.text.startIndex, offsetBy: mention.end)]
        #expect(String(token) == "@explore")
    }

    @Test func fileParts_requireTheTokenToStillMatch() {
        let parts = ComposerMentionRouting.fileParts(
            text: "see @src/App.kt",
            mentions: [FileMention(id: "f1", path: "src/App.kt", start: 4, end: 15)]
        )
        #expect(parts.count == 1)
        #expect(parts[0].path == "src/App.kt")
        #expect(parts[0].filename == "App.kt")
        #expect(parts[0].sourceValue == "@src/App.kt")
        #expect(
            ComposerMentionRouting.fileParts(
                text: "see @other",
                mentions: [FileMention(id: "f1", path: "src/App.kt", start: 4, end: 10)]
            ).isEmpty
        )
    }

    @Test func atQuery_insideFileMentionStaysClosed() {
        let files = [FileMention(id: "f1", path: "my file.kt", start: 0, end: 11)]
        #expect(ComposerMentionRouting.atSuggestionQuery("@my file.kt", cursor: 4, fileMentions: files) == nil)
    }

    @Test func projectFileURL_encodesSpacesAndKeepsSlashes() {
        #expect(ProjectPaths.fileURL(worktree: "/repo", relativePath: "src/App.kt") == "file:///repo/src/App.kt")
        #expect(ProjectPaths.fileURL(worktree: "/repo", relativePath: "my file.kt") == "file:///repo/my%20file.kt")
    }
}
