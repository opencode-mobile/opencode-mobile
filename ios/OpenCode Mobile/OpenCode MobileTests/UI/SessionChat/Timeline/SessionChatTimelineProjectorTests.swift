import Foundation
import Testing
@testable import OpenCode_Mobile

struct SessionChatTimelineProjectorTests {
    @Test func metadataLabel_titleCasesModeModelAndVariant() {
        let label = SessionChatTimelineProjector.metadataLabel(
            mode: "build",
            model: "grok-4.6",
            variant: "high"
        )
        #expect(label == "Build · Grok 4.6 · High")
    }

    @Test func interleavedTextToolText_blockIDsStableAcrossReproject() {
        let parts: [MessagePart] = [
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
        let message = makeMessage(id: "msg-1", parts: parts, text: "Before\nAfter")
        let frame1 = displayFrame(messages: [DisplayMessage(message: message)])
        let first = project(frame1)
        #expect(first.rows.count == 1)
        let blockIDs = first.rows[0].blocks.map(\.blockID)
        #expect(blockIDs == [
            PresentationBlockId.direct("t1"),
            PresentationBlockId.direct("bash-1"),
            PresentationBlockId.direct("t2"),
        ])

        let frame2 = DisplayFrame(
            sessionKey: frame1.sessionKey,
            frameRevision: 2,
            sourceRevision: 2,
            messages: frame1.messages
        )
        let second = project(frame2, previous: first)
        #expect(second.changedRowIDs.isEmpty)
        #expect(second.layoutChangedRowIDs.isEmpty)
        #expect(second.rows[0].blocks.map(\.blockID) == blockIDs)
        #expect(second.rows[0].layoutRevision == first.rows[0].layoutRevision)
    }

    @Test func toolGroupMembershipChange_changesBlockIDAndMarksRowChanged() {
        let twoParts: [MessagePart] = [
            .tool(ToolPart(id: "r1", toolName: "read", status: .completed)),
            .tool(ToolPart(id: "g1", toolName: "grep", status: .completed)),
        ]
        let threeParts: [MessagePart] = [
            .tool(ToolPart(id: "r1", toolName: "read", status: .completed)),
            .tool(ToolPart(id: "g1", toolName: "grep", status: .completed)),
            .tool(ToolPart(id: "l1", toolName: "glob", status: .completed)),
        ]
        let firstMessage = makeMessage(id: "msg-group", parts: twoParts, text: "")
        let secondMessage = makeMessage(id: "msg-group", parts: threeParts, text: "")
        let first = project(displayFrame(messages: [DisplayMessage(message: firstMessage)]))
        #expect(first.rows[0].blocks.map(\.blockID) == [
            PresentationBlockId.toolGroup(["r1", "g1"]),
        ])

        let second = project(
            displayFrame(revision: 2, messages: [DisplayMessage(message: secondMessage)]),
            previous: first
        )
        #expect(second.rows[0].blocks.map(\.blockID) == [
            PresentationBlockId.toolGroup(["r1", "g1", "l1"]),
        ])
        #expect(second.changedRowIDs.contains("msg-group"))
        #expect(second.layoutChangedRowIDs.contains("msg-group"))
        #expect(second.rows[0].layoutRevision == first.rows[0].layoutRevision &+ 1)
    }

    @Test func optimisticThenAuthoritative_reconcilesMatchingSignature() {
        let pending = TimelineRow(
            id: "optimistic-1",
            role: .user,
            text: "Hello",
            metadataLabel: "build · deepseek-v4-flash-free · high · 10:21 AM",
            isPending: true,
            blocks: [
                .text(
                    blockID: PresentationBlockId.messageFallback("optimistic-1"),
                    text: "Hello",
                    renderedMarkdown: nil
                ),
            ],
            contentRevision: ContentRevision.of("Hello"),
            layoutRevision: 1
        )
        let signature = SessionChatTimelineProjector.optimisticSignature(pending)
        let empty = project(displayFrame(messages: []))
        #expect(empty.rows.isEmpty)

        let withOptimistic = project(
            displayFrame(revision: 1, messages: []),
            optimistic: OptimisticInputs(
                pendingRows: [pending],
                baselineSignatureCounts: [signature: 0],
                mergePending: true
            ),
            previous: empty
        )
        #expect(withOptimistic.rows.map(\.id) == ["optimistic-1"])

        let authoritative = makeMessage(
            id: "server-1",
            role: .user,
            parts: [text("u1", "Hello")],
            text: "Hello"
        )
        let reconciled = project(
            displayFrame(revision: 2, messages: [DisplayMessage(message: authoritative)]),
            optimistic: OptimisticInputs(
                pendingRows: [pending],
                baselineSignatureCounts: [signature: 0],
                mergePending: true
            ),
            previous: withOptimistic
        )
        #expect(reconciled.rows.map(\.id) == ["server-1"])
        #expect(reconciled.rows.allSatisfy { !$0.isPending })
        #expect(reconciled.rows[0].metadataLabel == "build · deepseek-v4-flash-free · high · 10:21 AM")
        #expect(reconciled.removedRowIDs.contains("optimistic-1"))
        #expect(reconciled.insertedRowIDs.contains("server-1"))
    }

    @Test func metadataArrivingOnSameUserRow_updatesLabelAndLayout() {
        let sparse = Message(
            id: "msg-user",
            role: .user,
            text: "Hello",
            parts: [text("u1", "Hello")],
            model: nil,
            mode: nil,
            sentAtEpochMs: nil
        )
        let first = SessionChatTimelineProjector.project(
            frame: displayFrame(messages: [DisplayMessage(message: sparse)])
        )
        #expect(first.rows[0].metadataLabel.isEmpty)

        let enriched = Message(
            id: "msg-user",
            role: .user,
            text: "Hello",
            parts: [text("u1", "Hello")],
            model: "deepseek-v4-flash-free",
            variant: "high",
            mode: "build",
            sentAtEpochMs: 1_710_000_000_000
        )
        let second = SessionChatTimelineProjector.project(
            frame: displayFrame(revision: 2, messages: [DisplayMessage(message: enriched)]),
            previous: first
        )
        let expected = SessionChatTimelineProjector.metadataLabel(for: enriched)
        #expect(!expected.isEmpty)
        #expect(second.rows[0].metadataLabel == expected)
        #expect(second.changedRowIDs.contains("msg-user"))
        #expect(second.layoutChangedRowIDs.contains("msg-user"))
    }

    @Test func unchangedContent_retainsLayoutRevisionAndEmptyChangeSets() {
        let message = makeMessage(
            id: "stable",
            parts: [text("t1", "Steady")],
            text: "Steady"
        )
        let first = project(displayFrame(messages: [DisplayMessage(message: message)]))
        let second = project(
            displayFrame(revision: 2, messages: [DisplayMessage(message: message)]),
            previous: first
        )
        #expect(second.changedRowIDs.isEmpty)
        #expect(second.layoutChangedRowIDs.isEmpty)
        #expect(second.insertedRowIDs.isEmpty)
        #expect(second.removedRowIDs.isEmpty)
        #expect(second.rows[0].layoutRevision == first.rows[0].layoutRevision)
        #expect(second.rows[0].contentRevision == first.rows[0].contentRevision)
    }

    @Test func plainToDecorated_advancesLayoutRevisionAndLayoutChangedIDs() {
        let message = makeMessage(
            id: "decorate-me",
            parts: [text("t1", "# Title")],
            text: "# Title"
        )
        let plain = project(displayFrame(messages: [DisplayMessage(message: message)]))
        #expect(plain.rows[0].renderedMarkdown == nil)
        guard case .text(_, _, let plainDoc) = plain.rows[0].blocks[0] else {
            Issue.record("Expected text block")
            return
        }
        #expect(plainDoc == nil)

        let document = NativeMarkdownDocument(
            sourceHash: stableMarkdownSourceHash("# Title"),
            blocks: [.paragraph([.text("Title")])]
        )
        let decoratedDisplay = DisplayMessage(message: message, renderedMarkdown: document)
        let decorated = project(
            displayFrame(revision: 2, messages: [decoratedDisplay]),
            previous: plain
        )
        #expect(decorated.changedRowIDs.contains("decorate-me"))
        #expect(decorated.layoutChangedRowIDs.contains("decorate-me"))
        #expect(decorated.rows[0].layoutRevision == plain.rows[0].layoutRevision &+ 1)
        #expect(decorated.rows[0].renderedMarkdown?.sourceHash == document.sourceHash)
        guard case .text(_, _, let blockDoc) = decorated.rows[0].blocks[0] else {
            Issue.record("Expected text block with markdown")
            return
        }
        #expect(blockDoc?.sourceHash == document.sourceHash)
    }

    @Test func streamingTextGrowth_marksGrewBottomNotLayoutChanged() {
        let firstMessage = makeMessage(
            id: "stream-1",
            parts: [text("t1", "Hi", isStreaming: true)],
            text: "Hi"
        )
        let first = project(displayFrame(messages: [DisplayMessage(message: firstMessage)]))
        let secondMessage = makeMessage(
            id: "stream-1",
            parts: [text("t1", "Hi there", isStreaming: true)],
            text: "Hi there"
        )
        let second = project(
            displayFrame(revision: 2, messages: [DisplayMessage(message: secondMessage)]),
            previous: first
        )
        #expect(second.changedRowIDs.contains("stream-1"))
        #expect(second.layoutChangedRowIDs.isEmpty)
        #expect(second.layoutAffectingRowIDs.isEmpty)
        #expect(second.grewBottomRowIDs == ["stream-1"])
        #expect(second.rows[0].layoutRevision == first.rows[0].layoutRevision)
        #expect(second.rows[0].isStreaming)
    }

    @Test func streamingEnd_marksLayoutChanged() {
        let streamingMessage = makeMessage(
            id: "stream-end",
            parts: [text("t1", "Done", isStreaming: true)],
            text: "Done"
        )
        let streaming = project(displayFrame(messages: [DisplayMessage(message: streamingMessage)]))
        let finalizedMessage = makeMessage(
            id: "stream-end",
            parts: [text("t1", "Done", isStreaming: false)],
            text: "Done"
        )
        let finalized = project(
            displayFrame(revision: 2, messages: [DisplayMessage(message: finalizedMessage)]),
            previous: streaming
        )
        #expect(finalized.changedRowIDs.contains("stream-end"))
        #expect(finalized.layoutChangedRowIDs.contains("stream-end"))
        #expect(finalized.decorateRowIDs.isEmpty)
        #expect(finalized.grewBottomRowIDs.isEmpty)
        #expect(finalized.rows[0].layoutRevision == streaming.rows[0].layoutRevision &+ 1)
        let kinds = TimelineApply.classify(finalized).kinds
        #expect(kinds.contains(.structure))
        #expect(!kinds.contains(.decorate))
    }

    @Test func plainToDecorated_marksDecorateKind() {
        let message = makeMessage(
            id: "decorate-me",
            parts: [text("t1", "# Title")],
            text: "# Title"
        )
        let plain = project(displayFrame(messages: [DisplayMessage(message: message)]))
        let document = NativeMarkdownDocument(
            sourceHash: stableMarkdownSourceHash("# Title"),
            blocks: [.paragraph([.text("Title")])]
        )
        let decoratedDisplay = DisplayMessage(message: message, renderedMarkdown: document)
        let decorated = project(
            displayFrame(revision: 2, messages: [decoratedDisplay]),
            previous: plain
        )
        #expect(decorated.decorateRowIDs == ["decorate-me"])
        let kinds = TimelineApply.classify(decorated).kinds
        #expect(kinds.contains(.decorate))
        #expect(!kinds.contains(.structure))
    }

    @Test func classify_requiredAndGrowBottom_carryMultipleKinds() {
        let firstMessage = makeMessage(
            id: "stream-1",
            parts: [text("t1", "Hi", isStreaming: true)],
            text: "Hi"
        )
        let first = project(displayFrame(messages: [DisplayMessage(message: firstMessage)]))
        let secondMessage = makeMessage(
            id: "stream-1",
            parts: [text("t1", "Hi there", isStreaming: true)],
            text: "Hi there"
        )
        let grew = project(
            displayFrame(revision: 2, messages: [DisplayMessage(message: secondMessage)]),
            previous: first
        ).withRequired(true)
        let kinds = TimelineApply.classify(grew).kinds
        #expect(kinds == [.required, .growBottom])
    }

    @Test func displayFrameBuilder_attachesBlockDecorationCacheHits() {
        let cache = BlockDecorationCache(maxEntries: 8)
        let plain = "# Cached"
        let blockID = PresentationBlockId.direct("t1")
        let document = NativeMarkdownDocument(
            sourceHash: 42,
            blocks: [.paragraph([.text("Cached")])]
        )
        cache.put(
            sessionKey: "session-a",
            blockID: blockID,
            contentRevision: ContentRevision.of(plain),
            isStreaming: false,
            document: document
        )
        let message = makeMessage(
            id: "cached-msg",
            parts: [text("t1", plain)],
            text: plain
        )
        let blocks = DisplayFrameBuilder.attachCachedBlockMarkdown(
            [
                .text(blockID: blockID, text: plain, renderedMarkdown: nil),
            ],
            sessionKey: "session-a",
            isStreaming: false,
            cache: cache
        )
        guard case .text(_, _, let attached) = blocks[0] else {
            Issue.record("Expected text block")
            return
        }
        #expect(attached?.sourceHash == 42)

        let frame = DisplayFrameBuilder.build(
            sessionKey: "session-a",
            frameRevision: 1,
            sourceRevision: 1,
            displayMessages: [DisplayMessage(message: message, renderedMarkdown: nil)]
        )
        // Shared cache is separate; builder default uses .shared — ensure sole-text
        // bridge remains nil when message has no root markdown and shared misses.
        #expect(frame.messages[0].renderedMarkdown == nil)
    }

    @Test func displayFrameBuilder_keepsEmptyStreamingAssistant() {
        let message = makeMessage(
            id: "msg-2",
            parts: [text("prt-1", "", isStreaming: true)],
            text: ""
        )
        let frame = displayFrame(messages: [DisplayMessage(message: message)])
        #expect(frame.messages.map(\.id) == ["msg-2"])
        #expect(frame.messages[0].blocks.map(\.blockID) == [PresentationBlockId.messageFallback("msg-2")])
    }

    @Test func contentRevision_usesTextHashAndLength() {
        let revision = ContentRevision.of("abc")
        #expect(revision.textLength == 3)
        #expect(revision.textHash == stableMarkdownSourceHash("abc"))
    }

    @Test func displayFrameBuilder_sortsOldestToNewestWithoutAnAssistant() {
        let later = userMessage(id: "msg-2", text: "Later", sentAtEpochMs: 2_000)
        let earlier = userMessage(id: "msg-1", text: "Earlier", sentAtEpochMs: 1_000)
        let frame = displayFrame(messages: [
            DisplayMessage(message: later),
            DisplayMessage(message: earlier),
        ])
        #expect(frame.messages.map(\.id) == ["msg-1", "msg-2"])
    }

    @Test func displayFrameBuilder_keepsStoreUserBeforeAssistantWhenUserTimestampIsLater() {
        let prompt = userMessage(
            id: "msg-photo",
            text: "What is it",
            sentAtEpochMs: 3_000,
            parts: [
                text("u1", "What is it"),
                .file(filePart(id: "server-file", dataURL: "https://example.test/photo.jpg")),
            ]
        )
        let assistant = makeMessage(
            id: "msg-asst",
            parts: [text("a1", "A photo of a cat", isStreaming: true)],
            text: "A photo of a cat",
            sentAtEpochMs: 1_000
        )
        let hey = userMessage(id: "msg-hey", text: "hey", sentAtEpochMs: 2_000)
        let frame = displayFrame(messages: [
            DisplayMessage(message: prompt),
            DisplayMessage(message: assistant),
            DisplayMessage(message: hey),
        ])
        #expect(frame.messages.map(\.id) == ["msg-photo", "msg-asst", "msg-hey"])
    }

    @Test func optimisticFile_reconcilesWhenLocalDataURLDiffersFromServer() {
        let pendingFile = filePart(id: "local-file", dataURL: "data:image/jpeg;base64,abc")
        let pending = TimelineRow(
            id: "optimistic-photo",
            role: .user,
            text: "What is it",
            metadataLabel: "build · 1:21 PM",
            isPending: true,
            blocks: [
                .text(
                    blockID: PresentationBlockId.messageFallback("optimistic-photo"),
                    text: "What is it",
                    renderedMarkdown: nil
                ),
                .attachment(blockID: PresentationBlockId.direct("local-file"), file: pendingFile),
            ],
            contentRevision: ContentRevision.of("What is it"),
            layoutRevision: 1
        )
        let signature = SessionChatTimelineProjector.optimisticSignature(pending)
        let withOptimistic = project(
            displayFrame(messages: []),
            optimistic: OptimisticInputs(
                pendingRows: [pending],
                baselineSignatureCounts: [signature: 0],
                mergePending: true
            )
        )
        #expect(withOptimistic.rows.map(\.id) == ["optimistic-photo"])

        let assistant = makeMessage(
            id: "msg-asst",
            parts: [text("a1", "Working", isStreaming: true)],
            text: "Working"
        )
        let serverUser = userMessage(
            id: "msg-photo",
            text: "What is it",
            parts: [
                text("u1", "What is it"),
                .file(filePart(id: "server-file", dataURL: "https://example.test/photo.jpg")),
            ]
        )
        let reconciled = project(
            displayFrame(revision: 2, messages: [
                DisplayMessage(message: serverUser),
                DisplayMessage(message: assistant),
            ]),
            optimistic: OptimisticInputs(
                pendingRows: [pending],
                baselineSignatureCounts: [signature: 0],
                mergePending: true
            ),
            previous: withOptimistic
        )
        #expect(reconciled.rows.map(\.id) == ["msg-photo", "msg-asst"])
        #expect(reconciled.rows.allSatisfy { !$0.isPending })
        #expect(reconciled.removedRowIDs.contains("optimistic-photo"))
    }

    @Test func optimisticFile_reconcilesWhenLiveInboxOmitsFile() {
        let pendingFile = filePart(id: "local-file", dataURL: "data:image/jpeg;base64,abc")
        let pending = TimelineRow(
            id: "optimistic-photo",
            role: .user,
            text: "What",
            metadataLabel: "Build · MiMo V2.5 Free · 7:58 PM",
            isPending: true,
            blocks: [
                .text(
                    blockID: PresentationBlockId.messageFallback("optimistic-photo"),
                    text: "What",
                    renderedMarkdown: nil
                ),
                .attachment(blockID: PresentationBlockId.direct("local-file"), file: pendingFile),
            ],
            contentRevision: ContentRevision.of("What"),
            layoutRevision: 1
        )
        let signature = SessionChatTimelineProjector.optimisticSignature(pending)
        let withOptimistic = project(
            displayFrame(messages: []),
            optimistic: OptimisticInputs(
                pendingRows: [pending],
                baselineSignatureCounts: [signature: 0],
                mergePending: true
            )
        )
        let serverUser = userMessage(id: "msg-photo", text: "What", parts: [text("u1", "What")])
        let reconciled = project(
            displayFrame(revision: 2, messages: [DisplayMessage(message: serverUser)]),
            optimistic: OptimisticInputs(
                pendingRows: [pending],
                baselineSignatureCounts: [signature: 0],
                mergePending: true
            ),
            previous: withOptimistic
        )
        #expect(reconciled.rows.map(\.id) == ["msg-photo"])
        #expect(reconciled.rows.allSatisfy { !$0.isPending })
        #expect(reconciled.removedRowIDs.contains("optimistic-photo"))
        #expect(reconciled.rows[0].hasAttachments)
        #expect(reconciled.rows[0].metadataLabel == "Build · MiMo V2.5 Free · 7:58 PM")
    }

    @Test func optimisticFile_reconcilesOntoNewestTextOnlyWhenHistorySharesText() {
        let previous = userMessage(id: "msg-old", text: "What", sentAtEpochMs: 1)
        let history = project(displayFrame(messages: [DisplayMessage(message: previous)]))
        let baseline = SessionChatTimelineProjector.signatureCounts(history.rows)
        let pendingFile = filePart(id: "local-file", dataURL: "data:image/jpeg;base64,abc")
        let pending = TimelineRow(
            id: "optimistic-photo",
            role: .user,
            text: "What",
            metadataLabel: "Build · 7:58 PM",
            isPending: true,
            blocks: [
                .text(
                    blockID: PresentationBlockId.messageFallback("optimistic-photo"),
                    text: "What",
                    renderedMarkdown: nil
                ),
                .attachment(blockID: PresentationBlockId.direct("local-file"), file: pendingFile),
            ],
            contentRevision: ContentRevision.of("What"),
            layoutRevision: 1
        )
        let withOptimistic = project(
            displayFrame(messages: [DisplayMessage(message: previous)]),
            optimistic: OptimisticInputs(
                pendingRows: [pending],
                baselineSignatureCounts: baseline,
                mergePending: true
            )
        )
        #expect(withOptimistic.rows.map(\.id) == ["msg-old", "optimistic-photo"])

        let live = userMessage(id: "msg-new", text: "What", sentAtEpochMs: 2)
        let reconciled = project(
            displayFrame(revision: 2, messages: [
                DisplayMessage(message: previous),
                DisplayMessage(message: live),
            ]),
            optimistic: OptimisticInputs(
                pendingRows: [pending],
                baselineSignatureCounts: baseline,
                mergePending: true
            ),
            previous: withOptimistic
        )
        #expect(reconciled.rows.map(\.id) == ["msg-old", "msg-new"])
        #expect(!reconciled.rows[0].hasAttachments)
        #expect(reconciled.rows[1].hasAttachments)
        #expect(reconciled.rows[1].metadataLabel == "Build · 7:58 PM")
        #expect(reconciled.removedRowIDs.contains("optimistic-photo"))
    }

    @Test func unmatchedPendingUser_insertsBeforeStreamingAssistant() {
        let pending = TimelineRow(
            id: "optimistic-photo",
            role: .user,
            text: "What is it",
            metadataLabel: "",
            isPending: true,
            blocks: [
                .text(
                    blockID: PresentationBlockId.messageFallback("optimistic-photo"),
                    text: "What is it",
                    renderedMarkdown: nil
                ),
            ],
            contentRevision: ContentRevision.of("What is it"),
            layoutRevision: 1
        )
        let assistant = makeMessage(
            id: "msg-asst",
            parts: [text("a1", "Working", isStreaming: true)],
            text: "Working"
        )
        let hey = userMessage(id: "msg-hey", text: "hey")
        let merged = project(
            displayFrame(messages: [
                DisplayMessage(message: assistant),
                DisplayMessage(message: hey),
            ]),
            optimistic: OptimisticInputs(
                pendingRows: [pending],
                baselineSignatureCounts: [:],
                mergePending: true
            )
        )
        #expect(merged.rows.map(\.id) == ["optimistic-photo", "msg-asst", "msg-hey"])
    }

    @Test func streamingMarkdownSourceHashChange_isGrowBottomNotLayout() {
        let firstMessage = makeMessage(
            id: "msg-1",
            parts: [text("prt-1", "Hi", isStreaming: true)],
            text: "Hi"
        )
        let first = project(
            displayFrame(
                messages: [
                    DisplayMessage(
                        message: firstMessage,
                        renderedMarkdown: NativeMarkdownDocument(sourceHash: 1, blocks: [])
                    ),
                ]
            )
        )
        let secondMessage = makeMessage(
            id: "msg-1",
            parts: [text("prt-1", "Hi there", isStreaming: true)],
            text: "Hi there"
        )
        let second = project(
            displayFrame(
                revision: 2,
                messages: [
                    DisplayMessage(
                        message: secondMessage,
                        renderedMarkdown: NativeMarkdownDocument(sourceHash: 2, blocks: [])
                    ),
                ]
            ),
            previous: first
        )
        #expect(second.changedRowIDs == ["msg-1"])
        #expect(second.layoutChangedRowIDs.isEmpty)
        #expect(second.grewBottomRowIDs == ["msg-1"])
        #expect(second.layoutAffectingRowIDs.isEmpty)
        #expect(second.rows[0].layoutRevision == first.rows[0].layoutRevision)
        let kinds = TimelineApply.classify(second).kinds
        #expect(kinds.contains(.growBottom))
        #expect(!kinds.contains(.structure))
    }

    @Test func streamingReasoningGrowth_isContentNotLayout() {
        let firstMessage = makeMessage(
            id: "msg-1",
            parts: [.reasoning(ReasoningPart(id: "prt-r", text: "Think", isStreaming: true))],
            text: ""
        )
        let first = project(displayFrame(messages: [DisplayMessage(message: firstMessage)]))
        let secondMessage = makeMessage(
            id: "msg-1",
            parts: [.reasoning(ReasoningPart(id: "prt-r", text: "Think harder", isStreaming: true))],
            text: ""
        )
        let second = project(
            displayFrame(revision: 2, messages: [DisplayMessage(message: secondMessage)]),
            previous: first
        )
        #expect(second.changedRowIDs == ["msg-1"])
        #expect(second.layoutChangedRowIDs.isEmpty)
        #expect(second.layoutAffectingRowIDs.isEmpty)
        #expect(second.rows[0].layoutRevision == first.rows[0].layoutRevision)
        let kinds = TimelineApply.classify(second).kinds
        #expect(kinds.contains(.content))
        #expect(!kinds.contains(.structure))
    }

    @Test func reasoningComplete_isContentNotLayout() {
        let firstMessage = makeMessage(
            id: "msg-1",
            parts: [.reasoning(ReasoningPart(id: "prt-r", text: "Think", isStreaming: true))],
            text: ""
        )
        let first = project(displayFrame(messages: [DisplayMessage(message: firstMessage)]))
        let secondMessage = makeMessage(
            id: "msg-1",
            parts: [
                .reasoning(
                    ReasoningPart(
                        id: "prt-r",
                        text: "Think",
                        isStreaming: false,
                        timeStartEpochMs: 1,
                        timeEndEpochMs: 2
                    )
                ),
            ],
            text: ""
        )
        let second = project(
            displayFrame(revision: 2, messages: [DisplayMessage(message: secondMessage)]),
            previous: first
        )
        #expect(second.changedRowIDs == ["msg-1"])
        #expect(second.layoutChangedRowIDs.isEmpty)
        #expect(second.rows[0].layoutRevision == first.rows[0].layoutRevision)
        let kinds = TimelineApply.classify(second).kinds
        #expect(kinds.contains(.content))
        #expect(!kinds.contains(.structure))
    }

    @Test func toolTitleChange_isContentNotLayout() {
        let firstMessage = makeMessage(
            id: "msg-1",
            parts: [
                .tool(ToolPart(id: "prt-1", toolName: "task", status: .running, title: "Working…")),
            ],
            text: ""
        )
        let secondMessage = makeMessage(
            id: "msg-1",
            parts: [
                .tool(
                    ToolPart(
                        id: "prt-1",
                        toolName: "task",
                        status: .running,
                        title: "explore",
                        input: ["agent": .string("explore")]
                    )
                ),
            ],
            text: ""
        )
        let first = project(displayFrame(messages: [DisplayMessage(message: firstMessage)]))
        let second = project(
            displayFrame(revision: 2, messages: [DisplayMessage(message: secondMessage)]),
            previous: first
        )
        #expect(second.changedRowIDs == ["msg-1"])
        #expect(second.layoutChangedRowIDs.isEmpty)
        #expect(second.layoutAffectingRowIDs.isEmpty)
        #expect(second.rows[0].layoutRevision == first.rows[0].layoutRevision)
    }

    @Test func project_excludesQueuePendingUserMessage() {
        let message = userMessage(
            id: "msg-user",
            text: "Held until idle"
        ).withPickupState(isWaitingForPickup: true, followUpDelivery: .queue)
        let frame = project(displayFrame(messages: [DisplayMessage(message: message)]))
        #expect(frame.rows.isEmpty)
    }

    @Test func project_includesSteerPendingUserMessage() {
        let message = userMessage(
            id: "msg-user",
            text: "Waiting to steer"
        ).withPickupState(isWaitingForPickup: true, followUpDelivery: .steer)
        let frame = project(displayFrame(messages: [DisplayMessage(message: message)]))
        #expect(frame.rows.count == 1)
        #expect(frame.rows[0].text == "Waiting to steer")
        #expect(frame.rows[0].pendingDelivery == .steer)
    }

    @Test func optimisticThenQueuePendingEcho_dropsOptimisticAndHidesEcho() {
        let pending = TimelineRow(
            id: "optimistic-1",
            role: .user,
            text: "Hello",
            metadataLabel: "build · deepseek-v4-flash-free · high · 10:21 AM",
            isPending: true,
            blocks: [
                .text(
                    blockID: PresentationBlockId.messageFallback("optimistic-1"),
                    text: "Hello",
                    renderedMarkdown: nil
                ),
            ],
            contentRevision: ContentRevision.of("Hello"),
            layoutRevision: 1
        )
        let earlier = userMessage(id: "msg-user-0", text: "Earlier")
        let signature = SessionChatTimelineProjector.optimisticSignature(pending)
        let withOptimistic = project(
            displayFrame(revision: 1, messages: [DisplayMessage(message: earlier)]),
            optimistic: OptimisticInputs(
                pendingRows: [pending],
                baselineSignatureCounts: [signature: 0],
                mergePending: true
            )
        )
        #expect(withOptimistic.rows.map(\.id) == ["msg-user-0", "optimistic-1"])

        let queuePending = userMessage(
            id: "msg-user-1",
            text: "Hello"
        ).withPickupState(isWaitingForPickup: true, followUpDelivery: .queue)
        let reconciled = project(
            displayFrame(
                revision: 2,
                messages: [
                    DisplayMessage(message: earlier),
                    DisplayMessage(message: queuePending),
                ]
            ),
            optimistic: OptimisticInputs(
                pendingRows: [pending],
                baselineSignatureCounts: [signature: 0],
                mergePending: true
            ),
            previous: withOptimistic
        )
        #expect(reconciled.rows.map(\.id) == ["msg-user-0"])
        #expect(!reconciled.rows.contains { $0.id == "optimistic-1" })
        #expect(!reconciled.rows.contains { $0.id == "msg-user-1" })
    }

    // MARK: - Helpers

    private func project(
        _ frame: DisplayFrame,
        optimistic: OptimisticInputs = .none,
        previous: TimelineFrame? = nil
    ) -> TimelineFrame {
        SessionChatTimelineProjector.project(
            frame: frame,
            optimistic: optimistic,
            previous: previous,
            metadataLabel: { _ in "" }
        )
    }

    private func displayFrame(
        revision: UInt64 = 1,
        messages: [DisplayMessage]
    ) -> DisplayFrame {
        DisplayFrameBuilder.build(
            sessionKey: "session-a",
            frameRevision: revision,
            sourceRevision: revision,
            displayMessages: messages
        )
    }

    private func makeMessage(
        id: String,
        role: MessageRole = .assistant,
        parts: [MessagePart],
        text: String,
        sentAtEpochMs: Int64 = 1
    ) -> Message {
        Message(
            id: id,
            role: role,
            text: text,
            parts: parts,
            model: nil,
            mode: nil,
            sentAtEpochMs: sentAtEpochMs
        )
    }

    private func userMessage(
        id: String,
        text: String,
        sentAtEpochMs: Int64? = 1,
        parts: [MessagePart]? = nil
    ) -> Message {
        Message(
            id: id,
            role: .user,
            text: text,
            parts: parts ?? [self.text("u-\(id)", text)],
            model: nil,
            mode: nil,
            sentAtEpochMs: sentAtEpochMs
        )
    }

    private func filePart(id: String, dataURL: String) -> FilePart {
        FilePart(
            id: id,
            filename: "IMG_1234.JPG",
            mime: "image/jpeg",
            kind: .image,
            dataURL: dataURL
        )
    }

    private func text(_ id: String, _ value: String, isStreaming: Bool = false) -> MessagePart {
        .text(TextPart(id: id, text: value, isStreaming: isStreaming))
    }
}
