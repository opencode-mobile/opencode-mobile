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
        #expect(tool.title == "Question")
        #expect(tool.subtitle == "Which option?")
        #expect(tool.detailText == nil)
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
        #expect(tool.title == "Read")
        #expect(tool.subtitle == "src/Main.kt")
        #expect(tool.detailText == nil)
    }

    @Test func build_contextFallsBackToGenericTitleWhenInputMissing() {
        let blocks = okBlocks(
            parts: [.tool(ToolPart(id: "glob-1", toolName: "glob", status: .completed))]
        )
        guard case .toolCard(_, let tool) = blocks.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "Called glob")
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
        #expect(tool.title == "Grep")
        #expect(tool.subtitle == "Searching…")
    }

    @Test func build_consecutiveContextToolsBecomeToolGroup() {
        let blocks = okBlocks(
            parts: [
                .tool(ToolPart(id: "r1", toolName: "read", status: .completed, input: ["path": .string("a.kt")])),
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
        #expect(group.title == "1 read · 1 grep · 1 glob")
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
        #expect(search.title == "Search")
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
        guard case .toolCard(_, let tool) = blocks.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "explore")
        #expect(tool.subtitle == "Find auth bugs")
        #expect(tool.childSessionId == "ses-child-1")
        #expect(tool.detailText == nil)
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
        guard case .toolCard(_, let tool) = blocks.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "Task")
        #expect(tool.subtitle == "Investigate flaky tests")
        #expect(tool.childSessionId == nil)
        #expect(tool.detailText == nil)
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
        guard case .toolCard(_, let tool) = blocks.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "coder")
        #expect(tool.subtitle == nil)
        #expect(tool.childSessionId == nil)
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
        guard case .toolCard(_, let tool) = blocks.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "researcher")
        #expect(tool.subtitle == "Long research (background)")
        #expect(tool.childSessionId == "ses-bg")
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

    private func okBlocks(
        role: MessageRole = .assistant,
        parts: [MessagePart],
        sourceLocation: SourceLocation = #_sourceLocation
    ) -> [SessionChatTimelineBlock] {
        let result = SessionChatTimelineBlocks.build(role: role, parts: parts)
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
