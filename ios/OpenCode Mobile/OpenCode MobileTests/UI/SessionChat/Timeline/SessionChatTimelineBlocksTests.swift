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

    @Test func build_readUsesV2PathAsSubtitle() {
        let blocks = okBlocks(
            parts: [
                .tool(
                    ToolPart(
                        id: "read-1",
                        toolName: "read",
                        status: .completed,
                        input: ["path": .string("src/app.ts")],
                        output: "1: export function handler()"
                    )
                ),
            ]
        )
        guard case .toolCard(_, let tool) = blocks.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "Explored — 1 read")
        #expect(tool.subtitle == "src/app.ts")
        #expect(tool.detailText == "1: export function handler()")
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
        #expect(search.detailText == "https://a.example\nhttps://b.example")
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

    @Test func chipTimeline_skillChipNamesFromServerIdAndMetadata() throws {
        let blocks = okBlocks(
            parts: [
                .tool(ToolPart(id: "s1", toolName: "skill", status: .running, input: ["id": .string("feature")])),
                .tool(
                    ToolPart(
                        id: "s2",
                        toolName: "skill",
                        status: .completed,
                        input: ["id": .string("linear")],
                        metadata: [
                            "name": .string("linear-project-tracking"),
                            "directory": .string("/repo/.agents/skills/linear-project-tracking"),
                        ]
                    )
                ),
            ],
            chipTimeline: true
        )
        guard let paragraph = paragraph(blocks.single) else { return }
        #expect(paragraph.chips.map(\.label) == ["/feature", "/linear-project-tracking"])
        #expect(paragraph.chips.map(\.kind) == [.skill, .skill])
        #expect(paragraph.chips[0].brief == nil)
        #expect(paragraph.chips[1].brief == "/repo/.agents/skills/linear-project-tracking")
        #expect(paragraph.chips[1].card.subtitle == "linear-project-tracking")
    }

    @Test func skillUses_collectsAcrossParagraphsInFirstUseOrder() throws {
        let blocks = okBlocks(
            parts: [
                text("t1", "Loading."),
                .tool(
                    ToolPart(
                        id: "s1",
                        toolName: "skill",
                        status: .completed,
                        input: ["id": .string("feature")],
                        metadata: ["name": .string("feature"), "directory": .string("/repo/.agents/skills/feature")]
                    )
                ),
                text("t2", "Again."),
                .tool(ToolPart(id: "s2", toolName: "skill", status: .completed, input: ["id": .string("linear")])),
                .tool(ToolPart(id: "s3", toolName: "skill", status: .error, error: "denied", input: ["id": .string("feature")])),
                .tool(ToolPart(id: "b1", toolName: "bash", status: .completed, input: ["command": .string("ls")])),
            ],
            chipTimeline: true
        )
        let uses = SessionChatTimelineBlocks.skillUses(in: blocks)
        #expect(uses.map(\.label) == ["/feature", "/linear"])
        #expect(uses[0].id == PresentationBlockId.direct("s1"))
        #expect(uses[0].status == .error)
        #expect(uses[0].directory == "/repo/.agents/skills/feature")
        #expect(uses[1].status == .completed)
        #expect(uses[1].directory == nil)
        #expect(SessionChatTimelineBlocks.skillUses(in: okBlocks(parts: [text("t", "No tools.")], chipTimeline: true)).isEmpty)
    }

    @Test func skillUses_collapsesByRequestedIdWhenTheSettledNameDiffers() throws {
        let blocks = okBlocks(
            parts: [
                .tool(
                    ToolPart(
                        id: "s1",
                        toolName: "skill",
                        status: .completed,
                        input: ["id": .string("feature")],
                        metadata: ["name": .string("feature-workflow"), "directory": .string("/repo/.agents/skills/feature")]
                    )
                ),
                text("t", "Once more."),
                .tool(ToolPart(id: "s2", toolName: "skill", status: .running, input: ["id": .string("feature")])),
            ],
            chipTimeline: true
        )
        let uses = SessionChatTimelineBlocks.skillUses(in: blocks)
        #expect(uses.count == 1)
        #expect(uses[0].id == PresentationBlockId.direct("s1"))
        #expect(uses[0].label == "/feature")
        #expect(uses[0].status == .running)
        #expect(uses[0].directory == "/repo/.agents/skills/feature")
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

    @Test func chipTimeline_labelsPerTool() throws {
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
        // The four explore tools fold into the summary; the rest keep the
        // group order change, command, skill.
        let summary = try #require(paragraph.exploreSummary)
        #expect(summary.label == "Explored \u{00B7} 1 glob \u{00B7} 1 grep \u{00B7} 1 search \u{00B7} 1 fetch")
        #expect(summary.title == "Explored \u{2014} 1 glob \u{00B7} 1 grep \u{00B7} 1 search \u{00B7} 1 fetch")
        #expect(summary.status == .completed)
        #expect(summary.blockID == PresentationBlockId.toolGroup(["g1", "gr", "ws", "wf"]))
        #expect(summary.members.map(\.toolName) == ["glob", "grep", "websearch", "webfetch"])
        #expect(paragraph.chips.map(\.label) == [
            "New.swift",
            "./gradlew :app:testDebugUnitTest --info", "linear_get_issue",
            "/feature",
        ])
        #expect(paragraph.chips.map(\.family) == [.change, .command, .command, .skill])
        // Arrival order is kept on each chip for scoped expansion.
        #expect(paragraph.chips.map(\.arrivalIndex) == [0, 1, 3, 2])
        #expect(paragraph.chips.map(\.id) == ["p:w1", "p:b1", "p:x1", "p:s1"])
        let write = paragraph.chips[0]
        #expect(write.additions == 20)
        #expect(write.deletions == nil)
        #expect(write.card.opensChangesReview)
        #expect(paragraph.chips[1].kind == .command)
    }

    @Test func chipTimeline_commandLabel_firstNonEmptyLineCollapsedAndTrimmed() {
        let command = "\n   \n  for n in 1 2 3; do\n    echo $n\n  done\n"
        #expect(SessionChatTimelineBlocks.commandChipLabel(command) == "for n in 1 2 3; do")
        #expect(SessionChatTimelineBlocks.commandChipLabel("  gh   pr\tlist  ") == "gh pr list")
    }

    @Test func chipTimeline_commandLabel_cutsAtLastWhitespaceBefore48() {
        let command = "git log --oneline --decorate --graph --all --since=2.weeks --author=ivan"
        #expect(command.count > 48)
        let label = SessionChatTimelineBlocks.commandChipLabel(command)
        #expect(label == "git log --oneline --decorate --graph --all\u{2026}")
        #expect(label.count <= 49)
    }

    @Test func chipTimeline_commandLabel_spaceAtLimitIsABoundary() {
        // 48 characters, then a space, then more.
        let head = String(repeating: "abcdefg ", count: 6) // 48 chars, ends with a space
        let command = String(head.dropLast()) + "X more words"
        #expect(command.count > 48)
        let label = SessionChatTimelineBlocks.commandChipLabel(command)
        #expect(label == String(head.dropLast()) + "X\u{2026}")
    }

    @Test func chipTimeline_commandLabel_neverCutsInsideFirstToken() {
        let token = String(repeating: "a", count: 60)
        #expect(SessionChatTimelineBlocks.commandChipLabel(token) == token)
        #expect(SessionChatTimelineBlocks.commandChipLabel(token + " --flag") == token + "\u{2026}")
    }

    @Test func chipTimeline_commandLabel_stripsCdExportAndAssignmentPrefixes() {
        #expect(SessionChatTimelineBlocks.commandChipLabel("cd /Users/ivan/repo && npm test") == "npm test")
        #expect(SessionChatTimelineBlocks.commandChipLabel("cd ios; xcodebuild test") == "xcodebuild test")
        #expect(SessionChatTimelineBlocks.commandChipLabel("cd a && cd b; make check") == "make check")
        #expect(SessionChatTimelineBlocks.commandChipLabel("export JAVA_HOME=/opt/jdk && ./gradlew test") == "./gradlew test")
        #expect(SessionChatTimelineBlocks.commandChipLabel("export CI=1; npm run build") == "npm run build")
        #expect(SessionChatTimelineBlocks.commandChipLabel("FOO=1 BAR=two ./gradlew test") == "./gradlew test")
        #expect(SessionChatTimelineBlocks.commandChipLabel("cd repo && FOO=1 swift test") == "swift test")
        #expect(SessionChatTimelineBlocks.commandChipLabel("  cd repo  &&   npm   test  ") == "npm test")
        // Quoted values may hold spaces (parity with Android).
        #expect(SessionChatTimelineBlocks.commandChipLabel("CI=1 NODE_ENV=\"test env\" npm test") == "npm test")
        #expect(SessionChatTimelineBlocks.commandChipLabel("export A=1 B='x y'; npm test") == "npm test")
        #expect(SessionChatTimelineBlocks.commandChipLabel("cd \"a b\" && cd sub && make") == "make")
        // `export` without an assignment is not a prefix to strip.
        #expect(SessionChatTimelineBlocks.commandChipLabel("export FOO && make") == "export FOO && make")
    }

    @Test func chipTimeline_commandLabel_keepsOriginalWhenStrippingLeavesNothing() {
        #expect(SessionChatTimelineBlocks.commandChipLabel("cd /tmp &&") == "cd /tmp &&")
        #expect(SessionChatTimelineBlocks.commandChipLabel("FOO=1") == "FOO=1")
        #expect(SessionChatTimelineBlocks.commandChipLabel("cd /tmp") == "cd /tmp")
        #expect(SessionChatTimelineBlocks.commandChipLabel("git commit -m \"a=b c\"") == "git commit -m \"a=b c\"")
    }

    @Test func chipTimeline_commandChip_cardKeepsFullCommand() {
        let command = "cd /Users/ivan/repo && npm test"
        let blocks = okBlocks(
            parts: [
                .tool(ToolPart(id: "b1", toolName: "bash", status: .completed, input: ["command": .string(command)])),
            ],
            chipTimeline: true
        )
        guard let paragraph = paragraph(blocks.single) else { return }
        #expect(paragraph.chips.map(\.label) == ["npm test"])
        #expect(paragraph.chips[0].card.subtitle == command)
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
        #expect(first.exploreSummary?.label == "Explored \u{00B7} 2 reads")
        #expect(first.chips.isEmpty)
        guard case .reasoning(let thirdID, _, _, _) = blocks[2] else {
            Issue.record("Expected the post-narration thought third")
            return
        }
        #expect(thirdID == PresentationBlockId.direct("th3"))
        guard let second = paragraph(blocks[3]) else { return }
        #expect(second.text == "Now narration")
        #expect(second.chips.map(\.label) == ["c.swift"])
    }

    @Test func chipTimeline_thoughtAfterPendingNarration_collapsesIntoOneRow() {
        let blocks = okBlocks(
            parts: [
                .reasoning(ReasoningPart(id: "th1", text: "First", isStreaming: false, timeStartEpochMs: 0, timeEndEpochMs: 1_000)),
                text("t1", "Narration"),
                .reasoning(ReasoningPart(id: "th2", text: "Second", isStreaming: false, timeStartEpochMs: 1_000, timeEndEpochMs: 2_000)),
                .tool(ToolPart(id: "r1", toolName: "read", status: .completed, input: ["filePath": .string("a.swift")])),
            ],
            chipTimeline: true
        )
        #expect(blocks.count == 2)
        guard case .reasoning(let id, let joined, false, let duration) = blocks[0] else {
            Issue.record("Expected one merged reasoning row")
            return
        }
        #expect(id == PresentationBlockId.mergedThought([PresentationBlockId.direct("th1"), "th2"]))
        #expect(joined == "First\n\nSecond")
        #expect(duration == 2_000)
        guard let narration = paragraph(blocks[1]) else { return }
        #expect(narration.text == "Narration")
        #expect(narration.chips.map(\.label) == ["a.swift"])
    }

    @Test func chipTimeline_consecutiveStepThoughts_collapseUntilAParagraphFlushes() {
        let blocks = okBlocks(
            parts: [
                .reasoning(ReasoningPart(id: "th1", text: "First", isStreaming: false, timeStartEpochMs: 0, timeEndEpochMs: 1_000)),
                text("t1", "Step one"),
                .tool(ToolPart(id: "r1", toolName: "read", status: .completed, input: ["filePath": .string("a.swift")])),
                .reasoning(ReasoningPart(id: "th2", text: "Second", isStreaming: false, timeStartEpochMs: 1_000, timeEndEpochMs: 2_500)),
                text("t2", "Step two"),
                .tool(ToolPart(id: "r2", toolName: "read", status: .completed, input: ["filePath": .string("b.swift")])),
                .reasoning(ReasoningPart(id: "th3", text: "Third", isStreaming: false, timeStartEpochMs: 2_500, timeEndEpochMs: 3_000)),
                text("t3", "Step three"),
            ],
            chipTimeline: true
        )
        #expect(blocks.count == 5)
        guard case .reasoning(_, let joined, false, let duration) = blocks[0] else {
            Issue.record("Expected the opening thoughts merged")
            return
        }
        #expect(joined == "First\n\nSecond")
        #expect(duration == 2_500)
        guard let first = paragraph(blocks[1]) else { return }
        #expect(first.text == "Step one")
        #expect(first.chips.map(\.label) == ["a.swift"])
        guard case .reasoning(_, let third, false, let thirdDuration) = blocks[2] else {
            Issue.record("Expected the thought after the flushed paragraph")
            return
        }
        #expect(third == "Third")
        #expect(thirdDuration == 500)
        guard let second = paragraph(blocks[3]) else { return }
        #expect(second.text == "Step two")
        #expect(second.chips.map(\.label) == ["b.swift"])
        guard let last = paragraph(blocks[4]) else { return }
        #expect(last.text == "Step three")
        #expect(last.chips.isEmpty)
    }

    @Test func streamingStepParagraphBlockID_marksOnlyTheLatestNarration() {
        let earlier = SessionChatTimelineBlock.stepParagraph(
            blockID: "earlier",
            paragraph: SessionChatStepParagraphBlock(
                id: "sp:earlier",
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
                id: "sp:later",
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
        // Both reads land in the same paragraph, so they fold into its summary.
        #expect(paragraph.exploreSummary?.label == "Explored \u{00B7} 2 reads")
        #expect(paragraph.chips.isEmpty)
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
        // Two identical completed `bash` runs fold into one chip.
        #expect(paragraph.chips.count == 1)
        #expect(paragraph.chips.first?.repeatCount == 2)
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

    // MARK: - Chip row visibility (collapse rules)

    private func chip(
        _ family: SessionChatToolChipFamily,
        _ label: String,
        status: ToolStatus = .completed,
        arrivalIndex: Int = 0
    ) -> SessionChatToolChipBlock {
        let kind: SessionChatToolChipKind =
            switch family {
            case .explore, .change: .file
            case .command: .command
            case .agent: .agent
            case .skill: .skill
            }
        return SessionChatToolChipBlock(
            id: PresentationBlockId.direct(label),
            kind: kind,
            family: family,
            label: label,
            status: status,
            card: SessionChatToolCardBlock(toolName: label, status: status, title: label),
            arrivalIndex: arrivalIndex
        )
    }

    private func chips(_ family: SessionChatToolChipFamily, _ prefix: String, _ count: Int) -> [SessionChatToolChipBlock] {
        (1...count).map { chip(family, "\(prefix)\($0)") }
    }

    @Test func chipRow_familyLimits_changeCommandThree_othersUnlimited() {
        #expect(SessionChatToolChipFamily.change.visibleLimit == 3)
        #expect(SessionChatToolChipFamily.command.visibleLimit == 3)
        #expect(SessionChatToolChipFamily.explore.visibleLimit == nil)
        #expect(SessionChatToolChipFamily.agent.visibleLimit == nil)
        #expect(SessionChatToolChipFamily.skill.visibleLimit == nil)

        let row = SessionChatTimelineBlocks.chipRow(
            chips(.explore, "e", 2) + chips(.change, "c", 4) + chips(.command, "b", 4) + chips(.agent, "a", 3) + chips(.skill, "s", 4)
        )
        #expect(row.visible.map(\.label) == ["e1", "e2", "c1", "c2", "c3", "b1", "b2", "b3", "a1", "a2", "a3", "s1", "s2", "s3", "s4"])
        #expect(row.hiddenCount == 2)
    }

    @Test func chipRow_attentionChipsNeverHiddenAndNotCounted() {
        let row = SessionChatTimelineBlocks.chipRow(
            [
                chip(.command, "b1", status: .error),
                chip(.command, "b2", status: .running),
                chip(.command, "b3"),
                chip(.command, "b4", status: .pending),
                chip(.command, "b5"),
                chip(.command, "b6"),
                chip(.command, "b7"),
                chip(.command, "b8"),
            ]
        )
        #expect(row.visible.map(\.label) == ["b1", "b2", "b3", "b4", "b5", "b6"])
        #expect(row.hiddenCount == 2)
    }

    @Test func chipRow_noParagraphCeiling() {
        let input = chips(.change, "c", 3) + chips(.command, "b", 3) + chips(.agent, "a", 4) + chips(.skill, "s", 4)
        let row = SessionChatTimelineBlocks.chipRow(input)
        #expect(row.visible == input)
        #expect(row.hiddenCount == 0)
    }

    @Test func chipRow_singleHiddenChipShowsEverything() {
        let fourChanges = chips(.change, "c", 4)
        let row = SessionChatTimelineBlocks.chipRow(fourChanges)
        #expect(row.visible == fourChanges)
        #expect(row.hiddenCount == 0)
    }

    @Test func chipRow_oneExpanderCountsAllFamilies() {
        let input = chips(.change, "c", 5) + chips(.command, "b", 5)
        let row = SessionChatTimelineBlocks.chipRow(input)
        #expect(row.visible.map(\.label) == ["c1", "c2", "c3", "b1", "b2", "b3"])
        #expect(row.hiddenCount == 4)
    }

    @Test func chipRow_expandedThroughShowsChipsAtTapTimeAndCollapsesLaterArrivals() {
        let atTap = (1...5).map { chip(.command, "b\($0)", arrivalIndex: $0 - 1) }
        let expanded = SessionChatTimelineBlocks.chipRow(atTap, expandedThrough: atTap.count)
        #expect(expanded.visible == atTap)
        #expect(expanded.hiddenCount == 0)

        // Five more commands arrive after the tap: the first five stay, the
        // new ones get their own limit of three and a fresh `+2 more`.
        let later = atTap + (6...10).map { chip(.command, "b\($0)", arrivalIndex: $0 - 1) }
        let row = SessionChatTimelineBlocks.chipRow(later, expandedThrough: atTap.count)
        #expect(row.visible.map(\.label) == ["b1", "b2", "b3", "b4", "b5", "b6", "b7", "b8"])
        #expect(row.hiddenCount == 2)
    }

    @Test func chipRow_expandedThroughFollowsArrivalOrderNotRowOrder() {
        // Family order puts the later Change chip ahead of earlier Commands in
        // the row; expansion still follows arrival order.
        let input = [chip(.change, "c1", arrivalIndex: 4)]
            + (1...4).map { chip(.command, "b\($0)", arrivalIndex: $0 - 1) }
            + (5...9).map { chip(.command, "b\($0)", arrivalIndex: $0) }
        let row = SessionChatTimelineBlocks.chipRow(input, expandedThrough: 4)
        #expect(row.visible.map(\.label) == ["c1", "b1", "b2", "b3", "b4", "b5", "b6", "b7"])
        #expect(row.hiddenCount == 2)
    }

    // MARK: - Explore summary

    @Test func chipTimeline_twoOrMoreExploreToolsFoldIntoOneSummaryChip() throws {
        let blocks = okBlocks(
            parts: [
                text("t1", "Looking"),
                .tool(ToolPart(id: "r1", toolName: "read", status: .completed, input: ["filePath": .string("/a/b.swift")])),
                .tool(ToolPart(id: "r2", toolName: "read", status: .completed, input: ["filePath": .string("/a/c.swift")])),
                .tool(ToolPart(id: "g1", toolName: "grep", status: .running, input: ["pattern": .string("foo")])),
                .tool(ToolPart(id: "b1", toolName: "bash", status: .completed, input: ["command": .string("ls")])),
            ],
            chipTimeline: true
        )
        let step = try #require(paragraph(blocks.single))
        let summary = try #require(step.exploreSummary)
        #expect(summary.label == "Exploring \u{00B7} 2 reads \u{00B7} 1 grep")
        #expect(summary.status == .running)
        #expect(summary.subtitle == "/a/b.swift")
        #expect(summary.blockID == PresentationBlockId.toolGroup(["r1", "r2", "g1"]))
        #expect(summary.members.count == 3)
        #expect(step.chips.map(\.label) == ["ls"])
        #expect(blocks[0].blockID == PresentationBlockId.stepParagraph(["t1", "r1", "r2", "g1", "b1"]))
    }

    @Test func chipTimeline_singleExploreToolKeepsItsOwnChip() throws {
        let blocks = okBlocks(
            parts: [
                .tool(ToolPart(id: "r1", toolName: "read", status: .completed, input: ["filePath": .string("/a/b.swift")])),
                .tool(ToolPart(id: "b1", toolName: "bash", status: .completed, input: ["command": .string("ls")])),
            ],
            chipTimeline: true
        )
        let step = try #require(paragraph(blocks.single))
        #expect(step.exploreSummary == nil)
        #expect(step.chips.map(\.label) == ["b.swift", "ls"])
    }

    @Test func chipTimeline_failedExploreToolStaysAnAttentionChipOutsideTheSummary() throws {
        let blocks = okBlocks(
            parts: [
                .tool(ToolPart(id: "r1", toolName: "read", status: .completed, input: ["filePath": .string("/a/b.swift")])),
                .tool(ToolPart(id: "r2", toolName: "read", status: .error, error: "ENOENT", input: ["filePath": .string("/a/missing.swift")])),
                .tool(ToolPart(id: "g1", toolName: "grep", status: .completed, input: ["pattern": .string("foo")])),
            ],
            chipTimeline: true
        )
        let step = try #require(paragraph(blocks.single))
        #expect(step.exploreSummary?.label == "Explored \u{00B7} 1 read \u{00B7} 1 grep")
        #expect(step.exploreSummary?.blockID == PresentationBlockId.toolGroup(["r1", "g1"]))
        #expect(step.chips.map(\.label) == ["missing.swift"])
        #expect(step.chips[0].status == .error)
        #expect(step.errorLines == ["missing.swift: ENOENT"])
    }

    @Test func chipTimeline_foldedChipKeepsFirstRunIdAndArrivalIndex() throws {
        let blocks = okBlocks(
            parts: [
                .tool(ToolPart(id: "b1", toolName: "bash", status: .completed, input: ["command": .string("npm lint")])),
                .tool(ToolPart(id: "b2", toolName: "bash", status: .completed, input: ["command": .string("npm test")], output: "run 1")),
                .tool(ToolPart(id: "b3", toolName: "bash", status: .completed, input: ["command": .string("npm test")], output: "run 2")),
            ],
            chipTimeline: true
        )
        let step = try #require(paragraph(blocks.single))
        #expect(step.chips.map(\.id) == ["p:b1", "p:b2"])
        #expect(step.chips[1].card.detailText == "run 2")
        #expect(step.chips.map(\.arrivalIndex) == [0, 1])
    }

    // MARK: - Repeat folding

    @Test func chipTimeline_identicalCompletedRepeatsFoldKeepingLastCard() {
        let blocks = okBlocks(
            parts: [
                .tool(ToolPart(id: "b1", toolName: "bash", status: .completed, input: ["command": .string("npm test")], output: "run 1")),
                .tool(ToolPart(id: "b2", toolName: "bash", status: .completed, input: ["command": .string("npm test")], output: "run 2")),
                .tool(ToolPart(id: "b3", toolName: "bash", status: .completed, input: ["command": .string("npm test")], output: "run 3")),
            ],
            chipTimeline: true
        )
        guard let paragraph = paragraph(blocks.single) else { return }
        #expect(paragraph.chips.count == 1)
        #expect(paragraph.chips[0].repeatCount == 3)
        #expect(paragraph.chips[0].card.detailText == "run 3")
        #expect(paragraph.chips[0].displayTrailingText == "\u{00D7}3")
        // Block identity still covers every folded part.
        #expect(blocks[0].blockID == PresentationBlockId.stepParagraph(["b1", "b2", "b3"]))
        #expect(paragraph.id == PresentationBlockId.stepParagraphIdentity("b1"))
    }

    @Test func chipTimeline_failedChipBreaksRepeatRun() {
        let blocks = okBlocks(
            parts: [
                .tool(ToolPart(id: "b1", toolName: "bash", status: .completed, input: ["command": .string("npm test")])),
                .tool(ToolPart(id: "b2", toolName: "bash", status: .completed, input: ["command": .string("npm test")])),
                .tool(ToolPart(id: "b3", toolName: "bash", status: .error, error: "exit 1", input: ["command": .string("npm test")])),
                .tool(ToolPart(id: "b4", toolName: "bash", status: .error, error: "exit 1", input: ["command": .string("npm test")])),
                .tool(ToolPart(id: "b5", toolName: "bash", status: .completed, input: ["command": .string("npm test")])),
                .tool(ToolPart(id: "b6", toolName: "bash", status: .completed, input: ["command": .string("npm test")])),
                .tool(ToolPart(id: "b7", toolName: "bash", status: .running, input: ["command": .string("npm test")])),
                .tool(ToolPart(id: "b8", toolName: "bash", status: .running, input: ["command": .string("npm test")])),
            ],
            chipTimeline: true
        )
        guard let paragraph = paragraph(blocks.single) else { return }
        #expect(paragraph.chips.map(\.status) == [.completed, .error, .error, .completed, .running, .running])
        #expect(paragraph.chips.map(\.repeatCount) == [2, 1, 1, 2, 1, 1])
        #expect(paragraph.errorLines.count == 2)
    }

    @Test func foldRepeatedChips_differentLabelOrKindBreaksRun() {
        let folded = SessionChatTimelineBlocks.foldRepeatedChips([
            chip(.explore, "a.swift"),
            chip(.explore, "b.swift"),
            chip(.explore, "b.swift"),
            chip(.change, "b.swift"),
            chip(.command, "b.swift"),
        ])
        #expect(folded.map(\.label) == ["a.swift", "b.swift", "b.swift", "b.swift"])
        #expect(folded.map(\.repeatCount) == [1, 2, 1, 1])
    }

    @Test func chipTimeline_foldedEditsSumDiffCountsAndReadDoesNotFoldWithEdit() {
        func edit(_ id: String, additions: Int, deletions: Int) -> MessagePart {
            .tool(
                ToolPart(
                    id: id,
                    toolName: "edit",
                    status: .completed,
                    input: ["filePath": .string("dir/a.swift")],
                    metadata: ["filediff": .dictionary([
                        "file": .string("dir/a.swift"),
                        "additions": .int(additions),
                        "deletions": .int(deletions),
                    ])]
                )
            )
        }
        let blocks = okBlocks(
            parts: [
                .tool(ToolPart(id: "r1", toolName: "read", status: .completed, input: ["filePath": .string("dir/a.swift")])),
                edit("e1", additions: 3, deletions: 1),
                edit("e2", additions: 4, deletions: 2),
            ],
            chipTimeline: true
        )
        guard let paragraph = paragraph(blocks.single) else { return }
        #expect(paragraph.chips.map(\.family) == [.explore, .change])
        #expect(paragraph.chips.map(\.repeatCount) == [1, 2])
        #expect(paragraph.chips[1].additions == 7)
        #expect(paragraph.chips[1].deletions == 3)
        #expect(paragraph.chips[1].card.additions == 4)
    }

    @Test func chipTimeline_executeChipUsesShortenedLabel() {
        let command = "cd /Users/ivan/repo && npm test"
        let blocks = okBlocks(
            parts: [
                .tool(ToolPart(id: "x1", toolName: "execute", status: .completed, input: ["command": .string(command)])),
            ],
            chipTimeline: true
        )
        guard let paragraph = paragraph(blocks.single) else { return }
        #expect(paragraph.chips.map(\.label) == ["npm test"])
    }

    @Test func displayTrailingText_combinesRepeatCountWithExistingText() {
        let base = SessionChatToolChipBlock(
            id: "p:b1",
            kind: .command,
            family: .command,
            label: "npm test",
            status: .completed,
            trailingText: "12s",
            card: SessionChatToolCardBlock(toolName: "bash", status: .completed, title: "Shell")
        )
        #expect(base.displayTrailingText == "12s")
        #expect(base.withRepeatCount(2).displayTrailingText == "\u{00D7}2 · 12s")
        #expect(chip(.command, "x").displayTrailingText == nil)
    }

    // MARK: - Paragraph identity

    @Test func chipTimeline_paragraphIdentityStableWhileToolsStreamIn() {
        let early = okBlocks(
            parts: [
                text("t1", "Looking"),
                .tool(ToolPart(id: "r1", toolName: "read", status: .completed, input: ["filePath": .string("a.swift")])),
            ],
            chipTimeline: true
        )
        let later = okBlocks(
            parts: [
                text("t1", "Looking"),
                .tool(ToolPart(id: "r1", toolName: "read", status: .completed, input: ["filePath": .string("a.swift")])),
                .tool(ToolPart(id: "r2", toolName: "read", status: .completed, input: ["filePath": .string("b.swift")])),
            ],
            chipTimeline: true
        )
        guard let first = paragraph(early.single), let second = paragraph(later.single) else { return }
        #expect(early[0].blockID != later[0].blockID)
        #expect(first.id == second.id)
        #expect(first.id == PresentationBlockId.stepParagraphIdentity("t1"))
    }

    @Test func chipTimeline_v2ReadPath_labelsChipAndExploreMembers() {
        func read(_ id: String, _ path: String) -> MessagePart {
            .tool(ToolPart(id: id, toolName: "read", status: .completed, input: ["path": .string(path)], output: "body of \(path)"))
        }
        guard let lone = paragraph(okBlocks(parts: [read("r1", "src/app.ts")], chipTimeline: true).single) else { return }
        #expect(lone.chips.map(\.label) == ["app.ts"])

        guard let folded = paragraph(
            okBlocks(parts: [read("r1", "README.md"), read("r2", "src/app.ts")], chipTimeline: true).single
        ) else { return }
        let members = folded.exploreSummary?.members ?? []
        #expect(members.map(\.subtitle) == ["README.md", "src/app.ts"])
        #expect(members.map(\.detailText) == ["body of README.md", "body of src/app.ts"])
    }

    @Test func chipTimeline_customTool_labelsFromTitleInputAndLiveProgress() {
        let running = ToolPart(
            id: "c1",
            toolName: "sim_task",
            status: .running,
            title: "Run migrations \u{00B7} step 3/6",
            input: ["title": .string("Run migrations")]
        )
        let failed = ToolPart(
            id: "c2",
            toolName: "sim_task",
            status: .error,
            title: "Backfill users \u{00B7} step 3/3",
            error: "Backfill aborted: lock timeout",
            input: ["title": .string("Backfill users")]
        )
        let mcp = ToolPart(
            id: "c3",
            toolName: "github_create_issue",
            status: .completed,
            input: ["title": .string("Add handler tests"), "body": .string("src/app.ts has no tests.")],
            output: "Created issue #42"
        )
        let bare = ToolPart(id: "c4", toolName: "sim_task", status: .completed)
        guard let paragraph = paragraph(
            okBlocks(parts: [.tool(running), .tool(failed), .tool(mcp), .tool(bare)], chipTimeline: true).single
        ) else { return }
        #expect(paragraph.chips.map(\.label) == [
            "Run migrations \u{00B7} step 3/6", "Backfill users", "Add handler tests", "sim_task",
        ])
        #expect(paragraph.errorLines == ["Backfill users: Backfill aborted: lock timeout"])
        // The tool name stays the detail sheet title.
        #expect(paragraph.chips[2].card.title == "Called github_create_issue")
        #expect(paragraph.chips[2].card.subtitle == "Add handler tests")
        #expect(paragraph.chips[2].card.detailText == "Created issue #42")
    }

    @Test func chipTimeline_questionChipSaysAskedQuestions() {
        let question = ToolPart(
            id: "q1",
            toolName: "question",
            status: .completed,
            input: [
                "questions": .array([
                    .dictionary(["question": .string("Which database?")]),
                    .dictionary(["question": .string("Which region?")]),
                ]),
            ],
            metadata: ["answers": .array([.array([.string("Postgres")]), .array([.string("eu-west")])])]
        )
        guard let paragraph = paragraph(okBlocks(parts: [.tool(question)], chipTimeline: true).single) else { return }
        #expect(paragraph.chips.map(\.label) == ["Asked 2 questions"])
    }

    @Test func build_shellNonZeroExit_isFailedWithExitCode() {
        let shell = ToolPart(
            id: "s1",
            toolName: "shell",
            status: .completed,
            input: ["command": .string("echo 'about to fail' && exit 3")],
            output: "about to fail\nCommand exited with code 3.",
            metadata: ["exit": .int(3)]
        )
        let ok = ToolPart(
            id: "s2",
            toolName: "shell",
            status: .completed,
            input: ["command": .string("echo 'about to fail' && exit 3")],
            metadata: ["exit": .int(0)]
        )
        guard case .toolCard(_, let card)? = okBlocks(parts: [.tool(shell)]).single else {
            Issue.record("Expected tool card")
            return
        }
        #expect(card.status == .error)
        #expect(card.errorText == "Exited with code 3")
        #expect(card.detailText == "about to fail\nCommand exited with code 3.")

        guard let paragraph = paragraph(okBlocks(parts: [.tool(shell), .tool(ok)], chipTimeline: true).single) else { return }
        #expect(paragraph.chips.map(\.status) == [.error, .completed])
        #expect(paragraph.errorLines == ["echo 'about to fail' && exit 3: Exited with code 3"])
    }

    @Test func chipTimeline_inFlightChipsCarryTheirStartForElapsedTime() {
        let agent = ToolPart(
            id: "a1",
            toolName: "subagent",
            status: .running,
            input: ["agent": .string("explore"), "description": .string("Scan the project")],
            timeStartEpochMs: 1_000
        )
        let held = ToolPart(id: "h1", toolName: "sim_task", status: .running, timeStartEpochMs: 2_000)
        let done = ToolPart(id: "d1", toolName: "sim_task", status: .completed, timeStartEpochMs: 3_000)
        guard let paragraph = paragraph(
            okBlocks(parts: [.tool(agent), .tool(held), .tool(done)], chipTimeline: true).single
        ) else { return }
        let starts = Dictionary(uniqueKeysWithValues: paragraph.chips.map { ($0.id, $0.runningSinceEpochMs) })
        #expect(starts[PresentationBlockId.direct("a1")] == 1_000)
        #expect(starts[PresentationBlockId.direct("h1")] == 2_000)
        #expect(starts[PresentationBlockId.direct("d1")] == .some(nil))
    }

    @Test func chipTimeline_writeOfNewFileCountsItsLines() {
        let write = ToolPart(
            id: "w1",
            toolName: "write",
            status: .completed,
            input: [
                "path": .string("notes/todo.md"),
                "content": .string("# Todo\n\n- [ ] Add handler tests\n- [ ] Wire CI\n"),
            ]
        )
        let overwrite = ToolPart(
            id: "w2",
            toolName: "write",
            status: .completed,
            input: ["path": .string("README.md"), "content": .string("# Title\n")],
            metadata: ["exists": .bool(true)]
        )
        guard let paragraph = paragraph(okBlocks(parts: [.tool(write), .tool(overwrite)], chipTimeline: true).single) else {
            return
        }
        #expect(paragraph.chips[0].label == "todo.md")
        #expect(paragraph.chips[0].additions == 4)
        #expect(paragraph.chips[0].deletions == nil)
        #expect(paragraph.chips[1].additions == nil)
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
