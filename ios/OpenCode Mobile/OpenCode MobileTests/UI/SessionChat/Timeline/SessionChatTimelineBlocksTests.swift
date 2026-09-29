import Foundation
import Testing
@testable import OpenCode_Mobile

struct PresentationBlockIdTests {
    @Test func direct_prefixesPartId() {
        #expect(PresentationBlockId.direct("prt-1") == "p:prt-1")
    }

    @Test func mergedText_singlePartUsesDirect() {
        #expect(PresentationBlockId.mergedText(["a"]) == "p:a")
    }

    @Test func mergedText_joinsOrderedIdsWithUnitSeparator() {
        #expect(PresentationBlockId.mergedText(["a", "b", "c"]) == "t:a\u{001F}b\u{001F}c")
    }

    @Test func toolGroup_joinsOrderedMemberIds() {
        #expect(PresentationBlockId.toolGroup(["r1", "g1"]) == "g:r1\u{001F}g1")
    }

    @Test func messageFallback_prefixesMessageId() {
        #expect(PresentationBlockId.messageFallback("msg-1") == "m:msg-1")
    }
}

struct SessionChatTimelineBlocksTests {
    @Test func build_mergesAdjacentTextParts() {
        let blocks = okBlocks(
            parts: [
                text("t1", "Hello"),
                text("t2", "World"),
            ]
        )
        #expect(blocks == [
            .text(
                blockID: PresentationBlockId.mergedText(["t1", "t2"]),
                text: "Hello\nWorld",
                renderedMarkdown: nil
            ),
        ])
    }

    @Test func ensureLiveRow_keepsEmptyStreamingAssistant() {
        let message = Message(
            id: "msg-2",
            role: .assistant,
            text: "",
            parts: [.text(TextPart(id: "prt-1", text: "", isStreaming: true))],
            model: nil,
            mode: nil,
            sentAtEpochMs: nil
        )
        let blocks = SessionChatTimelineBlocks.ensureLiveRow(
            message: message,
            isStreaming: true,
            blocks: []
        )
        #expect(blocks.count == 1)
        guard case .text(let blockID, let text, _) = blocks[0] else {
            Issue.record("expected text block")
            return
        }
        #expect(blockID == PresentationBlockId.messageFallback("msg-2"))
        #expect(text.isEmpty)
    }

    @Test func ensureLiveRow_omitsHiddenToolOnlyAssistant() {
        let message = Message(
            id: "msg-2",
            role: .assistant,
            text: "",
            parts: [.tool(ToolPart(id: "prt-1", toolName: "todowrite", status: .running))],
            model: nil,
            mode: nil,
            sentAtEpochMs: nil
        )
        let blocks = SessionChatTimelineBlocks.ensureLiveRow(
            message: message,
            isStreaming: false,
            blocks: []
        )
        #expect(blocks.isEmpty)
    }

    @Test func ensureLiveRow_omitsEmptyTextAfterStreamEnds() {
        let message = Message(
            id: "msg-2",
            role: .assistant,
            text: "",
            parts: [
                .text(TextPart(id: "prt-1", text: "", isStreaming: false)),
                .tool(ToolPart(id: "prt-2", toolName: "todowrite", status: .completed)),
            ],
            model: nil,
            mode: nil,
            sentAtEpochMs: nil
        )
        let blocks = SessionChatTimelineBlocks.ensureLiveRow(
            message: message,
            isStreaming: false,
            blocks: []
        )
        #expect(blocks.isEmpty)
    }

    @Test func thoughtDurationLabel_matchesTuiBuckets() {
        #expect(SessionChatTimelineBlocks.thoughtDurationLabel(438) == "438ms")
        #expect(SessionChatTimelineBlocks.thoughtDurationLabel(6400) == "6.4s")
        #expect(SessionChatTimelineBlocks.thoughtDurationLabel(127_000) == "2m 7s")
        #expect(SessionChatTimelineBlocks.thoughtDurationLabel(nil) == nil)
    }

    @Test func build_hidesBlankReasoning() {
        let blankCompleted = okBlocks(
            parts: [
                .reasoning(
                    ReasoningPart(
                        id: "r1",
                        text: "",
                        isStreaming: false,
                        timeStartEpochMs: 1,
                        timeEndEpochMs: 2
                    )
                ),
            ]
        )
        #expect(blankCompleted.isEmpty)

        let blankStreaming = okBlocks(
            parts: [.reasoning(ReasoningPart(id: "r2", text: "", isStreaming: true))]
        )
        #expect(blankStreaming.isEmpty)

        let redactedOnly = okBlocks(
            parts: [.reasoning(ReasoningPart(id: "r3", text: "[REDACTED]", isStreaming: false))]
        )
        #expect(redactedOnly.isEmpty)
    }

    @Test func build_hidesStackedEmptyReasoningBeforeTool() {
        var parts: [MessagePart] = (1 ... 12).map { index in
            .reasoning(
                ReasoningPart(
                    id: "r\(index)",
                    text: "",
                    isStreaming: false,
                    timeStartEpochMs: Int64(index),
                    timeEndEpochMs: Int64(index + 1)
                )
            )
        }
        parts.append(.tool(ToolPart(id: "shell-1", toolName: "bash", status: .completed)))
        let blocks = okBlocks(parts: parts)
        #expect(blocks.count == 1)
        guard case .toolCard(_, let tool) = blocks[0] else {
            Issue.record("expected tool card")
            return
        }
        #expect(tool.toolName == "bash")
    }

    @Test func streamingReasoning_isNotEmittedAsBlock() {
        let streaming = okBlocks(
            parts: [.reasoning(ReasoningPart(id: "r1", text: "Need to greet the user.", isStreaming: true))]
        )
        #expect(streaming.isEmpty)

        let settled = okBlocks(
            parts: [.reasoning(ReasoningPart(id: "r1", text: "Need to greet the user.", isStreaming: false))]
        )
        #expect(settled.count == 1)
        guard case .reasoning(_, let text, let isStreaming, _) = settled.first else {
            Issue.record("expected settled reasoning")
            return
        }
        #expect(text == "Need to greet the user.")
        #expect(isStreaming == false)
    }

    @Test func streamingReasoningOnlyMessage_fallsBackToLiveRow() {
        let message = Message(
            id: "msg-thought",
            role: .assistant,
            text: "",
            parts: [.reasoning(ReasoningPart(id: "r1", text: "Need to greet the user.", isStreaming: true))],
            model: nil,
            mode: nil,
            sentAtEpochMs: nil
        )
        let built = SessionChatTimelineBlocks.buildOrFallback(
            role: message.role,
            parts: message.parts,
            messageId: message.id,
            fallbackText: message.text
        )
        let blocks = SessionChatTimelineBlocks.ensureLiveRow(
            message: message,
            isStreaming: true,
            blocks: built
        )
        #expect(blocks.count == 1)
        guard case .text(let blockID, let text, _) = blocks[0] else {
            Issue.record("expected live-row text block")
            return
        }
        #expect(blockID == PresentationBlockId.messageFallback(message.id))
        #expect(text == message.text)
    }

    @Test func paintsInTimeline_falseOnlyForLiveThought() {
        let streaming = SessionChatTimelineBlock.reasoning(
            blockID: "r1",
            text: "thinking",
            isStreaming: true,
            durationMs: nil
        )
        #expect(!streaming.paintsInTimeline)

        let settled = SessionChatTimelineBlock.reasoning(
            blockID: "r2",
            text: "done",
            isStreaming: false,
            durationMs: 100
        )
        #expect(settled.paintsInTimeline)

        let text = SessionChatTimelineBlock.text(
            blockID: "t1",
            text: "Hello",
            renderedMarkdown: nil
        )
        #expect(text.paintsInTimeline)
    }

    @Test func paintedBlocks_streamingReasoningOnly_fallsBackToPlaceholder() {
        let message = SessionChatRow(
            id: "msg-1",
            role: .assistant,
            text: "",
            metadataLabel: "",
            blocks: [
                .reasoning(blockID: "r1", text: "thinking", isStreaming: true, durationMs: nil),
            ]
        )
        let painted = SessionChatTimelineBlocks.paintedBlocks(for: message)
        #expect(painted.count == 1)
        guard case .text(let blockID, _, _) = painted[0] else {
            Issue.record("expected fallback text")
            return
        }
        #expect(blockID == PresentationBlockId.messageFallback("msg-1"))
    }

    @Test func paintedBlocks_textPlusStreamingReasoning_keepsOnlyText() {
        let textBlock = SessionChatTimelineBlock.text(
            blockID: "t1",
            text: "Hello",
            renderedMarkdown: nil
        )
        let message = SessionChatRow(
            id: "msg-2",
            role: .assistant,
            text: "Hello",
            metadataLabel: "",
            blocks: [
                textBlock,
                .reasoning(blockID: "r1", text: "thinking", isStreaming: true, durationMs: nil),
            ]
        )
        #expect(SessionChatTimelineBlocks.paintedBlocks(for: message) == [textBlock])
    }

    @Test func build_reasoningPart_includesDuration() {
        let blocks = okBlocks(
            parts: [
                .reasoning(
                    ReasoningPart(
                        id: "r1",
                        text: "Need to greet the user.",
                        timeStartEpochMs: 1_000,
                        timeEndEpochMs: 7_400
                    )
                ),
            ]
        )
        guard case .reasoning(_, let text, let isStreaming, let durationMs) = blocks.first else {
            Issue.record("expected reasoning")
            return
        }
        #expect(text == "Need to greet the user.")
        #expect(isStreaming == false)
        #expect(durationMs == 6_400)
    }

    @Test func build_interleavesTextAndToolCards() {
        let blocks = okBlocks(
            parts: [
                text("t1", "Before"),
                .tool(
                    ToolPart(
                        id: "bash-1",
                        toolName: "bash",
                        status: .completed,
                        input: ["command": .string("ls")],
                        output: "a.txt"
                    )
                ),
                text("t2", "After"),
            ]
        )
        #expect(blocks.count == 3)
        #expect(blocks[0] == .text(blockID: "p:t1", text: "Before", renderedMarkdown: nil))
        #expect(blocks[0].blockID == PresentationBlockId.direct("t1"))
        guard case .toolCard(let blockID, let tool) = blocks[1] else {
            Issue.record("Expected tool card")
            return
        }
        #expect(blockID == PresentationBlockId.direct("bash-1"))
        #expect(tool.title == "Shell")
        #expect(tool.subtitle == "ls")
        #expect(tool.detailText == "a.txt")
        #expect(blocks[2] == .text(blockID: "p:t2", text: "After", renderedMarkdown: nil))
    }

    @Test func build_hidesTodowriteAlways() {
        let blocks = okBlocks(
            parts: [
                text("t1", "Visible"),
                .tool(ToolPart(id: "todo-1", toolName: "todowrite", status: .completed)),
            ]
        )
        #expect(blocks == [
            .text(blockID: "p:t1", text: "Visible", renderedMarkdown: nil),
        ])
    }

    @Test func build_hidesInFlightQuestion_showsCompletedQuestion() {
        let hidden = okBlocks(
            parts: [
                .tool(ToolPart(id: "q1", toolName: "question", status: .pending)),
                .tool(ToolPart(id: "q2", toolName: "question", status: .running)),
            ]
        )
        #expect(hidden.isEmpty)

        let shown = okBlocks(
            parts: [
                .tool(
                    ToolPart(
                        id: "q3",
                        toolName: "question",
                        status: .completed,
                        input: ["prompt": .string("Which option?")]
                    )
                ),
            ]
        )
        guard case .toolCard(let blockID, let tool) = shown.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(blockID == PresentationBlockId.direct("q3"))
        #expect(tool.title == "Asked 1 question")
        #expect(tool.subtitle == nil)
        #expect(tool.detailText == "Which option?")
    }

    @Test func build_dismissedQuestionIsTappable() {
        let shown = okBlocks(
            parts: [
                .tool(
                    ToolPart(
                        id: "q-dismissed",
                        toolName: "question",
                        status: .error,
                        error: "The user dismissed this question",
                        input: [
                            "questions": .array([
                                .dictionary(["question": .string("What would you like to do in this session?")]),
                            ]),
                        ]
                    )
                ),
            ]
        )
        guard case .toolCard(_, let tool) = shown.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "Asked 1 question")
        #expect(tool.errorText == "The user dismissed this question")
        #expect(
            tool.detailText ==
                "What would you like to do in this session?\n\nThe user dismissed this question"
        )
    }

    @Test func build_answeredQuestionShowsQuestionAndReply() {
        let shown = okBlocks(
            parts: [
                .tool(
                    ToolPart(
                        id: "q-answered",
                        toolName: "question",
                        status: .completed,
                        input: [
                            "questions": .array([
                                .dictionary(["question": .string("What should we do with these two projects this session?")]),
                            ]),
                        ],
                        metadata: [
                            "answers": .array([
                                .array([.string("Build a third sibling")]),
                            ]),
                        ]
                    )
                ),
            ]
        )
        guard case .toolCard(_, let tool) = shown.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "Asked 1 question")
        #expect(tool.subtitle == "Build a third sibling")
        #expect(
            tool.detailText ==
                "What should we do with these two projects this session?\nBuild a third sibling"
        )
    }

    @Test func build_questionToolUsesAskedCountTitleEvenWhenNameIsPlaceholder() {
        let questions: AnyCodable = .array([
            .dictionary(["question": .string("Q0")]),
            .dictionary(["question": .string("Q1")]),
            .dictionary(["question": .string("Q2")]),
        ])
        let hidden = okBlocks(
            parts: [
                .tool(
                    ToolPart(
                        id: "prt-q",
                        toolName: "tool",
                        status: .running,
                        input: ["questions": questions]
                    )
                ),
            ]
        )
        #expect(hidden.isEmpty)

        let shown = okBlocks(
            parts: [
                .tool(
                    ToolPart(
                        id: "prt-q",
                        toolName: "tool",
                        status: .completed,
                        input: ["questions": questions]
                    )
                ),
            ]
        )
        guard case .toolCard(_, let tool) = shown.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "Asked 3 questions")
    }

    @Test func build_hidesPlaceholderToolNameWithoutQuestions() {
        let blocks = okBlocks(
            parts: [
                .tool(
                    ToolPart(
                        id: "prt-unknown",
                        toolName: "tool",
                        status: .running
                    )
                ),
            ]
        )
        #expect(blocks.isEmpty)
    }

    @Test func build_nonAssistantRolesAreTextOnly() {
        let blocks = okBlocks(
            role: .user,
            parts: [
                text("t1", "Prompt"),
                .tool(ToolPart(id: "bash-1", toolName: "bash", status: .completed)),
            ]
        )
        #expect(blocks == [
            .text(blockID: "p:t1", text: "Prompt", renderedMarkdown: nil),
        ])
    }

    @Test func build_returnsEmptyWhenOnlyHiddenTools() {
        let blocks = okBlocks(
            parts: [
                .tool(ToolPart(id: "todo-1", toolName: "todowrite", status: .running)),
                .tool(ToolPart(id: "q1", toolName: "question", status: .pending)),
            ]
        )
        #expect(blocks.isEmpty)
    }

    @Test func build_singleReadUsesSpecializedChrome() {
        let blocks = okBlocks(
            parts: [
                .tool(
                    ToolPart(
                        id: "read-1",
                        toolName: "read",
                        status: .completed,
                        input: ["filePath": .string("src/Main.kt")]
                    )
                ),
            ]
        )
        guard case .toolCard(let blockID, let tool) = blocks.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(blockID == PresentationBlockId.direct("read-1"))
        #expect(tool.title == "Explored — 1 read")
        #expect(tool.subtitle == "src/Main.kt")
        #expect(tool.detailText == nil)
    }

    @Test func build_readDoesNotUseDirectoryPathAsSubtitle() {
        let blocks = okBlocks(
            parts: [
                .tool(
                    ToolPart(
                        id: "read-1",
                        toolName: "read",
                        status: .completed,
                        input: ["path": .string("/home/ivan/Projects/session-test")]
                    )
                ),
            ]
        )
        guard case .toolCard(_, let tool) = blocks.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "Explored — 1 read")
        #expect(tool.subtitle == nil)
    }

    @Test func build_contextFallsBackToGenericTitleWhenInputMissing() {
        let blocks = okBlocks(
            parts: [.tool(ToolPart(id: "glob-1", toolName: "glob", status: .completed))]
        )
        guard case .toolCard(_, let tool) = blocks.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "Explored — 1 glob")
        #expect(tool.subtitle == nil)
    }

    @Test func build_runningContextPrefersToolTitleSubtitle() {
        let blocks = okBlocks(
            parts: [
                .tool(
                    ToolPart(
                        id: "grep-1",
                        toolName: "grep",
                        status: .running,
                        title: "Searching…",
                        input: ["pattern": .string("TODO")]
                    )
                ),
            ]
        )
        guard case .toolCard(_, let tool) = blocks.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "Explored — 1 grep")
        #expect(tool.subtitle == "Searching…")
    }

    @Test func build_consecutiveContextToolsBecomeToolGroup() {
        let blocks = okBlocks(
            parts: [
                .tool(ToolPart(id: "r1", toolName: "read", status: .completed, input: ["filePath": .string("a.kt")])),
                .tool(ToolPart(id: "g1", toolName: "grep", status: .running, input: ["pattern": .string("foo")])),
                .tool(ToolPart(id: "l1", toolName: "glob", status: .pending, input: ["pattern": .string("*.kt")])),
            ]
        )
        guard case .toolGroup(let blockID, let group) = blocks.first else {
            Issue.record("Expected tool group")
            return
        }
        #expect(blockID == PresentationBlockId.toolGroup(["r1", "g1", "l1"]))
        #expect(group.count == 3)
        #expect(group.status == .running)
        #expect(group.title == "Explored — 1 read · 1 grep · 1 glob")
        #expect(group.subtitle == "a.kt")
    }

    @Test func build_consecutiveWebsearchesUseExploredSearchChrome() {
        let blocks = okBlocks(
            parts: [
                .tool(ToolPart(id: "s1", toolName: "websearch", status: .completed, input: ["query": .string("one")])),
                .tool(ToolPart(id: "s2", toolName: "websearch", status: .completed, input: ["query": .string("two")])),
            ]
        )
        guard case .toolGroup(_, let group) = blocks.first else {
            Issue.record("Expected tool group")
            return
        }
        #expect(group.title == "Explored — 2 searches")
        #expect(group.subtitle == "one")
    }

    @Test func build_textBreaksContextGroup() {
        let blocks = okBlocks(
            parts: [
                .tool(ToolPart(id: "r1", toolName: "read", status: .completed, input: ["path": .string("a.kt")])),
                text("note-1", "note"),
                .tool(ToolPart(id: "r2", toolName: "read", status: .completed, input: ["path": .string("b.kt")])),
            ]
        )
        #expect(blocks.count == 3)
        guard case .toolCard(let firstID, _) = blocks[0] else {
            Issue.record("Expected first tool card")
            return
        }
        #expect(firstID == PresentationBlockId.direct("r1"))
        #expect(blocks[1] == .text(blockID: "p:note-1", text: "note", renderedMarkdown: nil))
        guard case .toolCard(let secondID, _) = blocks[2] else {
            Issue.record("Expected second tool card")
            return
        }
        #expect(secondID == PresentationBlockId.direct("r2"))
    }

    @Test func build_nonContextToolBreaksContextGroup() {
        let blocks = okBlocks(
            parts: [
                .tool(ToolPart(id: "r1", toolName: "read", status: .completed, input: ["path": .string("a.kt")])),
                .tool(
                    ToolPart(
                        id: "bash-1",
                        toolName: "bash",
                        status: .completed,
                        input: ["command": .string("pwd")],
                        output: "/"
                    )
                ),
                .tool(ToolPart(id: "r2", toolName: "read", status: .completed, input: ["path": .string("b.kt")])),
            ]
        )
        #expect(blocks.count == 3)
        guard case .toolCard = blocks[0], case .toolCard = blocks[1], case .toolCard = blocks[2] else {
            Issue.record("Expected three tool cards")
            return
        }
        #expect(blocks.map(\.blockID) == [
            PresentationBlockId.direct("r1"),
            PresentationBlockId.direct("bash-1"),
            PresentationBlockId.direct("r2"),
        ])
    }

    @Test func build_toolGroupStatusPrefersError() {
        let blocks = okBlocks(
            parts: [
                .tool(ToolPart(id: "r1", toolName: "read", status: .completed)),
                .tool(ToolPart(id: "g1", toolName: "grep", status: .error, error: "boom")),
                .tool(ToolPart(id: "l1", toolName: "glob", status: .running)),
            ]
        )
        guard case .toolGroup(let blockID, let group) = blocks.first else {
            Issue.record("Expected tool group")
            return
        }
        #expect(blockID == PresentationBlockId.toolGroup(["r1", "g1", "l1"]))
        #expect(group.status == .error)
        #expect(group.errorText == "boom")
    }

    @Test func build_pendingBashHasNoSheetDetail() {
        let blocks = okBlocks(
            parts: [
                .tool(
                    ToolPart(
                        id: "bash-1",
                        toolName: "bash",
                        status: .pending,
                        input: ["command": .string("echo hi")]
                    )
                ),
            ]
        )
        guard case .toolCard(_, let tool) = blocks.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "Shell")
        #expect(tool.subtitle == "echo hi")
        #expect(tool.detailText == nil)
    }

    @Test func build_bashErrorUsesErrorSlotAndSheetDetail() {
        let blocks = okBlocks(
            parts: [
                .tool(
                    ToolPart(
                        id: "bash-1",
                        toolName: "bash",
                        status: .error,
                        error: "exit 1",
                        input: ["command": .string("false")]
                    )
                ),
            ]
        )
        guard case .toolCard(_, let tool) = blocks.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "Shell")
        #expect(tool.subtitle == "false")
        #expect(tool.errorText == "exit 1")
        #expect(tool.detailText == "exit 1")
    }

    @Test func build_v2ShellUsesCommandAsSubtitle() {
        let blocks = okBlocks(
            parts: [
                .tool(
                    ToolPart(
                        id: "shell-1",
                        toolName: "shell",
                        status: .running,
                        title: "shell",
                        input: ["command": .string("git status")]
                    )
                ),
            ]
        )
        guard case .toolCard(_, let tool) = blocks.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "Shell")
        #expect(tool.subtitle == "git status")
    }

    @Test func build_networkFetchAndSearchChrome() {
        let fetchBlocks = okBlocks(
            parts: [
                .tool(
                    ToolPart(
                        id: "fetch-1",
                        toolName: "webfetch",
                        status: .completed,
                        input: ["url": .string("https://example.com")],
                        output: "ok"
                    )
                ),
            ]
        )
        guard case .toolCard(_, let fetch) = fetchBlocks.first else {
            Issue.record("Expected fetch card")
            return
        }
        #expect(fetch.title == "Fetch")
        #expect(fetch.subtitle == "https://example.com")
        #expect(fetch.detailText == "ok")

        let searchBlocks = okBlocks(
            parts: [
                .tool(
                    ToolPart(
                        id: "search-1",
                        toolName: "websearch",
                        status: .completed,
                        input: ["query": .string("opencode mobile")],
                        output: "https://a.example\nhttps://b.example"
                    )
                ),
            ]
        )
        guard case .toolCard(_, let search) = searchBlocks.first else {
            Issue.record("Expected search card")
            return
        }
        #expect(search.title == "Explored — 1 search")
        #expect(search.subtitle == "opencode mobile")
        #expect(search.detailText == nil)
    }

    @Test func build_runningNetworkHasNoSheetDetailEvenWithOutput() {
        let blocks = okBlocks(
            parts: [
                .tool(
                    ToolPart(
                        id: "fetch-1",
                        toolName: "webfetch",
                        status: .running,
                        input: ["url": .string("https://example.com")],
                        output: "partial"
                    )
                ),
            ]
        )
        guard case .toolCard(_, let tool) = blocks.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "Fetch")
        #expect(tool.detailText == nil)
    }

    @Test func build_skillUsesSpecializedChrome() {
        let blocks = okBlocks(
            parts: [
                .tool(
                    ToolPart(
                        id: "skill-1",
                        toolName: "skill",
                        status: .completed,
                        input: ["name": .string("tdd")]
                    )
                ),
            ]
        )
        guard case .toolCard(_, let tool) = blocks.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "Skill")
        #expect(tool.subtitle == "tdd")
        #expect(tool.detailText == nil)
    }

    @Test func build_taskUsesAgentTitleDescriptionAndChildSessionId() {
        let blocks = okBlocks(
            parts: [
                .tool(
                    ToolPart(
                        id: "task-1",
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
        guard case .childSessionRow(_, let row) = blocks.first else {
            Issue.record("Expected child session row")
            return
        }
        #expect(row.agentName == "explore")
        #expect(row.letter == "E")
        #expect(row.secondLine == "Find auth bugs")
        #expect(row.childSessionId == "ses-child-1")
        #expect(row.isBackground == false)
        #expect(row.timeStartEpochMs == nil)
    }

    @Test func build_v2SubagentUsesChildSessionRow() {
        let blocks = okBlocks(
            parts: [
                .tool(
                    ToolPart(
                        id: "call_1",
                        toolName: "subagent",
                        status: .completed,
                        title: "subagent",
                        input: [
                            "agent": .string("explore"),
                            "description": .string("Find git projects in directories"),
                        ],
                        metadata: ["sessionID": .string("ses-child-2")]
                    )
                ),
            ]
        )
        guard case .childSessionRow(_, let row) = blocks.first else {
            Issue.record("Expected child session row")
            return
        }
        #expect(row.agentName == "explore")
        #expect(row.letter == "E")
        #expect(row.secondLine == "Find git projects in directories")
        #expect(row.childSessionId == "ses-child-2")
    }

    @Test func build_taskFallsBackTitleAndOmitsNavWithoutSessionId() {
        let blocks = okBlocks(
            parts: [
                .tool(
                    ToolPart(
                        id: "task-1",
                        toolName: "task",
                        status: .running,
                        title: "Working…",
                        input: ["prompt": .string("Investigate flaky tests")]
                    )
                ),
            ]
        )
        guard case .childSessionRow(_, let row) = blocks.first else {
            Issue.record("Expected child session row")
            return
        }
        #expect(row.agentName == "Task")
        #expect(row.letter == "T")
        #expect(row.secondLine == "Investigate flaky tests")
        #expect(row.childSessionId == nil)
        #expect(row.isBackground == false)
        #expect(row.timeStartEpochMs == nil)
    }

    @Test func build_taskStaysTaskUntilInputAgentLands() {
        let blocks = okBlocks(
            parts: [
                .tool(
                    ToolPart(
                        id: "task-1",
                        toolName: "task",
                        status: .running,
                        title: "explore"
                    )
                ),
            ]
        )
        guard case .childSessionRow(_, let row) = blocks.first else {
            Issue.record("Expected child session row")
            return
        }
        #expect(row.agentName == "Task")
    }

    @Test func build_subagentDefaultsToGeneralAndPrefersDescriptionOverCompositeTitle() {
        let blocks = okBlocks(
            parts: [
                .tool(
                    ToolPart(
                        id: "prt-1",
                        toolName: "subagent",
                        status: .running,
                        title: "General Subagent – First 7 days of October 2026",
                        input: ["description": .string("First 7 days of October 2026")]
                    )
                ),
            ]
        )
        guard case .childSessionRow(_, let row) = blocks.first else {
            Issue.record("Expected child session row")
            return
        }
        #expect(row.agentName == "General")
        #expect(row.letter == "G")
        #expect(row.secondLine == "First 7 days of October 2026")
    }

    @Test func build_subagentUsesSubagentTypeBeforeDefault() {
        let blocks = okBlocks(
            parts: [
                .tool(
                    ToolPart(
                        id: "prt-1",
                        toolName: "subagent",
                        status: .running,
                        input: ["subagent_type": .string("explore")]
                    )
                ),
            ]
        )
        guard case .childSessionRow(_, let row) = blocks.first else {
            Issue.record("Expected child session row")
            return
        }
        #expect(row.agentName == "explore")
    }

    @Test func build_pendingTaskQuietRowUsesPromptWhenTitleMissing() {
        let blocks = okBlocks(
            parts: [
                .tool(
                    ToolPart(
                        id: "task-1",
                        toolName: "task",
                        status: .pending,
                        input: [
                            "agent": .string("explore"),
                            "description": .string("Find auth bugs"),
                        ],
                        metadata: [
                            "background": .bool(true),
                            "sessionId": .string("ses-child-1"),
                        ],
                        timeStartEpochMs: 1_710_000_000_000
                    )
                ),
            ]
        )
        guard case .childSessionRow(_, let row) = blocks.first else {
            Issue.record("Expected child session row")
            return
        }
        #expect(row.agentName == "explore")
        #expect(row.letter == "E")
        #expect(row.secondLine == "Find auth bugs")
        #expect(row.isBackground == true)
        #expect(row.childSessionId == "ses-child-1")
        #expect(row.timeStartEpochMs == 1_710_000_000_000)
    }

    @Test func build_taskUsesInputNameFallbackAndOmitsBareBackground() {
        let blocks = okBlocks(
            parts: [
                .tool(
                    ToolPart(
                        id: "task-1",
                        toolName: "task",
                        status: .completed,
                        input: ["name": .string("coder")],
                        metadata: ["background": .bool(true)]
                    )
                ),
            ]
        )
        guard case .childSessionRow(_, let row) = blocks.first else {
            Issue.record("Expected child session row")
            return
        }
        #expect(row.agentName == "Task")
        #expect(row.letter == "T")
        #expect(row.secondLine == nil)
        #expect(row.isBackground == true)
        #expect(row.childSessionId == nil)
        #expect(row.timeStartEpochMs == nil)
    }

    @Test func build_taskAppendsBackgroundMarkerAndUsesMetadataAgent() {
        let blocks = okBlocks(
            parts: [
                .tool(
                    ToolPart(
                        id: "task-1",
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
        guard case .childSessionRow(_, let row) = blocks.first else {
            Issue.record("Expected child session row")
            return
        }
        #expect(row.agentName == "researcher")
        #expect(row.letter == "R")
        #expect(row.secondLine == "Long research")
        #expect(row.isBackground == true)
        #expect(row.childSessionId == "ses-bg")
        #expect(row.timeStartEpochMs == nil)
    }

    @Test func build_taskErrorKeepsSpecializedChrome() {
        let blocks = okBlocks(
            parts: [
                .tool(
                    ToolPart(
                        id: "task-1",
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
        guard case .toolCard(_, let tool) = blocks.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "build")
        #expect(tool.subtitle == "Ship it")
        #expect(tool.errorText == "child failed")
        #expect(tool.childSessionId == "ses-err")
    }

    @Test func build_editUsesFilediffPathCountsAndOpensChanges() {
        let blocks = okBlocks(
            parts: [
                .tool(
                    ToolPart(
                        id: "edit-1",
                        toolName: "edit",
                        status: .completed,
                        metadata: [
                            "filediff": .dictionary([
                                "file": .string("src/Main.kt"),
                                "additions": .int(3),
                                "deletions": .int(1),
                            ]),
                        ]
                    )
                ),
            ]
        )
        guard case .toolCard(_, let tool) = blocks.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "Edit")
        #expect(tool.subtitle == "src/Main.kt")
        #expect(tool.additions == 3)
        #expect(tool.deletions == 1)
        #expect(tool.opensChangesReview)
        #expect(tool.changesInitialFilePath == "src/Main.kt")
        #expect(tool.detailText == nil)
        #expect(tool.childSessionId == nil)
    }

    @Test func build_writeFallsBackToInputPathAndOmitsZeroCounts() {
        let blocks = okBlocks(
            parts: [
                .tool(
                    ToolPart(
                        id: "write-1",
                        toolName: "write",
                        status: .completed,
                        input: ["filePath": .string("README.md")],
                        metadata: [
                            "filediff": .dictionary([
                                "file": .string("README.md"),
                                "additions": .int(5),
                                "deletions": .int(0),
                            ]),
                        ]
                    )
                ),
            ]
        )
        guard case .toolCard(_, let tool) = blocks.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "Write")
        #expect(tool.subtitle == "README.md")
        #expect(tool.additions == 5)
        #expect(tool.deletions == nil)
        #expect(tool.opensChangesReview)
        #expect(tool.changesInitialFilePath == "README.md")
    }

    @Test func build_fileChangeWithoutPathKeepsTitleOnlyAndNoTap() {
        let blocks = okBlocks(
            parts: [.tool(ToolPart(id: "edit-1", toolName: "edit", status: .completed))]
        )
        guard case .toolCard(_, let tool) = blocks.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "Edit")
        #expect(tool.subtitle == nil)
        #expect(tool.additions == nil)
        #expect(tool.deletions == nil)
        #expect(!tool.opensChangesReview)
        #expect(tool.changesInitialFilePath == nil)
    }

    @Test func build_fileChangePendingWithoutPathIsTitleOnly() {
        let blocks = okBlocks(
            parts: [
                .tool(
                    ToolPart(
                        id: "edit-1",
                        toolName: "edit",
                        status: .running,
                        title: "Editing…"
                    )
                ),
            ]
        )
        guard case .toolCard(_, let tool) = blocks.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "Edit")
        #expect(tool.subtitle == nil)
        #expect(!tool.opensChangesReview)
    }

    @Test func build_fileChangePendingWithPathIsDisplayOnly() {
        let blocks = okBlocks(
            parts: [
                .tool(
                    ToolPart(
                        id: "edit-1",
                        toolName: "edit",
                        status: .running,
                        title: "Editing…",
                        input: ["filePath": .string("App.kt")]
                    )
                ),
            ]
        )
        guard case .toolCard(_, let tool) = blocks.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "Edit")
        #expect(tool.subtitle == "App.kt")
        #expect(!tool.opensChangesReview)
        #expect(tool.changesInitialFilePath == nil)
    }

    @Test func build_applyPatchMultiFileUsesCountSubtitleAndListTap() {
        let blocks = okBlocks(
            parts: [
                .tool(
                    ToolPart(
                        id: "patch-1",
                        toolName: "apply_patch",
                        status: .completed,
                        metadata: [
                            "files": .array([
                                .dictionary([
                                    "filePath": .string("a.kt"),
                                    "additions": .int(2),
                                    "deletions": .int(1),
                                ]),
                                .dictionary([
                                    "filePath": .string("b.kt"),
                                    "additions": .int(4),
                                    "deletions": .int(0),
                                ]),
                            ]),
                        ]
                    )
                ),
            ]
        )
        guard case .toolCard(_, let tool) = blocks.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "Patch")
        #expect(tool.subtitle == "2 files")
        #expect(tool.additions == 6)
        #expect(tool.deletions == 1)
        #expect(tool.opensChangesReview)
        #expect(tool.changesInitialFilePath == nil)
    }

    @Test func build_applyPatchSingleFileDeepLinksPath() {
        let blocks = okBlocks(
            parts: [
                .tool(
                    ToolPart(
                        id: "patch-1",
                        toolName: "apply_patch",
                        status: .error,
                        error: "conflict",
                        metadata: [
                            "files": .array([
                                .dictionary([
                                    "filePath": .string("solo.kt"),
                                    "additions": .int(1),
                                    "deletions": .int(1),
                                ]),
                            ]),
                        ]
                    )
                ),
            ]
        )
        guard case .toolCard(_, let tool) = blocks.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "Patch")
        #expect(tool.subtitle == "solo.kt")
        #expect(tool.additions == 1)
        #expect(tool.deletions == 1)
        #expect(tool.errorText == "conflict")
        #expect(tool.opensChangesReview)
        #expect(tool.changesInitialFilePath == "solo.kt")
    }

    @Test func build_keepsGenericChromeForUnknownTools() {
        let blocks = okBlocks(
            parts: [.tool(ToolPart(id: "custom-1", toolName: "custom_mcp", status: .completed))]
        )
        guard case .toolCard(let blockID, let tool) = blocks.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(blockID == PresentationBlockId.direct("custom-1"))
        #expect(tool.title == "Called custom_mcp")
        #expect(tool.detailText == nil)
        #expect(tool.childSessionId == nil)
        #expect(!tool.opensChangesReview)
    }

    @Test func build_hidesSyntheticTextBesideFileAttachment() {
        let blocks = okBlocks(
            role: .user,
            parts: [
                text("prt-1", "I attached logs"),
                .text(
                    TextPart(
                        id: "prt-2",
                        text: #"Called the Read tool with the following input: {"filePath":"notes.txt"}"#,
                        isStreaming: false,
                        isSynthetic: true
                    )
                ),
                .text(TextPart(id: "prt-3", text: "file body", isStreaming: false, isSynthetic: true)),
                .file(
                    FilePart(
                        id: "prt-4",
                        filename: "notes.txt",
                        mime: "text/plain",
                        kind: .text,
                        dataURL: "data:text/plain;base64,ZmlsZSBib2R5"
                    )
                ),
            ]
        )
        #expect(blocks.count == 2)
        guard case .text(let textID, let prompt, _) = blocks[0] else {
            Issue.record("Expected user prompt text")
            return
        }
        #expect(textID == PresentationBlockId.direct("prt-1"))
        #expect(prompt == "I attached logs")
        guard case .attachment(let attachmentID, let file) = blocks[1] else {
            Issue.record("Expected attachment")
            return
        }
        #expect(attachmentID == PresentationBlockId.direct("prt-4"))
        #expect(file.filename == "notes.txt")
    }

    @Test func build_missingTextPartId_returnsMissingPartIds() {
        let result = SessionChatTimelineBlocks.build(
            role: .assistant,
            parts: [
                .text(TextPart(id: nil, text: "Hello", isStreaming: false)),
            ]
        )
        #expect(result == .missingPartIds)
    }

    @Test func build_missingToolPartId_returnsMissingPartIds() {
        let result = SessionChatTimelineBlocks.build(
            role: .assistant,
            parts: [
                .tool(ToolPart(id: nil, toolName: "bash", status: .completed)),
            ]
        )
        #expect(result == .missingPartIds)
    }

    @Test func build_missingToolGroupMemberId_returnsMissingPartIds() {
        let result = SessionChatTimelineBlocks.build(
            role: .assistant,
            parts: [
                .tool(ToolPart(id: "r1", toolName: "read", status: .completed)),
                .tool(ToolPart(id: nil, toolName: "grep", status: .completed)),
            ]
        )
        #expect(result == .missingPartIds)
    }

    @Test func buildOrFallback_usesMessageFallbackWhenPartIdsMissing() {
        let blocks = SessionChatTimelineBlocks.buildOrFallback(
            role: .assistant,
            parts: [.text(TextPart(id: nil, text: "Recovered", isStreaming: false))],
            messageId: "msg-9",
            fallbackText: "Recovered"
        )
        #expect(blocks == [
            .text(
                blockID: PresentationBlockId.messageFallback("msg-9"),
                text: "Recovered",
                renderedMarkdown: nil
            ),
        ])
    }

    @Test func buildOrFallback_emptyParts_usesFallbackText() {
        let blocks = SessionChatTimelineBlocks.buildOrFallback(
            role: .user,
            parts: [],
            messageId: "msg-1",
            fallbackText: "Hello"
        )
        #expect(blocks == [
            .text(
                blockID: PresentationBlockId.messageFallback("msg-1"),
                text: "Hello",
                renderedMarkdown: nil
            ),
        ])
    }

    @Test func build_mergedTextMembershipChange_changesBlockID() {
        let two = okBlocks(parts: [text("a", "Hello"), text("b", "World")])
        let three = okBlocks(parts: [text("a", "Hello"), text("b", "World"), text("c", "!")])
        #expect(two.single?.blockID == PresentationBlockId.mergedText(["a", "b"]))
        #expect(three.single?.blockID == PresentationBlockId.mergedText(["a", "b", "c"]))
        #expect(two.single?.blockID != three.single?.blockID)
    }

    @Test func build_collapseOff_keepsNonContextToolsSeparate() {
        let blocks = okBlocks(
            parts: [
                .tool(ToolPart(id: "bash-1", toolName: "bash", status: .completed)),
                .tool(ToolPart(id: "bash-2", toolName: "bash", status: .completed)),
            ]
        )
        #expect(blocks.count == 2)
        guard case .toolCard = blocks[0], case .toolCard = blocks[1] else {
            Issue.record("Expected two tool cards when collapse is off")
            return
        }
        #expect(blocks.map(\.blockID) == [
            PresentationBlockId.direct("bash-1"),
            PresentationBlockId.direct("bash-2"),
        ])
    }

    @Test func build_collapseOn_groupsMixedConsecutiveTools() {
        let blocks = okBlocks(
            parts: [
                .tool(ToolPart(id: "bash-1", toolName: "bash", status: .completed)),
                .tool(ToolPart(id: "edit-1", toolName: "edit", status: .completed)),
                .tool(ToolPart(id: "read-1", toolName: "read", status: .completed)),
            ],
            collapseConsecutiveToolCalls: true
        )
        guard case .toolGroup(let blockID, let group) = blocks.single else {
            Issue.record("Expected tool group")
            return
        }
        #expect(blockID == PresentationBlockId.toolGroup(["bash-1", "edit-1", "read-1"]))
        #expect(group.count == 3)
        #expect(group.title == "3 tools")
        #expect(group.subtitle == "Shell · Edit · Read")
    }

    @Test func build_collapseOn_mixedFamilyTitleCounts() {
        let blocks = okBlocks(
            parts: [
                .tool(ToolPart(id: "r1", toolName: "read", status: .completed)),
                .tool(ToolPart(id: "r2", toolName: "read", status: .completed)),
                .tool(ToolPart(id: "bash-1", toolName: "bash", status: .completed)),
                .tool(ToolPart(id: "edit-1", toolName: "edit", status: .completed)),
            ],
            collapseConsecutiveToolCalls: true
        )
        guard case .toolGroup(_, let group) = blocks.single else {
            Issue.record("Expected tool group")
            return
        }
        #expect(group.count == 4)
        #expect(group.title == "4 tools")
        #expect(group.subtitle == "2 reads · Shell · Edit")
    }

    @Test func build_groupMembersUseCommandAndPath() {
        let blocks = okBlocks(
            parts: [
                .tool(ToolPart(
                    id: "bash-1",
                    toolName: "bash",
                    status: .completed,
                    input: ["command": .string("xcodebuild build")]
                )),
                .tool(ToolPart(
                    id: "edit-1",
                    toolName: "edit",
                    status: .completed,
                    input: ["filePath": .string("App.swift")]
                )),
            ],
            collapseConsecutiveToolCalls: true
        )
        guard case .toolGroup(_, let group) = blocks.single else {
            Issue.record("Expected tool group")
            return
        }
        #expect(group.title == "2 tools")
        #expect(group.subtitle == "Shell · Edit")
        #expect(group.members.count == 2)
        #expect(group.members[0].title == "Shell")
        #expect(group.members[0].subtitle == "xcodebuild build")
        #expect(group.members[1].title == "Edit")
        #expect(group.members[1].subtitle == "App.swift")
        #expect(group.members[1].opensChangesReview)
    }

    @Test func build_collapseOn_singleToolStaysCard() {
        let blocks = okBlocks(
            parts: [
                .tool(ToolPart(id: "bash-1", toolName: "bash", status: .completed)),
            ],
            collapseConsecutiveToolCalls: true
        )
        guard case .toolCard(let blockID, _) = blocks.single else {
            Issue.record("Expected tool card")
            return
        }
        #expect(blockID == PresentationBlockId.direct("bash-1"))
    }

    @Test func build_collapseOn_textBreaksGroup() {
        let blocks = okBlocks(
            parts: [
                .tool(ToolPart(id: "bash-1", toolName: "bash", status: .completed)),
                text("note-1", "note"),
                .tool(ToolPart(id: "bash-2", toolName: "bash", status: .completed)),
            ],
            collapseConsecutiveToolCalls: true
        )
        #expect(blocks.count == 3)
        guard case .toolCard = blocks[0], case .toolCard = blocks[2] else {
            Issue.record("Expected tool cards around text")
            return
        }
    }

    @Test func build_collapseOn_emptyTextDoesNotBreakGroup() {
        let blocks = okBlocks(
            parts: [
                .tool(ToolPart(id: "bash-1", toolName: "bash", status: .completed)),
                text("gap-1", "  \n"),
                .tool(ToolPart(id: "bash-2", toolName: "bash", status: .completed)),
            ],
            collapseConsecutiveToolCalls: true
        )
        guard case .toolGroup(_, let group) = blocks.single else {
            Issue.record("Expected tool group")
            return
        }
        #expect(group.count == 2)
        #expect(group.title == "2 tools")
        #expect(group.subtitle == "2 shells")
    }

    @Test func build_collapseOn_hiddenToolDoesNotBreakGroup() {
        let blocks = okBlocks(
            parts: [
                .tool(ToolPart(id: "r1", toolName: "read", status: .completed)),
                .tool(ToolPart(id: "todo-1", toolName: "todowrite", status: .completed)),
                .tool(ToolPart(id: "r2", toolName: "read", status: .completed)),
            ],
            collapseConsecutiveToolCalls: true
        )
        guard case .toolGroup(let blockID, let group) = blocks.single else {
            Issue.record("Expected tool group")
            return
        }
        #expect(group.count == 2)
        #expect(group.title == "Explored — 2 reads")
        #expect(group.subtitle == nil)
        #expect(blockID == PresentationBlockId.toolGroup(["r1", "r2"]))
    }

    @Test func build_collapseOn_groupStatusPrefersError() {
        let blocks = okBlocks(
            parts: [
                .tool(ToolPart(id: "bash-1", toolName: "bash", status: .completed)),
                .tool(ToolPart(id: "edit-1", toolName: "edit", status: .error, error: "boom")),
                .tool(ToolPart(id: "read-1", toolName: "read", status: .running)),
            ],
            collapseConsecutiveToolCalls: true
        )
        guard case .toolGroup(_, let group) = blocks.single else {
            Issue.record("Expected tool group")
            return
        }
        #expect(group.status == .error)
        #expect(group.errorText == "boom")
    }

    @Test func build_collapseOn_taskNeverGroups() {
        let blocks = okBlocks(
            parts: [
                .tool(ToolPart(id: "task-1", toolName: "task", status: .completed, metadata: ["sessionId": .string("ses-1")])),
                .tool(ToolPart(id: "task-2", toolName: "task", status: .completed, metadata: ["sessionId": .string("ses-2")])),
            ],
            collapseConsecutiveToolCalls: true
        )
        #expect(blocks.count == 2)
        guard case .childSessionRow(let firstID, let first) = blocks[0],
              case .childSessionRow(let secondID, _) = blocks[1] else {
            Issue.record("Expected two child-session rows, not a group")
            return
        }
        #expect(firstID == PresentationBlockId.direct("task-1"))
        #expect(secondID == PresentationBlockId.direct("task-2"))
        #expect(first.childSessionId == "ses-1")
    }

    @Test func build_collapseOn_taskBreaksGroup() {
        let blocks = okBlocks(
            parts: [
                .tool(ToolPart(id: "r1", toolName: "read", status: .completed)),
                .tool(ToolPart(id: "task-1", toolName: "task", status: .completed)),
                .tool(ToolPart(id: "r2", toolName: "read", status: .completed)),
            ],
            collapseConsecutiveToolCalls: true
        )
        #expect(blocks.count == 3)
        guard case .toolCard = blocks[0],
              case .childSessionRow = blocks[1],
              case .toolCard = blocks[2] else {
            Issue.record("Expected task to break grouping as a child-session row")
            return
        }
    }

    @Test func merge_collapseOn_groupsConsecutiveToolOnlyAssistantMessages() {
        let bodies = [
            toolOnlyBody(id: "a1", toolID: "bash-1"),
            toolOnlyBody(id: "a2", toolID: "bash-2"),
            toolOnlyBody(id: "a3", toolID: "bash-3"),
        ]
        let merged = SessionChatTimelineBlocks.mergeConsecutiveToolOnlyAssistantBodies(
            bodies,
            collapseConsecutiveToolCalls: true
        )
        #expect(merged.count == 1)
        #expect(merged[0].message.id == "a1")
        guard case .toolGroup(_, let group) = merged[0].blocks.single else {
            Issue.record("Expected one tool group")
            return
        }
        #expect(group.count == 3)
        #expect(group.title == "3 tools")
        #expect(group.subtitle == "3 shells")
    }

    @Test func merge_collapseOff_keepsConsecutiveBashMessagesSeparate() {
        let bodies = [
            toolOnlyBody(id: "a1", toolID: "bash-1"),
            toolOnlyBody(id: "a2", toolID: "bash-2"),
        ]
        let merged = SessionChatTimelineBlocks.mergeConsecutiveToolOnlyAssistantBodies(
            bodies,
            collapseConsecutiveToolCalls: false
        )
        #expect(merged.count == 2)
        guard case .toolCard = merged[0].blocks.single, case .toolCard = merged[1].blocks.single else {
            Issue.record("Expected two shell cards when collapse is off")
            return
        }
    }

    @Test func merge_collapseOn_textMessageBreaksRun() {
        let bodies = [
            toolOnlyBody(id: "a1", toolID: "bash-1"),
            textBody(id: "a2", text: "note"),
            toolOnlyBody(id: "a3", toolID: "bash-3"),
        ]
        let merged = SessionChatTimelineBlocks.mergeConsecutiveToolOnlyAssistantBodies(
            bodies,
            collapseConsecutiveToolCalls: true
        )
        #expect(merged.count == 3)
    }

    @Test func merge_collapseOn_taskMessageBreaksRun() {
        let bodies = [
            toolOnlyBody(id: "a1", toolID: "bash-1"),
            toolOnlyBody(id: "a2", toolID: "task-1", toolName: "task"),
            toolOnlyBody(id: "a3", toolID: "bash-3"),
        ]
        let merged = SessionChatTimelineBlocks.mergeConsecutiveToolOnlyAssistantBodies(
            bodies,
            collapseConsecutiveToolCalls: true
        )
        #expect(merged.count == 3)
    }

    @Test func merge_collapseOn_runningTaskRowBreaksRun() {
        let bodies = [
            toolOnlyBody(id: "a1", toolID: "bash-1"),
            toolOnlyBody(id: "a2", toolID: "task-1", toolName: "task", status: .running),
            toolOnlyBody(id: "a3", toolID: "bash-3"),
        ]
        let merged = SessionChatTimelineBlocks.mergeConsecutiveToolOnlyAssistantBodies(
            bodies,
            collapseConsecutiveToolCalls: true
        )
        #expect(merged.count == 3)
        guard case .childSessionRow = merged[1].blocks.single else {
            Issue.record("Expected running task to stay a child-session row")
            return
        }
    }

    @Test func build_collapseOff_mixedToolsContextStillGroups() {
        let blocks = okBlocks(
            parts: [
                .tool(ToolPart(id: "r1", toolName: "read", status: .completed)),
                .tool(ToolPart(id: "r2", toolName: "read", status: .completed)),
                .tool(ToolPart(id: "bash-1", toolName: "bash", status: .completed)),
            ]
        )
        #expect(blocks.count == 2)
        guard case .toolGroup(let blockID, let group) = blocks[0] else {
            Issue.record("Expected context group")
            return
        }
        #expect(blockID == PresentationBlockId.toolGroup(["r1", "r2"]))
        #expect(group.title == "Explored — 2 reads")
        #expect(group.subtitle == nil)
        guard case .toolCard = blocks[1] else {
            Issue.record("Expected bash card")
            return
        }
    }

    @Test func build_toolGroupMembershipChange_changesBlockID() {
        let two = okBlocks(
            parts: [
                .tool(ToolPart(id: "r1", toolName: "read", status: .completed)),
                .tool(ToolPart(id: "g1", toolName: "grep", status: .completed)),
            ]
        )
        let three = okBlocks(
            parts: [
                .tool(ToolPart(id: "r1", toolName: "read", status: .completed)),
                .tool(ToolPart(id: "g1", toolName: "grep", status: .completed)),
                .tool(ToolPart(id: "l1", toolName: "glob", status: .completed)),
            ]
        )
        #expect(two.single?.blockID == PresentationBlockId.toolGroup(["r1", "g1"]))
        #expect(three.single?.blockID == PresentationBlockId.toolGroup(["r1", "g1", "l1"]))
        #expect(two.single?.blockID != three.single?.blockID)
    }

    private func text(_ id: String, _ value: String) -> MessagePart {
        .text(TextPart(id: id, text: value, isStreaming: false))
    }

    // MARK: - Chip timeline (OPE-260)

    private func paragraph(
        _ block: SessionChatTimelineBlock?,
        sourceLocation: SourceLocation = #_sourceLocation
    ) -> SessionChatStepParagraphBlock? {
        guard case .stepParagraph(_, let paragraph)? = block else {
            Issue.record("Expected step paragraph, got \(String(describing: block))", sourceLocation: sourceLocation)
            return nil
        }
        return paragraph
    }

    @Test func chipTimeline_textThenTools_oneParagraphWithChips() {
        let blocks = okBlocks(
            parts: [
                text("t1", "Looking at the view"),
                .tool(ToolPart(id: "r1", toolName: "read", status: .completed, input: ["filePath": .string("ios/App/SessionChatView.swift")])),
                .tool(ToolPart(id: "b1", toolName: "bash", status: .completed, input: ["command": .string("xcodebuild test -scheme App")])),
            ],
            chipTimeline: true
        )
        #expect(blocks.count == 1)
        #expect(blocks[0].blockID == PresentationBlockId.stepParagraph(["t1", "r1", "b1"]))
        guard let paragraph = paragraph(blocks.single) else { return }
        #expect(paragraph.text == "Looking at the view")
        #expect(paragraph.textBlockID == PresentationBlockId.mergedText(["t1"]))
        #expect(paragraph.chips.map(\.label) == ["SessionChatView.swift", "xcodebuild test -scheme App"])
        #expect(paragraph.chips.map(\.kind) == [.file, .command])
        #expect(paragraph.chips.map(\.family) == [.explore, .command])
        #expect(paragraph.errorLines.isEmpty)
        #expect(blocks[0].textContent?.blockID == PresentationBlockId.direct("t1"))
    }

    @Test func chipTimeline_toolsTextTools_twoParagraphs() {
        let blocks = okBlocks(
            parts: [
                .tool(ToolPart(id: "r1", toolName: "read", status: .completed, input: ["filePath": .string("a.swift")])),
                text("t1", "Now editing"),
                .tool(ToolPart(id: "e1", toolName: "edit", status: .completed, input: ["filePath": .string("dir/b.swift")])),
            ],
            chipTimeline: true
        )
        #expect(blocks.count == 2)
        guard let first = paragraph(blocks[0]), let second = paragraph(blocks[1]) else { return }
        #expect(first.text == nil)
        #expect(first.textBlockID == nil)
        #expect(first.chips.map(\.label) == ["a.swift"])
        #expect(blocks[0].blockID == PresentationBlockId.stepParagraph(["r1"]))
        #expect(second.text == "Now editing")
        #expect(second.chips.map(\.label) == ["b.swift"])
        #expect(second.chips[0].family == .change)
        #expect(blocks[1].blockID == PresentationBlockId.stepParagraph(["t1", "e1"]))
    }

    @Test func chipTimeline_adjacentTextMergesLikeTextBlock() {
        let blocks = okBlocks(
            parts: [text("t1", "One"), text("t2", "Two")],
            chipTimeline: true
        )
        guard let paragraph = paragraph(blocks.single) else { return }
        #expect(paragraph.text == "One\nTwo")
        #expect(paragraph.textBlockID == PresentationBlockId.mergedText(["t1", "t2"]))
        #expect(paragraph.chips.isEmpty)
    }

    @Test func chipTimeline_childSessionTool_becomesAgentChip() {
        let blocks = okBlocks(
            parts: [
                .tool(
                    ToolPart(
                        id: "task-1",
                        toolName: "task",
                        status: .running,
                        input: [
                            "agent": .string("Explore"),
                            "description": .string("Find the session list\nand report back"),
                        ],
                        metadata: ["sessionId": .string("child-1")]
                    )
                ),
            ],
            chipTimeline: true
        )
        guard let paragraph = paragraph(blocks.single) else { return }
        #expect(paragraph.chips.count == 1)
        let chip = paragraph.chips[0]
        #expect(chip.kind == .agent)
        #expect(chip.family == .agent)
        #expect(chip.label == "Explore")
        #expect(chip.brief == "Find the session list")
        #expect(chip.status == .running)
        #expect(chip.trailingText == nil)
        #expect(chip.card.childSessionId == "child-1")
    }

    @Test func chipTimeline_failedTool_errorStatusAndLine() {
        let blocks = okBlocks(
            parts: [
                text("t1", "Running tests"),
                .tool(ToolPart(id: "b1", toolName: "bash", status: .error, error: "exit 1", input: ["command": .string("npm test")])),
            ],
            chipTimeline: true
        )
        guard let paragraph = paragraph(blocks.single) else { return }
        #expect(paragraph.chips[0].status == .error)
        #expect(paragraph.errorLines == ["npm test: exit 1"])
    }

    @Test func chipTimeline_labelsPerTool() {
        let blocks = okBlocks(
            parts: [
                .tool(ToolPart(id: "g1", toolName: "glob", status: .completed, input: ["pattern": .string("**/*.kt"), "path": .string("/repo")])),
                .tool(ToolPart(id: "gr", toolName: "grep", status: .completed, input: ["pattern": .string("chipTimeline"), "path": .string("/repo")])),
                .tool(ToolPart(id: "ws", toolName: "websearch", status: .completed, input: ["query": .string("swift layout")])),
                .tool(ToolPart(id: "wf", toolName: "webfetch", status: .completed, input: ["url": .string("https://developer.apple.com/docs/layout")])),
                .tool(ToolPart(id: "w1", toolName: "write", status: .completed, input: ["filePath": .string("a/b/New.swift")], metadata: ["filediff": .dictionary(["file": .string("a/b/New.swift"), "additions": .int(20)])])),
                .tool(ToolPart(id: "b1", toolName: "bash", status: .completed, input: ["command": .string("./gradlew :app:testDebugUnitTest --info")])),
                .tool(ToolPart(id: "s1", toolName: "skill", status: .completed, input: ["name": .string("feature")])),
                .tool(ToolPart(id: "x1", toolName: "linear_get_issue", status: .completed, input: ["id": .string("OPE-260")])),
            ],
            chipTimeline: true
        )
        guard let paragraph = paragraph(blocks.single) else { return }
        // Family order: skill, explore, agent, change, command.
        #expect(paragraph.chips.map(\.label) == [
            "/feature",
            "**/*.kt", "chipTimeline", "swift layout", "developer.apple.com",
            "New.swift",
            "./gradlew :app:testDebugUnitTest --info", "linear_get_issue",
        ])
        #expect(paragraph.chips.map(\.family) == [.skill, .explore, .explore, .explore, .explore, .change, .command, .command])
        let write = paragraph.chips[5]
        #expect(write.additions == 20)
        #expect(write.deletions == nil)
        #expect(write.card.opensChangesReview)
        #expect(paragraph.chips[6].kind == .command)
    }

    @Test func chipTimeline_commandLabel_firstNonEmptyLineCollapsedAndTrimmed() {
        let command = "\n   \n  for n in 1 2 3; do\n    echo $n\n  done\n"
        #expect(SessionChatTimelineBlocks.commandChipLabel(command) == "for n in 1 2 3; do")
        #expect(SessionChatTimelineBlocks.commandChipLabel("  gh   pr\tlist  ") == "gh pr list")
    }

    @Test func chipTimeline_commandLabel_truncatesTo40WithEllipsis() {
        let command = String(repeating: "abcdef ", count: 8).trimmingCharacters(in: .whitespaces) + "12345"
        #expect(command.count == 60)
        let label = SessionChatTimelineBlocks.commandChipLabel(command)
        #expect(label.count == 41)
        #expect(label.hasSuffix("\u{2026}"))
        #expect(label == String(command.prefix(40)) + "\u{2026}")
    }

    @Test func chipTimeline_commandLabel_shortCommandUnchanged() {
        let blocks = okBlocks(
            parts: [
                .tool(ToolPart(id: "b1", toolName: "bash", status: .completed, input: ["command": .string("gh pr list --state open --limit 50")])),
            ],
            chipTimeline: true
        )
        guard let paragraph = paragraph(blocks.single) else { return }
        #expect(paragraph.chips.map(\.label) == ["gh pr list --state open --limit 50"])
    }

    @Test func chipTimeline_consecutiveThoughts_collapseIntoOneRowWithSummedDuration() {
        let blocks = okBlocks(
            parts: [
                .reasoning(ReasoningPart(id: "th1", text: "First", isStreaming: false, timeStartEpochMs: 0, timeEndEpochMs: 1_200)),
                .reasoning(ReasoningPart(id: "th2", text: "Second", isStreaming: false, timeStartEpochMs: 1_200, timeEndEpochMs: 6_000)),
                .reasoning(ReasoningPart(id: "th3", text: "", isStreaming: false, timeStartEpochMs: 6_000, timeEndEpochMs: 6_500)),
                text("t1", "Now"),
                .tool(ToolPart(id: "r1", toolName: "read", status: .completed, input: ["filePath": .string("a.swift")])),
            ],
            chipTimeline: true
        )
        #expect(blocks.count == 2)
        guard case .reasoning(let id, let joined, false, let duration) = blocks[0] else {
            Issue.record("Expected one merged reasoning row")
            return
        }
        // th3 is blank, so the visibility rule hides it; two settled thoughts merge.
        #expect(id == PresentationBlockId.mergedThought([PresentationBlockId.direct("th1"), "th2"]))
        #expect(joined == "First\n\nSecond")
        #expect(duration == 6_000)
        guard let paragraph = paragraph(blocks[1]) else { return }
        #expect(paragraph.text == "Now")
    }

    @Test func chipTimeline_thoughtsAroundOneParagraph_mergeAboveIt() {
        let blocks = okBlocks(
            parts: [
                .reasoning(ReasoningPart(id: "th1", text: "First", isStreaming: false, timeStartEpochMs: 0, timeEndEpochMs: 1_000)),
                .tool(ToolPart(id: "r1", toolName: "read", status: .completed, input: ["filePath": .string("a.swift")])),
                .reasoning(ReasoningPart(id: "th2", text: "Second", isStreaming: false, timeStartEpochMs: 1_000, timeEndEpochMs: 3_000)),
                .tool(ToolPart(id: "r2", toolName: "read", status: .completed, input: ["filePath": .string("b.swift")])),
                text("t1", "Now narration"),
                .reasoning(ReasoningPart(id: "th3", text: "Third", isStreaming: false, timeStartEpochMs: 3_000, timeEndEpochMs: 4_000)),
                .tool(ToolPart(id: "r3", toolName: "read", status: .completed, input: ["filePath": .string("c.swift")])),
            ],
            chipTimeline: true
        )
        // Rows: one merged thought for the tool-only stretch, its paragraph,
        // then the thought that followed the narration and the final paragraph.
        #expect(blocks.count == 4)
        guard case .reasoning(_, _, false, let firstDuration) = blocks[0] else {
            Issue.record("Expected merged reasoning first")
            return
        }
        #expect(firstDuration == 3_000)
        guard let first = paragraph(blocks[1]) else { return }
        #expect(first.text == nil)
        #expect(first.chips.map(\.label) == ["a.swift", "b.swift"])
        guard case .reasoning(let thirdID, _, _, _) = blocks[2] else {
            Issue.record("Expected the post-narration thought third")
            return
        }
        #expect(thirdID == PresentationBlockId.direct("th3"))
        guard let second = paragraph(blocks[3]) else { return }
        #expect(second.text == "Now narration")
        #expect(second.chips.map(\.label) == ["c.swift"])
    }

    @Test func chipTimeline_thoughtAfterPendingNarration_doesNotMerge() {
        let blocks = okBlocks(
            parts: [
                .reasoning(ReasoningPart(id: "th1", text: "First", isStreaming: false, timeStartEpochMs: 0, timeEndEpochMs: 1_000)),
                text("t1", "Narration"),
                .reasoning(ReasoningPart(id: "th2", text: "Second", isStreaming: false, timeStartEpochMs: 1_000, timeEndEpochMs: 2_000)),
                .tool(ToolPart(id: "r1", toolName: "read", status: .completed, input: ["filePath": .string("a.swift")])),
            ],
            chipTimeline: true
        )
        #expect(blocks.count == 3)
        guard case .reasoning(let firstID, let first, _, _) = blocks[0] else {
            Issue.record("Expected the first thought")
            return
        }
        #expect(firstID == PresentationBlockId.direct("th1"))
        #expect(first == "First")
        guard case .reasoning(let secondID, let second, _, _) = blocks[1] else {
            Issue.record("Expected the second thought")
            return
        }
        #expect(secondID == PresentationBlockId.direct("th2"))
        #expect(second == "Second")
        guard let narration = paragraph(blocks[2]) else { return }
        #expect(narration.text == "Narration")
        #expect(narration.chips.map(\.label) == ["a.swift"])
    }

    @Test func streamingStepParagraphBlockID_marksOnlyTheLatestNarration() {
        let earlier = SessionChatTimelineBlock.stepParagraph(
            blockID: "earlier",
            paragraph: SessionChatStepParagraphBlock(
                textBlockID: nil,
                text: "Done",
                renderedMarkdown: nil,
                chips: [],
                errorLines: []
            )
        )
        let later = SessionChatTimelineBlock.stepParagraph(
            blockID: "later",
            paragraph: SessionChatStepParagraphBlock(
                textBlockID: nil,
                text: "Live",
                renderedMarkdown: nil,
                chips: [],
                errorLines: []
            )
        )
        #expect(
            SessionChatTimelineBlocks.streamingStepParagraphBlockID(
                blocks: [earlier, later],
                messageIsStreaming: true
            ) == "later"
        )
        #expect(
            SessionChatTimelineBlocks.streamingStepParagraphBlockID(
                blocks: [earlier, later],
                messageIsStreaming: false
            ) == nil
        )
    }

    @Test func build_collapseOff_consecutiveThoughts_stayAsSeparateRows() {
        let blocks = okBlocks(
            parts: [
                .reasoning(ReasoningPart(id: "th1", text: "First", isStreaming: false)),
                .reasoning(ReasoningPart(id: "th2", text: "Second", isStreaming: false)),
            ]
        )
        #expect(blocks.count == 2)
    }

    @Test func chipTimeline_attachmentAndReasoning_emittedSeparatelyWithoutClosingParagraph() {
        let file = FilePart(id: "f1", filename: "shot.png", mime: "image/png", kind: .image, dataURL: "data:image/png;base64,")
        let blocks = okBlocks(
            parts: [
                text("t1", "Before"),
                .tool(ToolPart(id: "r1", toolName: "read", status: .completed, input: ["filePath": .string("a.swift")])),
                .file(file),
                .reasoning(ReasoningPart(id: "th1", text: "Considered options", isStreaming: false)),
                .tool(ToolPart(id: "r2", toolName: "read", status: .completed, input: ["filePath": .string("b.swift")])),
            ],
            chipTimeline: true
        )
        #expect(blocks.count == 3)
        guard case .attachment(let attachmentID, _) = blocks[0] else {
            Issue.record("Expected attachment first")
            return
        }
        #expect(attachmentID == PresentationBlockId.direct("f1"))
        guard case .reasoning(let reasoningID, _, _, _) = blocks[1] else {
            Issue.record("Expected reasoning second")
            return
        }
        #expect(reasoningID == PresentationBlockId.direct("th1"))
        guard let paragraph = paragraph(blocks[2]) else { return }
        #expect(paragraph.text == "Before")
        #expect(paragraph.chips.map(\.label) == ["a.swift", "b.swift"])
        #expect(blocks[2].blockID == PresentationBlockId.stepParagraph(["t1", "r1", "r2"]))
    }

    @Test func chipTimeline_hiddenToolsSkipped() {
        let blocks = okBlocks(
            parts: [
                text("t1", "Visible"),
                .tool(ToolPart(id: "todo-1", toolName: "todowrite", status: .completed)),
            ],
            chipTimeline: true
        )
        guard let paragraph = paragraph(blocks.single) else { return }
        #expect(paragraph.chips.isEmpty)
        #expect(blocks[0].blockID == PresentationBlockId.stepParagraph(["t1"]))
    }

    @Test func chipTimeline_missingPartIds_fallsBackLikeToday() {
        let result = SessionChatTimelineBlocks.build(
            role: .assistant,
            parts: [
                text("t1", "Visible"),
                .tool(ToolPart(id: nil, toolName: "bash", status: .completed, input: ["command": .string("ls")])),
            ],
            chipTimeline: true
        )
        #expect(result == .missingPartIds)
        let fallback = SessionChatTimelineBlocks.buildOrFallback(
            role: .assistant,
            parts: [
                text("t1", "Visible"),
                .tool(ToolPart(id: nil, toolName: "bash", status: .completed, input: ["command": .string("ls")])),
            ],
            messageId: "m1",
            fallbackText: "Visible",
            chipTimeline: true
        )
        #expect(fallback == [.text(blockID: PresentationBlockId.messageFallback("m1"), text: "Visible", renderedMarkdown: nil)])
    }

    @Test func chipTimeline_userRole_unchanged() {
        let blocks = okBlocks(
            role: .user,
            parts: [text("t1", "Hello")],
            chipTimeline: true
        )
        #expect(blocks == [.text(blockID: "p:t1", text: "Hello", renderedMarkdown: nil)])
    }

    @Test func chipTimeline_off_outputUnchanged() {
        let parts: [MessagePart] = [
            text("t1", "Before"),
            .tool(ToolPart(id: "bash-1", toolName: "bash", status: .completed, input: ["command": .string("ls")])),
        ]
        #expect(okBlocks(parts: parts) == okBlocks(parts: parts, chipTimeline: false))
        #expect(okBlocks(parts: parts).count == 2)
    }

    @Test func merge_chipTimeline_joinsConsecutiveAssistantBodiesIncludingText() {
        let bodies = [
            textBody(id: "a1", text: "Looking around"),
            toolOnlyBody(id: "a2", toolID: "bash-1"),
            toolOnlyBody(id: "a3", toolID: "bash-2"),
        ]
        let merged = SessionChatTimelineBlocks.mergeConsecutiveToolOnlyAssistantBodies(
            bodies,
            collapseConsecutiveToolCalls: false,
            chipTimeline: true
        )
        #expect(merged.count == 1)
        #expect(merged[0].message.id == "a1")
        guard let paragraph = paragraph(merged[0].blocks.single) else { return }
        #expect(paragraph.text == "Looking around")
        #expect(paragraph.chips.count == 2)
        #expect(merged[0].blocks[0].blockID == PresentationBlockId.stepParagraph(["a1-t", "bash-1", "bash-2"]))
    }

    @Test func merge_chipTimeline_userBodyBreaksRun() {
        let userPart = text("u-t", "Question")
        let user = DisplayMessageBody(
            message: Message(id: "u1", role: .user, text: "Question", parts: [userPart], model: nil, mode: nil, sentAtEpochMs: nil),
            isStreaming: false,
            blocks: okBlocks(role: .user, parts: [userPart]),
            renderedMarkdown: nil
        )
        let bodies = [
            textBody(id: "a1", text: "First"),
            user,
            textBody(id: "a2", text: "Second"),
            toolOnlyBody(id: "a3", toolID: "bash-1"),
        ]
        let merged = SessionChatTimelineBlocks.mergeConsecutiveToolOnlyAssistantBodies(
            bodies,
            collapseConsecutiveToolCalls: false,
            chipTimeline: true
        )
        #expect(merged.map(\.message.id) == ["a1", "u1", "a2"])
        #expect(merged[2].isStreaming == false)
        guard let paragraph = paragraph(merged[2].blocks.single) else { return }
        #expect(paragraph.text == "Second")
        #expect(paragraph.chips.count == 1)
    }

    @Test func merge_chipTimeline_off_textBodiesStaySeparate() {
        let bodies = [
            textBody(id: "a1", text: "First"),
            toolOnlyBody(id: "a2", toolID: "bash-1"),
        ]
        let merged = SessionChatTimelineBlocks.mergeConsecutiveToolOnlyAssistantBodies(
            bodies,
            collapseConsecutiveToolCalls: true
        )
        #expect(merged.count == 2)
    }

    @Test func merge_chipTimeline_carriesMemberRootMarkdownOntoParagraph() {
        let document = NativeMarkdownDocument(
            sourceHash: stableMarkdownSourceHash("# Looking"),
            blocks: [.paragraph([.text("Looking")])]
        )
        let part = text("a1-t", "# Looking")
        let withMarkdown = DisplayMessageBody(
            message: Message(id: "a1", role: .assistant, text: "# Looking", parts: [part], model: nil, mode: nil, sentAtEpochMs: nil),
            isStreaming: false,
            blocks: okBlocks(parts: [part], chipTimeline: true),
            renderedMarkdown: document
        )
        let merged = SessionChatTimelineBlocks.mergeConsecutiveToolOnlyAssistantBodies(
            [withMarkdown, toolOnlyBody(id: "a2", toolID: "bash-1")],
            collapseConsecutiveToolCalls: false,
            chipTimeline: true
        )
        guard let paragraph = paragraph(merged.first?.blocks.single) else { return }
        #expect(paragraph.renderedMarkdown?.sourceHash == document.sourceHash)
    }

    private func toolOnlyBody(
        id: String,
        toolID: String,
        toolName: String = "bash",
        status: ToolStatus = .completed
    ) -> DisplayMessageBody {
        let part: MessagePart = .tool(ToolPart(id: toolID, toolName: toolName, status: status))
        let message = Message(
            id: id,
            role: .assistant,
            text: "",
            parts: [part],
            model: nil,
            mode: nil,
            sentAtEpochMs: nil
        )
        return DisplayMessageBody(
            message: message,
            isStreaming: false,
            blocks: okBlocks(parts: [part], collapseConsecutiveToolCalls: true),
            renderedMarkdown: nil
        )
    }

    private func textBody(id: String, text value: String) -> DisplayMessageBody {
        let part = text("\(id)-t", value)
        let message = Message(
            id: id,
            role: .assistant,
            text: value,
            parts: [part],
            model: nil,
            mode: nil,
            sentAtEpochMs: nil
        )
        return DisplayMessageBody(
            message: message,
            isStreaming: false,
            blocks: okBlocks(parts: [part]),
            renderedMarkdown: nil
        )
    }

    private func okBlocks(
        role: MessageRole = .assistant,
        parts: [MessagePart],
        collapseConsecutiveToolCalls: Bool = false,
        chipTimeline: Bool = false,
        sourceLocation: SourceLocation = #_sourceLocation
    ) -> [SessionChatTimelineBlock] {
        let result = SessionChatTimelineBlocks.build(
            role: role,
            parts: parts,
            collapseConsecutiveToolCalls: collapseConsecutiveToolCalls,
            chipTimeline: chipTimeline
        )
        guard case .ok(let blocks) = result else {
            Issue.record("Expected Ok", sourceLocation: sourceLocation)
            return []
        }
        return blocks
    }
}

private extension Array where Element == SessionChatTimelineBlock {
    var single: SessionChatTimelineBlock? {
        count == 1 ? first : nil
    }
}
