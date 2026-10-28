import Foundation
import Testing
@testable import OpenCode_Mobile

struct ComposerSubmitRoutingTests {
    private let catalog: Set<String> = ["init", "review", "deploy"]

    @Test func resolve_matchingCommandWithArgs_returnsServerCommand() {
        let intent = ComposerSubmitRouting.resolve(text: "/init hello world", serverCommandNames: catalog)
        #expect(intent == .serverCommand(command: "init", arguments: "hello world"))
    }

    @Test func resolve_matchingCommandBare_returnsEmptyArguments() {
        let intent = ComposerSubmitRouting.resolve(text: "/review", serverCommandNames: catalog)
        #expect(intent == .serverCommand(command: "review", arguments: ""))
    }

    @Test func resolve_skillWithId_staysOnPromptRouteWithTextIntact() {
        let commands = [
            ServerCommand(name: "init", source: .command),
            ServerCommand(name: "OpenCode", source: .skill, skillID: "opencode"),
        ]
        let intent = ComposerSubmitRouting.resolve(text: "/OpenCode check the error", commands: commands)
        #expect(intent == .prompt(text: "/OpenCode check the error"))
        #expect(ComposerSubmitRouting.leadingSkill(text: "/OpenCode check the error", commands: commands)?.skillID == "opencode")
        #expect(ComposerSubmitRouting.leadingSkill(text: "check /OpenCode", commands: commands) == nil)
        #expect(ComposerSubmitRouting.leadingSkill(text: "/init now", commands: commands) == nil)
    }

    @Test func slashSuggestions_commandsOnlyLeadingSkillsAnywhereNoPlacedSkill() {
        let commands = [
            ServerCommand(name: "init", source: .command),
            ServerCommand(name: "tdd", source: .skill, skillID: "tdd"),
            ServerCommand(name: "security", source: .skill, skillID: "security"),
        ]
        let leading = ComposerSubmitRouting.slashSuggestions(commands, query: "", isLeading: true, skillMentions: [])
        #expect(leading.map(\.name) == ["init", "tdd", "security"])
        let placed = [SkillMention(id: "s1", skillID: "tdd", name: "tdd", start: 0, end: 4)]
        let inline = ComposerSubmitRouting.slashSuggestions(commands, query: "", isLeading: false, skillMentions: placed)
        #expect(inline.map(\.name) == ["security"])
    }

    @Test func resolve_skillWithoutId_staysOnCommandRoute() {
        let intent = ComposerSubmitRouting.resolve(
            text: "/deploy now",
            commands: [ServerCommand(name: "deploy", source: .skill)]
        )
        #expect(intent == .serverCommand(command: "deploy", arguments: "now"))
    }

    @Test func resolve_unknownSlash_fallsThroughToPrompt() {
        let intent = ComposerSubmitRouting.resolve(text: "/not-a-command please", serverCommandNames: catalog)
        #expect(intent == .prompt(text: "/not-a-command please"))
    }

    @Test func resolve_disabledSlash_isIgnored() {
        let intent = ComposerSubmitRouting.resolve(
            text: "/not-a-command please",
            serverCommandNames: catalog,
            allowsSlashCommands: false
        )
        #expect(intent == .ignored)
    }

    @Test func resolve_disabledSlash_plainTextStillPrompts() {
        let intent = ComposerSubmitRouting.resolve(
            text: "email a@b.com",
            serverCommandNames: catalog,
            allowsSlashCommands: false
        )
        #expect(intent == .prompt(text: "email a@b.com"))
    }

    @Test func resolve_normalText_isPrompt() {
        let intent = ComposerSubmitRouting.resolve(text: "hello", serverCommandNames: catalog)
        #expect(intent == .prompt(text: "hello"))
    }

    @Test func slashQuery_bareSlash_returnsEmptyFilter() {
        #expect(ComposerSubmitRouting.slashSuggestionQuery("/") == "")
    }

    @Test func slashQuery_partialToken_returnsFilter() {
        #expect(ComposerSubmitRouting.slashSuggestionQuery("/in") == "in")
    }

    @Test func slashQuery_afterSpace_isClosed() {
        #expect(ComposerSubmitRouting.slashSuggestionQuery("/init ") == nil)
        #expect(ComposerSubmitRouting.slashSuggestionQuery("/init args") == nil)
    }

    @Test func slashQuery_nonSlash_isClosed() {
        #expect(ComposerSubmitRouting.slashSuggestionQuery("hello") == nil)
        #expect(ComposerSubmitRouting.slashSuggestionQuery("") == nil)
    }

    @Test func filterCommands_matchesNameAndDescription() {
        let commands = [
            ServerCommand(name: "init", description: "Create AGENTS.md", source: .command),
            ServerCommand(name: "review", description: "Review changes", source: .command),
            ServerCommand(name: "deploy", description: nil, source: .skill),
        ]
        #expect(ComposerSubmitRouting.filterCommands(commands, query: "ag").map(\.name) == ["init"])
        #expect(ComposerSubmitRouting.filterCommands(commands, query: "dep").map(\.name) == ["deploy"])
    }

    @Test func slashQuery_midTextAfterWhitespace_opensAtCaret() {
        #expect(ComposerMentionRouting.slashSuggestionQuery("fix the bug /td", cursor: 15) == "td")
        #expect(ComposerMentionRouting.slashSuggestionQuery("fix a/b", cursor: 7) == nil)
        #expect(ComposerMentionRouting.slashSuggestionQuery("fix /tdd now", cursor: 8) == "tdd")
        #expect(ComposerMentionRouting.slashQueryIsLeading("/td", cursor: 3))
        #expect(!ComposerMentionRouting.slashQueryIsLeading("fix /td", cursor: 7))
    }
}
