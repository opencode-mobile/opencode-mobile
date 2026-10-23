import Foundation
import Testing
@testable import OpenCode_Mobile

struct ToolResultInspectorTests {
    @Test func gitStatusIsFilenameFirstRowsWithWorkTreeLetter() throws {
        let output = [
            "M  android/app/src/main/java/dev/opencode/mobile/MainActivity.kt",
            " M android/app/src/main/java/dev/opencode/mobile/OpenCodeMobileApplication.kt",
            "A  android/app/src/main/java/dev/opencode/mobile/domain/diagnostic/FrameTimeTracker.kt",
            "D  android/app/src/main/java/dev/opencode/mobile/ui/settings/OldScreen.kt",
            "AM android/app/src/main/java/dev/opencode/mobile/ui/diagnostic/PerformanceOverlay.kt",
            #"R  "old/Name.kt" -> "ios/OpenCode Mobile/OpenCode Mobile/DI/AppContainer.swift""#,
        ].joined(separator: "\n")

        let sheet = try fileStatus(toolName: "bash", command: "git status --short", output: output, exitCode: 0)

        #expect(sheet.eyebrow == "Shell")
        #expect(sheet.title == "6 files")
        #expect(sheet.exitCode == 0)
        #expect(sheet.command == "git status --short")
        #expect(sheet.files.map(\.letter) == ["M", "M", "A", "D", "M", "R"])
        #expect(sheet.files[4].tone == .modified)
        #expect(sheet.files.last?.name == "AppContainer.swift")
        #expect(sheet.files.last?.directory == "ios/OpenCode Mobile/OpenCode Mobile/DI")
        #expect(sheet.counts[0].label == "3 modified")
        #expect(sheet.counts[1].label == "1 added")
        #expect(sheet.counts[2].label == "1 deleted")
        #expect(ToolResultPresentation.fileStatus(sheet).copiesOriginal)
    }

    @Test func completedShellDoesNotInventExitZero() throws {
        let sheet = try unrecognized(toolName: "shell", command: "echo hi", output: "hello")
        #expect(sheet.eyebrow == nil)
        #expect(sheet.raw == "hello")
    }

    @Test func findIsAPathListUnderShell() throws {
        let output = [
            "/Users/ivan/.gradle/caches/8.11/transforms/a91/AndroidView.kt",
            "/Users/ivan/.gradle/caches/8.11/transforms/a91/AndroidViewBinding.kt",
        ].joined(separator: "\n")
        let sheet = try pathList(
            toolName: "shell",
            command: "find ~/.gradle/caches -name 'AndroidView*'",
            output: output
        )
        #expect(sheet.eyebrow == "Shell")
        #expect(sheet.title == "2 paths")
        #expect(sheet.paths[0].name == "AndroidView.kt")
        #expect(sheet.paths[0].directory == "/Users/ivan/.gradle/caches/8.11/transforms/a91")
    }

    @Test func globUsesGlobEyebrow() throws {
        let sheet = try pathList(toolName: "glob", pattern: "*.kt", output: "src/Main.kt\nsrc/App.kt")
        #expect(sheet.eyebrow == "Glob")
        #expect(sheet.command == "*.kt")
        #expect(sheet.paths.count == 2)
    }

    @Test func pullRequestUsesNumberAndLinkNotASessionTitle() throws {
        let output = [
            "To github.com:opencode-mobile/opencode-mobile.git",
            " * [new branch]      HEAD -> feature/789-performance-overlay",
            "branch 'feature/789-performance-overlay' set up to track 'origin/feature/789-performance-overlay'.",
            "https://github.com/opencode-mobile/opencode-mobile/pull/812",
        ].joined(separator: "\n")
        let sheet = try pullRequest(
            toolName: "bash",
            command: "git push -u origin HEAD && gh pr create --fill",
            output: output,
            exitCode: 0
        )
        #expect(sheet.number == 812)
        #expect(sheet.url == "https://github.com/opencode-mobile/opencode-mobile/pull/812")
        #expect(sheet.branch == "feature/789-performance-overlay")
        #expect(sheet.pullTitle == nil)
        #expect(sheet.title == "Pull request opened")
    }

    @Test func thoughtCallsOutTheFirstSentenceAndKeepsTheRest() throws {
        let text =
            "Sample from the frame callback, not a timer. A timer lies while the main thread is stalled.\n\n"
            + "Keep a fixed ring of 120."
        let sheet = try #require(ToolResultInspector.thought(text: text, durationLabel: "8.7s"))
        #expect(sheet.title == "Thought")
        #expect(sheet.durationLabel == "8.7s")
        #expect(sheet.callout == "Sample from the frame callback, not a timer.")
        #expect(sheet.paragraphs == [
            "A timer lies while the main thread is stalled.",
            "Keep a fixed ring of 120.",
        ])
    }

    @Test func thoughtOneSentenceIsTheCalloutAlone() throws {
        let sheet = try #require(ToolResultInspector.thought(text: "Push went through. Nothing to reconsider.", durationLabel: nil))
        #expect(sheet.callout == "Push went through.")
        #expect(sheet.paragraphs == ["Nothing to reconsider."])
        #expect(sheet.durationLabel == nil)
    }

    @Test func readStripsNumberedOutputAndKeepsLineNumbers() throws {
        let sheet = try source(
            toolName: "read",
            path: "android/app/src/main/java/dev/opencode/mobile/domain/diagnostic/FrameTimeTracker.kt",
            output: "12: class FrameTimeTracker(\n13:     private val capacity: Int = 120,\n",
            offset: 12,
            limit: 2
        )
        #expect(sheet.eyebrow == "Read")
        #expect(sheet.title == "FrameTimeTracker.kt")
        #expect(sheet.language == "Kotlin")
        #expect(sheet.rangeLabel == "lines 12\u{2013}13")
        #expect(sheet.lines.map(\.number) == [12, 13])
        #expect(sheet.lines[0].text == "class FrameTimeTracker(")
    }

    @Test func writeUsesInputContent() throws {
        let sheet = try source(
            toolName: "write",
            path: "ui/diagnostic/PerformanceOverlay.kt",
            content: "fun overlay() = Unit\n"
        )
        #expect(sheet.eyebrow == "Write")
        #expect(sheet.filename == "PerformanceOverlay.kt")
        #expect(sheet.lines.count == 1)
        #expect(sheet.lines[0].text == "fun overlay() = Unit")
    }

    @Test func grepGroupsColonLinesAndFallsBackWhenItCannot() throws {
        let grouped = try grep(
            toolName: "grep",
            pattern: "Choreographer",
            searchPath: "android/",
            path: "android/",
            output: [
                "android/ui/FrameTimeTracker.kt:24:choreographer.postFrameCallback(this)",
                "android/ui/FrameTimeTracker.kt:41:// Choreographer, not a timer",
                "android/ui/PerformanceOverlay.kt:18:Choreographer.getInstance()",
            ].joined(separator: "\n")
        )
        #expect(grouped.title == "Choreographer")
        #expect(grouped.matchCount == 3)
        #expect(grouped.groups.count == 2)
        #expect(grouped.groups[0].name == "FrameTimeTracker.kt")

        let broken = try classify(
            toolName: "grep",
            fallbackTitle: "Grep",
            pattern: "x",
            output: "not a match listing"
        )
        guard case let .unrecognized(sheet) = broken else {
            Issue.record("Expected unrecognized")
            return
        }
        #expect(sheet.title == "Grep")
    }

    @Test func editBuildsAPatchFromOldAndNewWhenMetadataHasNone() throws {
        let sheet = try edit(
            toolName: "edit",
            path: "ui/diagnostic/PerformanceOverlay.kt",
            oldString: "Text(\"fps\")",
            newString: "Text(p95)"
        )
        #expect(sheet.eyebrow == "Edit")
        #expect(sheet.filename == "PerformanceOverlay.kt")
        #expect(sheet.additions == 1)
        #expect(sheet.deletions == 1)
        #expect(sheet.patch.contains("@@"))
        #expect(sheet.patch.contains("-Text(\"fps\")"))
        #expect(sheet.patch.contains("+Text(p95)"))
    }

    @Test func unrecognizedKeepsTheToolTitleAndDoesNotDropTheError() throws {
        let sheet = try unrecognized(
            toolName: "webfetch",
            fallbackTitle: "Fetch",
            output: "a long page",
            error: "timed out"
        )
        #expect(sheet.title == "Fetch")
        #expect(sheet.eyebrow == nil)
        #expect(sheet.error == "timed out")
        #expect(sheet.raw == "a long page")
        #expect(ToolResultPresentation.unrecognized(sheet).copiesOriginal)
    }

    @Test func nothingToShowIsNil() {
        #expect(
            ToolResultInspector.classify(
                ToolResultRequest(toolName: "read", fallbackTitle: "Read", path: "A.kt")
            ) == nil
        )
        #expect(ToolResultInspector.thought(text: "  ", durationLabel: "1s") == nil)
    }

    private func classify(
        toolName: String,
        fallbackTitle: String? = nil,
        command: String? = nil,
        path: String? = nil,
        pattern: String? = nil,
        searchPath: String? = nil,
        offset: Int? = nil,
        limit: Int? = nil,
        content: String? = nil,
        output: String? = nil,
        error: String? = nil,
        exitCode: Int? = nil,
        oldString: String? = nil,
        newString: String? = nil
    ) throws -> ToolResultPresentation {
        try #require(
            ToolResultInspector.classify(
                ToolResultRequest(
                    toolName: toolName,
                    fallbackTitle: fallbackTitle ?? toolName,
                    command: command,
                    path: path,
                    pattern: pattern,
                    searchPath: searchPath,
                    offset: offset,
                    limit: limit,
                    content: content,
                    output: output,
                    error: error,
                    exitCode: exitCode,
                    oldString: oldString,
                    newString: newString
                )
            )
        )
    }

    private func fileStatus(
        toolName: String,
        command: String? = nil,
        output: String? = nil,
        exitCode: Int? = nil
    ) throws -> ToolResultPresentation.FileStatus {
        let sheet = try classify(toolName: toolName, command: command, output: output, exitCode: exitCode)
        guard case let .fileStatus(value) = sheet else {
            Issue.record("Expected file status")
            return ToolResultPresentation.FileStatus(files: [], counts: [], raw: "")
        }
        return value
    }

    private func pathList(
        toolName: String,
        command: String? = nil,
        pattern: String? = nil,
        output: String? = nil
    ) throws -> ToolResultPresentation.PathList {
        let sheet = try classify(toolName: toolName, command: command, pattern: pattern, output: output)
        guard case let .pathList(value) = sheet else {
            Issue.record("Expected path list")
            return ToolResultPresentation.PathList(eyebrow: "", paths: [], raw: "")
        }
        return value
    }

    private func pullRequest(
        toolName: String,
        command: String? = nil,
        output: String? = nil,
        exitCode: Int? = nil
    ) throws -> ToolResultPresentation.PullRequest {
        let sheet = try classify(toolName: toolName, command: command, output: output, exitCode: exitCode)
        guard case let .pullRequest(value) = sheet else {
            Issue.record("Expected pull request")
            return ToolResultPresentation.PullRequest(number: 0, url: "", raw: "")
        }
        return value
    }

    private func source(
        toolName: String,
        path: String? = nil,
        output: String? = nil,
        content: String? = nil,
        offset: Int? = nil,
        limit: Int? = nil
    ) throws -> ToolResultPresentation.Source {
        let sheet = try classify(
            toolName: toolName,
            path: path,
            offset: offset,
            limit: limit,
            content: content,
            output: output
        )
        guard case let .source(value) = sheet else {
            Issue.record("Expected source")
            return ToolResultPresentation.Source(kind: .read, filename: "", lines: [], raw: "")
        }
        return value
    }

    private func grep(
        toolName: String,
        pattern: String? = nil,
        searchPath: String? = nil,
        path: String? = nil,
        output: String? = nil
    ) throws -> ToolResultPresentation.Grep {
        let sheet = try classify(
            toolName: toolName,
            path: path,
            pattern: pattern,
            searchPath: searchPath,
            output: output
        )
        guard case let .grep(value) = sheet else {
            Issue.record("Expected grep")
            return ToolResultPresentation.Grep(pattern: "", matchCount: 0, groups: [], raw: "")
        }
        return value
    }

    private func edit(
        toolName: String,
        path: String? = nil,
        oldString: String? = nil,
        newString: String? = nil
    ) throws -> ToolResultPresentation.Edit {
        let sheet = try classify(toolName: toolName, path: path, oldString: oldString, newString: newString)
        guard case let .edit(value) = sheet else {
            Issue.record("Expected edit")
            return ToolResultPresentation.Edit(eyebrow: "", filename: "", additions: 0, deletions: 0, patch: "", raw: "")
        }
        return value
    }

    private func unrecognized(
        toolName: String,
        fallbackTitle: String? = nil,
        command: String? = nil,
        output: String? = nil,
        error: String? = nil
    ) throws -> ToolResultPresentation.Unrecognized {
        let sheet = try classify(
            toolName: toolName,
            fallbackTitle: fallbackTitle,
            command: command,
            output: output,
            error: error
        )
        guard case let .unrecognized(value) = sheet else {
            Issue.record("Expected unrecognized")
            return ToolResultPresentation.Unrecognized(title: "", raw: "")
        }
        return value
    }
}
