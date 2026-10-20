import Testing
@testable import OpenCode_Mobile

struct ComposerShellCommandTests {
    @Test func leadingBangIsShellModeAndRunsTheRest() {
        #expect(ComposerShellCommand.isShellMode("!git status"))
        #expect(ComposerShellCommand.command("!git status") == "git status")
    }

    @Test func bareBangIsShellModeWithNoCommand() {
        #expect(ComposerShellCommand.isShellMode("!"))
        #expect(ComposerShellCommand.command("!") == nil)
    }

    @Test func bangFollowedOnlyByWhitespaceHasNoCommand() {
        #expect(ComposerShellCommand.isShellMode("!  \n "))
        #expect(ComposerShellCommand.command("!  \n ") == nil)
    }

    @Test func spaceAfterBangIsTrimmedFromTheCommand() {
        #expect(ComposerShellCommand.command("!  ls -la  ") == "ls -la")
    }

    @Test func leadingWhitespaceBeforeBangStaysAPrompt() {
        #expect(!ComposerShellCommand.isShellMode(" !ls"))
        #expect(!ComposerShellCommand.isShellMode("\n!ls"))
        #expect(ComposerShellCommand.command(" !ls") == nil)
    }

    @Test func bangLaterInTheTextStaysAPrompt() {
        #expect(!ComposerShellCommand.isShellMode("run !ls"))
        #expect(ComposerShellCommand.command("Hello!") == nil)
    }

    @Test func emptyDraftIsNotShellMode() {
        #expect(!ComposerShellCommand.isShellMode(""))
        #expect(ComposerShellCommand.command("") == nil)
    }

    @Test func multiLineCommandKeepsInnerNewlines() {
        #expect(ComposerShellCommand.command("!cd app\nls\n") == "cd app\nls")
    }

    @Test func doubleBangRunsTheSecondBangAsTheCommand() {
        #expect(ComposerShellCommand.command("!!") == "!")
    }

    @Test func exitShellModeDropsOnlyThePrefix() {
        #expect(ComposerShellCommand.exitShellMode("!git status") == "git status")
        #expect(ComposerShellCommand.exitShellMode("!") == "")
        #expect(ComposerShellCommand.exitShellMode("plain") == "plain")
    }
}
