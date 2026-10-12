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

    @Test func resolve_skillWithId_sendsSkillPromptNotCommand() {
        let intent = ComposerSubmitRouting.resolve(
            text: "/OpenCode check the error",
            commands: [
                ServerCommand(name: "init", source: .command),
                ServerCommand(name: "OpenCode", source: .skill, skillID: "opencode"),
            ]
        )
        #expect(intent == .skillPrompt(skillID: "opencode", text: "check the error"))
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

    @Test func insertSelection_appendsTrailingSpace() {
        #expect(ComposerSubmitRouting.insertSelection(commandName: "init") == "/init ")
    }
}
