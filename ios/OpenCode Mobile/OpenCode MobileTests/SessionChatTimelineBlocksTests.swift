import Foundation
import Testing
@testable import OpenCode_Mobile

struct SessionChatTimelineBlocksTests {
    @Test func build_mergesAdjacentTextParts() {
        let blocks = SessionChatTimelineBlocks.build(
            role: .assistant,
            parts: [
                .text(TextPart(text: "Hello", isStreaming: false)),
                .text(TextPart(text: "World", isStreaming: false)),
            ]
        )
        #expect(blocks == [.text("Hello\nWorld")])
    }

    @Test func build_interleavesTextAndToolCards() {
        let blocks = SessionChatTimelineBlocks.build(
            role: .assistant,
            parts: [
                .text(TextPart(text: "Before", isStreaming: false)),
                .tool(
                    ToolPart(
                        toolName: "bash",
                        status: .completed,
                        input: ["command": .string("ls")],
                        output: "a.txt"
                    )
                ),
                .text(TextPart(text: "After", isStreaming: false)),
            ]
        )
        #expect(blocks.count == 3)
        #expect(blocks[0] == .text("Before"))
        guard case .toolCard(let tool) = blocks[1] else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "Shell")
        #expect(tool.subtitle == "ls")
        #expect(tool.detailText == "a.txt")
        #expect(blocks[2] == .text("After"))
    }

    @Test func build_hidesTodowriteAlways() {
        let blocks = SessionChatTimelineBlocks.build(
            role: .assistant,
            parts: [
                .text(TextPart(text: "Visible", isStreaming: false)),
                .tool(ToolPart(toolName: "todowrite", status: .completed)),
            ]
        )
        #expect(blocks == [.text("Visible")])
    }

    @Test func build_hidesInFlightQuestion_showsCompletedQuestion() {
        let hidden = SessionChatTimelineBlocks.build(
            role: .assistant,
            parts: [
                .tool(ToolPart(toolName: "question", status: .pending)),
                .tool(ToolPart(toolName: "question", status: .running)),
            ]
        )
        #expect(hidden.isEmpty)

        let shown = SessionChatTimelineBlocks.build(
            role: .assistant,
            parts: [
                .tool(
                    ToolPart(
                        toolName: "question",
                        status: .completed,
                        input: ["prompt": .string("Which option?")]
                    )
                ),
            ]
        )
        guard case .toolCard(let tool) = shown.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "Question")
        #expect(tool.subtitle == "Which option?")
        #expect(tool.detailText == nil)
    }

    @Test func build_nonAssistantRolesAreTextOnly() {
        let blocks = SessionChatTimelineBlocks.build(
            role: .user,
            parts: [
                .text(TextPart(text: "Prompt", isStreaming: false)),
                .tool(ToolPart(toolName: "bash", status: .completed)),
            ]
        )
        #expect(blocks == [.text("Prompt")])
    }

    @Test func build_returnsEmptyWhenOnlyHiddenTools() {
        let blocks = SessionChatTimelineBlocks.build(
            role: .assistant,
            parts: [
                .tool(ToolPart(toolName: "todowrite", status: .running)),
                .tool(ToolPart(toolName: "question", status: .pending)),
            ]
        )
        #expect(blocks.isEmpty)
    }

    @Test func build_singleReadUsesSpecializedChrome() {
        let blocks = SessionChatTimelineBlocks.build(
            role: .assistant,
            parts: [
                .tool(
                    ToolPart(
                        toolName: "read",
                        status: .completed,
                        input: ["filePath": .string("src/Main.kt")]
                    )
                ),
            ]
        )
        guard case .toolCard(let tool) = blocks.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "Read")
        #expect(tool.subtitle == "src/Main.kt")
        #expect(tool.detailText == nil)
    }

    @Test func build_contextFallsBackToGenericTitleWhenInputMissing() {
        let blocks = SessionChatTimelineBlocks.build(
            role: .assistant,
            parts: [.tool(ToolPart(toolName: "glob", status: .completed))]
        )
        guard case .toolCard(let tool) = blocks.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "Called glob")
        #expect(tool.subtitle == nil)
    }

    @Test func build_runningContextPrefersToolTitleSubtitle() {
        let blocks = SessionChatTimelineBlocks.build(
            role: .assistant,
            parts: [
                .tool(
                    ToolPart(
                        toolName: "grep",
                        status: .running,
                        title: "Searching…",
                        input: ["pattern": .string("TODO")]
                    )
                ),
            ]
        )
        guard case .toolCard(let tool) = blocks.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "Grep")
        #expect(tool.subtitle == "Searching…")
    }

    @Test func build_consecutiveContextToolsBecomeToolGroup() {
        let blocks = SessionChatTimelineBlocks.build(
            role: .assistant,
            parts: [
                .tool(ToolPart(toolName: "read", status: .completed, input: ["path": .string("a.kt")])),
                .tool(ToolPart(toolName: "grep", status: .running, input: ["pattern": .string("foo")])),
                .tool(ToolPart(toolName: "glob", status: .pending, input: ["pattern": .string("*.kt")])),
            ]
        )
        guard case .toolGroup(let group) = blocks.first else {
            Issue.record("Expected tool group")
            return
        }
        #expect(group.count == 3)
        #expect(group.status == .running)
        #expect(group.title == "1 read · 1 grep · 1 glob")
    }

    @Test func build_textBreaksContextGroup() {
        let blocks = SessionChatTimelineBlocks.build(
            role: .assistant,
            parts: [
                .tool(ToolPart(toolName: "read", status: .completed, input: ["path": .string("a.kt")])),
                .text(TextPart(text: "note", isStreaming: false)),
                .tool(ToolPart(toolName: "read", status: .completed, input: ["path": .string("b.kt")])),
            ]
        )
        #expect(blocks.count == 3)
        guard case .toolCard = blocks[0] else {
            Issue.record("Expected first tool card")
            return
        }
        #expect(blocks[1] == .text("note"))
        guard case .toolCard = blocks[2] else {
            Issue.record("Expected second tool card")
            return
        }
    }

    @Test func build_nonContextToolBreaksContextGroup() {
        let blocks = SessionChatTimelineBlocks.build(
            role: .assistant,
            parts: [
                .tool(ToolPart(toolName: "read", status: .completed, input: ["path": .string("a.kt")])),
                .tool(
                    ToolPart(
                        toolName: "bash",
                        status: .completed,
                        input: ["command": .string("pwd")],
                        output: "/"
                    )
                ),
                .tool(ToolPart(toolName: "read", status: .completed, input: ["path": .string("b.kt")])),
            ]
        )
        #expect(blocks.count == 3)
        guard case .toolCard = blocks[0], case .toolCard = blocks[1], case .toolCard = blocks[2] else {
            Issue.record("Expected three tool cards")
            return
        }
    }

    @Test func build_toolGroupStatusPrefersError() {
        let blocks = SessionChatTimelineBlocks.build(
            role: .assistant,
            parts: [
                .tool(ToolPart(toolName: "read", status: .completed)),
                .tool(ToolPart(toolName: "grep", status: .error, error: "boom")),
                .tool(ToolPart(toolName: "glob", status: .running)),
            ]
        )
        guard case .toolGroup(let group) = blocks.first else {
            Issue.record("Expected tool group")
            return
        }
        #expect(group.status == .error)
        #expect(group.errorText == "boom")
    }

    @Test func build_pendingBashHasNoSheetDetail() {
        let blocks = SessionChatTimelineBlocks.build(
            role: .assistant,
            parts: [
                .tool(
                    ToolPart(
                        toolName: "bash",
                        status: .pending,
                        input: ["command": .string("echo hi")]
                    )
                ),
            ]
        )
        guard case .toolCard(let tool) = blocks.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "Shell")
        #expect(tool.subtitle == "echo hi")
        #expect(tool.detailText == nil)
    }

    @Test func build_bashErrorUsesErrorSlotAndSheetDetail() {
        let blocks = SessionChatTimelineBlocks.build(
            role: .assistant,
            parts: [
                .tool(
                    ToolPart(
                        toolName: "bash",
                        status: .error,
                        error: "exit 1",
                        input: ["command": .string("false")]
                    )
                ),
            ]
        )
        guard case .toolCard(let tool) = blocks.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "Shell")
        #expect(tool.subtitle == "false")
        #expect(tool.errorText == "exit 1")
        #expect(tool.detailText == "exit 1")
    }

    @Test func build_networkFetchAndSearchChrome() {
        let fetchBlocks = SessionChatTimelineBlocks.build(
            role: .assistant,
            parts: [
                .tool(
                    ToolPart(
                        toolName: "webfetch",
                        status: .completed,
                        input: ["url": .string("https://example.com")],
                        output: "ok"
                    )
                ),
            ]
        )
        guard case .toolCard(let fetch) = fetchBlocks.first else {
            Issue.record("Expected fetch card")
            return
        }
        #expect(fetch.title == "Fetch")
        #expect(fetch.subtitle == "https://example.com")
        #expect(fetch.detailText == "ok")

        let searchBlocks = SessionChatTimelineBlocks.build(
            role: .assistant,
            parts: [
                .tool(
                    ToolPart(
                        toolName: "websearch",
                        status: .completed,
                        input: ["query": .string("opencode mobile")],
                        output: "https://a.example\nhttps://b.example"
                    )
                ),
            ]
        )
        guard case .toolCard(let search) = searchBlocks.first else {
            Issue.record("Expected search card")
            return
        }
        #expect(search.title == "Search")
        #expect(search.subtitle == "opencode mobile")
        #expect(search.detailText == "https://a.example\nhttps://b.example")
    }

    @Test func build_runningNetworkHasNoSheetDetailEvenWithOutput() {
        let blocks = SessionChatTimelineBlocks.build(
            role: .assistant,
            parts: [
                .tool(
                    ToolPart(
                        toolName: "webfetch",
                        status: .running,
                        input: ["url": .string("https://example.com")],
                        output: "partial"
                    )
                ),
            ]
        )
        guard case .toolCard(let tool) = blocks.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "Fetch")
        #expect(tool.detailText == nil)
    }

    @Test func build_skillUsesSpecializedChrome() {
        let blocks = SessionChatTimelineBlocks.build(
            role: .assistant,
            parts: [
                .tool(
                    ToolPart(
                        toolName: "skill",
                        status: .completed,
                        input: ["name": .string("tdd")]
                    )
                ),
            ]
        )
        guard case .toolCard(let tool) = blocks.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "Skill")
        #expect(tool.subtitle == "tdd")
        #expect(tool.detailText == nil)
    }

    @Test func build_taskUsesAgentTitleDescriptionAndChildSessionId() {
        let blocks = SessionChatTimelineBlocks.build(
            role: .assistant,
            parts: [
                .tool(
                    ToolPart(
                        toolName: "task",
                        status: .completed,
                        input: [
                            "agent": .string("explore"),
                            "description": .string("Find auth bugs"),
                        ],
                        metadata: ["sessionId": .string("ses-child-1")]
                    )
                ),
            ]
        )
        guard case .toolCard(let tool) = blocks.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "explore")
        #expect(tool.subtitle == "Find auth bugs")
        #expect(tool.childSessionId == "ses-child-1")
        #expect(tool.detailText == nil)
    }

    @Test func build_taskFallsBackTitleAndOmitsNavWithoutSessionId() {
        let blocks = SessionChatTimelineBlocks.build(
            role: .assistant,
            parts: [
                .tool(
                    ToolPart(
                        toolName: "task",
                        status: .running,
                        title: "Working…",
                        input: ["prompt": .string("Investigate flaky tests")]
                    )
                ),
            ]
        )
        guard case .toolCard(let tool) = blocks.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "Task")
        #expect(tool.subtitle == "Investigate flaky tests")
        #expect(tool.childSessionId == nil)
        #expect(tool.detailText == nil)
    }

    @Test func build_taskUsesInputNameFallbackAndOmitsBareBackground() {
        let blocks = SessionChatTimelineBlocks.build(
            role: .assistant,
            parts: [
                .tool(
                    ToolPart(
                        toolName: "task",
                        status: .completed,
                        input: ["name": .string("coder")],
                        metadata: ["background": .bool(true)]
                    )
                ),
            ]
        )
        guard case .toolCard(let tool) = blocks.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "coder")
        #expect(tool.subtitle == nil)
        #expect(tool.childSessionId == nil)
    }

    @Test func build_taskAppendsBackgroundMarkerAndUsesMetadataAgent() {
        let blocks = SessionChatTimelineBlocks.build(
            role: .assistant,
            parts: [
                .tool(
                    ToolPart(
                        toolName: "task",
                        status: .completed,
                        input: ["description": .string("Long research")],
                        metadata: [
                            "agent": .string("researcher"),
                            "background": .bool(true),
                            "sessionId": .string("ses-bg"),
                        ]
                    )
                ),
            ]
        )
        guard case .toolCard(let tool) = blocks.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "researcher")
        #expect(tool.subtitle == "Long research (background)")
        #expect(tool.childSessionId == "ses-bg")
    }

    @Test func build_taskErrorKeepsSpecializedChrome() {
        let blocks = SessionChatTimelineBlocks.build(
            role: .assistant,
            parts: [
                .tool(
                    ToolPart(
                        toolName: "task",
                        status: .error,
                        error: "child failed",
                        input: [
                            "agent": .string("build"),
                            "description": .string("Ship it"),
                        ],
                        metadata: ["sessionId": .string("ses-err")]
                    )
                ),
            ]
        )
        guard case .toolCard(let tool) = blocks.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "build")
        #expect(tool.subtitle == "Ship it")
        #expect(tool.errorText == "child failed")
        #expect(tool.childSessionId == "ses-err")
    }

    @Test func build_keepsGenericChromeForLaterTickets() {
        for name in ["edit", "write", "apply_patch", "custom_mcp"] {
            let blocks = SessionChatTimelineBlocks.build(
                role: .assistant,
                parts: [.tool(ToolPart(toolName: name, status: .completed))]
            )
            guard case .toolCard(let tool) = blocks.first else {
                Issue.record("Expected tool card for \(name)")
                return
            }
            #expect(tool.title == "Called \(name)")
            #expect(tool.detailText == nil)
            #expect(tool.childSessionId == nil)
        }
    }
}
