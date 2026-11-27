import Foundation
import Testing
@testable import OpenCode_Mobile

/// Task, child session and shell rows speak their state from the String Catalog (#886).
struct SubagentRowAccessibilityTests {
    @Test func taskRowSpeaksTitleTagStateDetailAndTime() {
        #expect(
            buildTaskRowAccessibilityLabel(
                title: "Find float money math",
                tag: "explore",
                state: .running,
                detail: "Reading format.ts",
                detailCaption: "12 tools",
                time: "1:04"
            ) == "Find float money math, explore, running, Reading format.ts, 12 tools, 1:04"
        )
        #expect(
            buildTaskRowAccessibilityLabel(title: "Audit", tag: nil, state: .waitingForInput, detail: " ", detailCaption: nil, time: nil)
                == "Audit, waiting for input"
        )
    }

    @Test func taskRowStateWords() {
        #expect(OpenCodeTaskRowState.allCases.map(\.label) == ["running", "waiting for input", "done", "failed", "stopped"])
    }

    @Test func childSessionRowStatusWords() {
        #expect(OpenCodeChildSessionRowStatus.allCases.map(\.label) == ["running", "waiting for input", "done", "failed", "stopped"])
        #expect(
            buildChildSessionRowAccessibilityLabel(
                title: "explore",
                caption: "background",
                subtitle: "Find auth bugs",
                elapsed: "1:04",
                status: OpenCodeChildSessionRowStatus.done.label
            ) == "explore, background, Find auth bugs, done, 1:04"
        )
    }

    @Test func shellRowSpeaksShellCommandDescriptionAndElapsed() {
        #expect(
            buildShellRowAccessibilityLabel(command: "npm test", description: "Run the tests", elapsed: "1:04")
                == "Shell, npm test, Run the tests, 1:04"
        )
    }
}
