import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite struct SessionMessageEngineTests {
    @Test func partDelta_returnsChangedAndAdvancesRevision() {
        let engine = makeEngine()
        seedStreamingAssistant(engine)
        let before = engine.revision(sessionID: "ses-1")
        let result = engine.apply(
            sessionID: "ses-1",
            patch: .partDelta(messageID: "msg-1", partID: "prt-1", delta: " world")
        )
        guard case .changed(let changeSet) = result else {
            Issue.record("expected Changed")
            return
        }
        #expect(changeSet.sessionRevision == before + 1)
        #expect(changeSet.changedMessageIDs == ["msg-1"])
        #expect(changeSet.changedPartIDsByMessage["msg-1"] == ["prt-1"])
        #expect(engine.messages(sessionID: "ses-1").first?.text == "Hello world")
        #expect(engine.shouldPublishLive(changeSet))
    }

    @Test func partDelta_chunkFlattenEqualsSequentialAppend() {
        let engine = makeEngine()
        seedStreamingAssistant(engine)
        let chunks = [" a", "b", "c", "🚀", "d"]
        for chunk in chunks {
            _ = engine.apply(
                sessionID: "ses-1",
                patch: .partDelta(messageID: "msg-1", partID: "prt-1", delta: chunk)
            )
        }
        #expect(engine.messages(sessionID: "ses-1").first?.text == "Hello" + chunks.joined())
        #expect(engine.message(sessionID: "ses-1", messageID: "msg-1")?.text == "Hello" + chunks.joined())
    }

    @Test func removeUnknownMessage_isNoChange() {
        let engine = makeEngine()
        seedStreamingAssistant(engine)
        let before = engine.revision(sessionID: "ses-1")
        #expect(engine.apply(sessionID: "ses-1", patch: .removed(messageID: "missing")) == .noChange)
        #expect(engine.revision(sessionID: "ses-1") == before)
    }

    @Test func infoUpdated_withoutId_isNoChange() {
        let engine = makeEngine()
        seedStreamingAssistant(engine)
        let before = engine.revision(sessionID: "ses-1")
        #expect(
            engine.apply(
                sessionID: "ses-1",
                patch: .infoUpdated([
                    "role": .string("assistant"),
                    "sessionID": .string("ses-1"),
                ])
            ) == .noChange
        )
        #expect(engine.revision(sessionID: "ses-1") == before)
        #expect(engine.messages(sessionID: "ses-1").map(\.id) == ["msg-1"])
    }

    @Test func partOrder_preservedAcrossDelta() {
        let engine = makeEngine()
        engine.replaceAll(
            sessionID: "ses-1",
            messages: [
                Message(
                    id: "msg-1",
                    role: .assistant,
                    text: "AB",
                    parts: [
                        .text(TextPart(id: "prt-a", text: "A", isStreaming: false)),
                        .tool(ToolPart(id: "prt-tool", toolName: "bash", status: .completed)),
                        .text(TextPart(id: "prt-b", text: "B", isStreaming: false)),
                    ],
                    model: nil,
                    mode: nil,
                    sentAtEpochMs: nil
                ),
            ]
        )
        _ = engine.apply(
            sessionID: "ses-1",
            patch: .partDelta(messageID: "msg-1", partID: "prt-b", delta: "!")
        )
        let parts = engine.messages(sessionID: "ses-1").first?.parts ?? []
        #expect(parts.count == 3)
        guard case .text(let a) = parts[0],
              case .tool = parts[1],
              case .text(let b) = parts[2]
        else {
            Issue.record("unexpected part order")
            return
        }
        #expect(a.text == "A")
        #expect(b.text == "B!")
    }

    /// Store currently always commits `Changed` on a successful part write, even
    /// when the payload is identical. Phase 3 therefore asserts revision
    /// monotonicity rather than NoChange for a second identical part.updated.
    @Test func identicalPartUpdated_stillAdvancesRevision() {
        let engine = makeEngine()
        seedStreamingAssistant(engine)
        let part: [String: AnySendable] = [
            "id": .string("prt-1"),
            "messageID": .string("msg-1"),
            "sessionID": .string("ses-1"),
            "type": .string("text"),
            "text": .string("Hello"),
        ]
        guard case .changed(let first) = engine.apply(
            sessionID: "ses-1",
            patch: .partUpdated(part: part, delta: nil)
        ) else {
            Issue.record("expected first Changed")
            return
        }
        let afterFirst = engine.revision(sessionID: "ses-1")
        #expect(first.sessionRevision == afterFirst)
        guard case .changed(let second) = engine.apply(
            sessionID: "ses-1",
            patch: .partUpdated(part: part, delta: nil)
        ) else {
            Issue.record("expected second Changed (store does not idempotent-dedupe)")
            return
        }
        #expect(second.sessionRevision == afterFirst + 1)
        #expect(engine.revision(sessionID: "ses-1") == afterFirst + 1)
        #expect(engine.shouldPublishLive(second))
    }

    @Test func multipart_preservationAcrossPartUpdate() {
        let engine = makeEngine()
        engine.replaceAll(
            sessionID: "ses-1",
            messages: [
                Message(
                    id: "msg-1",
                    role: .assistant,
                    text: "AB",
                    parts: [
                        .text(TextPart(id: "prt-a", text: "A", isStreaming: false)),
                        .tool(ToolPart(id: "prt-tool", toolName: "bash", status: .completed)),
                        .text(TextPart(id: "prt-b", text: "B", isStreaming: false)),
                    ],
                    model: nil,
                    mode: nil,
                    sentAtEpochMs: nil
                ),
            ]
        )
        _ = engine.apply(
            sessionID: "ses-1",
            patch: .partUpdated(
                part: [
                    "id": .string("prt-b"),
                    "messageID": .string("msg-1"),
                    "sessionID": .string("ses-1"),
                    "type": .string("text"),
                    "text": .string("B!"),
                ],
                delta: nil
            )
        )
        let rebuilt = engine.message(sessionID: "ses-1", messageID: "msg-1")
        #expect(rebuilt?.parts.count == 3)
        guard case .text(let a) = rebuilt?.parts[0],
              case .tool(let tool) = rebuilt?.parts[1],
              case .text(let b) = rebuilt?.parts[2]
        else {
            Issue.record("multipart order lost")
            return
        }
        #expect(a.text == "A")
        #expect(tool.toolName == "bash")
        #expect(b.text == "B!")
        #expect(rebuilt?.text == "A\nB!")
    }

    @Test func deletion_removesMessageAndIsLivePublishable() {
        let engine = makeEngine()
        seedStreamingAssistant(engine)
        let before = engine.revision(sessionID: "ses-1")
        guard case .changed(let changeSet) = engine.apply(
            sessionID: "ses-1",
            patch: .removed(messageID: "msg-1")
        ) else {
            Issue.record("expected Changed")
            return
        }
        #expect(changeSet.removedMessageIDs == ["msg-1"])
        #expect(changeSet.structuralChange)
        #expect(changeSet.sessionRevision == before + 1)
        #expect(engine.shouldPublishLive(changeSet))
        #expect(engine.messages(sessionID: "ses-1").isEmpty)
        #expect(engine.message(sessionID: "ses-1", messageID: "msg-1") == nil)
    }

    @Test func revision_monotonicAcrossMixedMutations() {
        let engine = makeEngine()
        seedStreamingAssistant(engine)
        var previous = engine.revision(sessionID: "ses-1")
        let patches: [MessagePatch] = [
            .partDelta(messageID: "msg-1", partID: "prt-1", delta: " x"),
            .partUpdated(
                part: [
                    "id": .string("prt-1"),
                    "messageID": .string("msg-1"),
                    "sessionID": .string("ses-1"),
                    "type": .string("text"),
                    "text": .string("Hello x"),
                ],
                delta: nil
            ),
            .partUpdated(
                part: [
                    "id": .string("prt-2"),
                    "messageID": .string("msg-1"),
                    "sessionID": .string("ses-1"),
                    "type": .string("text"),
                    "text": .string("more"),
                ],
                delta: nil
            ),
        ]
        for patch in patches {
            guard case .changed(let changeSet) = engine.apply(sessionID: "ses-1", patch: patch) else {
                Issue.record("expected Changed for \(patch)")
                return
            }
            #expect(changeSet.sessionRevision == previous + 1)
            previous = changeSet.sessionRevision
        }
        engine.finishStreamingText(sessionID: "ses-1")
        let afterFinish = engine.revision(sessionID: "ses-1")
        #expect(afterFinish >= previous)
        #expect(engine.hasStreamingText(sessionID: "ses-1") == false)
        guard case .text(let text) = engine.message(sessionID: "ses-1", messageID: "msg-1")?.parts.first else {
            Issue.record("expected text part")
            return
        }
        #expect(text.isStreaming == false)
    }

    @Test func shouldPublishLive_falseForInfoOnlyInsert() {
        let engine = makeEngine()
        guard case .changed(let changeSet) = engine.apply(
            sessionID: "ses-1",
            patch: .infoUpdated([
                "id": .string("msg-2"),
                "role": .string("assistant"),
                "sessionID": .string("ses-1"),
            ])
        ) else {
            Issue.record("expected Changed")
            return
        }
        #expect(changeSet.insertedMessageIDs == ["msg-2"])
        #expect(changeSet.structuralChange)
        #expect(!engine.shouldPublishLive(changeSet))
    }

    @Test func userInsert_landsBeforeStreamingAssistant() {
        let engine = makeEngine()
        engine.replaceAll(
            sessionID: "ses-1",
            messages: [
                Message(
                    id: "msg-old-user",
                    role: .user,
                    text: "Earlier",
                    model: nil,
                    mode: nil,
                    sentAtEpochMs: nil
                ),
                Message(
                    id: "msg-old-asst",
                    role: .assistant,
                    text: "Done",
                    parts: [.text(TextPart(id: "prt-old", text: "Done", isStreaming: false))],
                    model: nil,
                    mode: nil,
                    sentAtEpochMs: nil
                ),
                Message(
                    id: "msg-live",
                    role: .assistant,
                    text: "Working",
                    parts: [.text(TextPart(id: "prt-live", text: "Working", isStreaming: true))],
                    model: nil,
                    mode: nil,
                    sentAtEpochMs: nil
                ),
            ]
        )
        _ = engine.apply(
            sessionID: "ses-1",
            patch: .infoUpdated([
                "id": .string("msg-new-user"),
                "role": .string("user"),
                "sessionID": .string("ses-1"),
            ])
        )
        _ = engine.apply(
            sessionID: "ses-1",
            patch: .partUpdated(
                part: [
                    "id": .string("msg-new-user:text:0"),
                    "messageID": .string("msg-new-user"),
                    "sessionID": .string("ses-1"),
                    "type": .string("text"),
                    "text": .string("Hello from CLI"),
                ],
                delta: nil
            )
        )
        #expect(engine.messages(sessionID: "ses-1").map(\.id) == [
            "msg-old-user",
            "msg-old-asst",
            "msg-new-user",
            "msg-live",
        ])
    }

    @Test func frameAfterChange_splicesChangedMessageOnly() {
        let engine = makeEngine()
        engine.replaceAll(
            sessionID: "ses-1",
            messages: [
                Message(
                    id: "msg-a",
                    role: .user,
                    text: "A",
                    parts: [.text(TextPart(id: "prt-a", text: "A", isStreaming: false))],
                    model: nil,
                    mode: nil,
                    sentAtEpochMs: nil
                ),
                Message(
                    id: "msg-b",
                    role: .assistant,
                    text: "B",
                    parts: [.text(TextPart(id: "prt-b", text: "B", isStreaming: true))],
                    model: nil,
                    mode: nil,
                    sentAtEpochMs: nil
                ),
            ]
        )
        let previous = engine.messages(sessionID: "ses-1")
        guard case .changed(let changeSet) = engine.apply(
            sessionID: "ses-1",
            patch: .partDelta(messageID: "msg-b", partID: "prt-b", delta: "!")
        ) else {
            Issue.record("expected Changed")
            return
        }
        let frame = engine.frameAfterChange(
            sessionID: "ses-1",
            changeSet: changeSet,
            previous: previous
        )
        #expect(frame.count == 2)
        #expect(frame[0].text == "A")
        #expect(frame[1].text == "B!")
        #expect(frame[0].id == previous[0].id)
    }

    @Test func frameAfterChange_rebuildsWhenChangedMessageMissingFromPrevious() {
        let engine = makeEngine()
        engine.replaceAll(
            sessionID: "ses-1",
            messages: [
                Message(
                    id: "msg-a",
                    role: .user,
                    text: "A",
                    parts: [.text(TextPart(id: "prt-a", text: "A", isStreaming: false))],
                    model: nil,
                    mode: nil,
                    sentAtEpochMs: nil
                ),
            ]
        )
        let previous = engine.messages(sessionID: "ses-1")
        _ = engine.apply(
            sessionID: "ses-1",
            patch: .infoUpdated([
                "id": .string("msg-b"),
                "role": .string("assistant"),
                "sessionID": .string("ses-1"),
            ])
        )
        _ = engine.apply(
            sessionID: "ses-1",
            patch: .partUpdated(
                part: [
                    "id": .string("prt-b"),
                    "messageID": .string("msg-b"),
                    "sessionID": .string("ses-1"),
                    "type": .string("text"),
                    "text": .string(""),
                ],
                delta: nil
            )
        )
        guard case .changed(let changeSet) = engine.apply(
            sessionID: "ses-1",
            patch: .partDelta(messageID: "msg-b", partID: "prt-b", delta: "Live")
        ) else {
            Issue.record("expected Changed")
            return
        }
        let frame = engine.frameAfterChange(
            sessionID: "ses-1",
            changeSet: changeSet,
            previous: previous
        )
        #expect(frame.map(\.id) == ["msg-a", "msg-b"])
        #expect(frame.last?.text == "Live")
    }

    @Test func frameAfterChange_fullRebuildOnRemoval() {
        let engine = makeEngine()
        engine.replaceAll(
            sessionID: "ses-1",
            messages: [
                Message(
                    id: "msg-a",
                    role: .user,
                    text: "A",
                    parts: [.text(TextPart(id: "prt-a", text: "A", isStreaming: false))],
                    model: nil,
                    mode: nil,
                    sentAtEpochMs: nil
                ),
                Message(
                    id: "msg-b",
                    role: .assistant,
                    text: "B",
                    parts: [.text(TextPart(id: "prt-b", text: "B", isStreaming: false))],
                    model: nil,
                    mode: nil,
                    sentAtEpochMs: nil
                ),
            ]
        )
        let previous = engine.messages(sessionID: "ses-1")
        guard case .changed(let changeSet) = engine.apply(
            sessionID: "ses-1",
            patch: .removed(messageID: "msg-a")
        ) else {
            Issue.record("expected Changed")
            return
        }
        let frame = engine.frameAfterChange(
            sessionID: "ses-1",
            changeSet: changeSet,
            previous: previous
        )
        #expect(frame.map(\.id) == ["msg-b"])
    }

    @Test func reasoningEnded_keepsStartFromWhenThinkingBegan() {
        let engine = makeEngine()
        engine.replaceAll(
            sessionID: "ses-1",
            messages: [
                Message(
                    id: "msg-1",
                    role: .assistant,
                    text: "",
                    parts: [
                        .reasoning(
                            ReasoningPart(
                                id: "prt-r",
                                text: "hmm",
                                isStreaming: true,
                                timeStartEpochMs: 1_000
                            )
                        ),
                    ],
                    model: nil,
                    mode: nil,
                    sentAtEpochMs: nil
                ),
            ]
        )
        _ = engine.apply(
            sessionID: "ses-1",
            patch: .partUpdated(
                part: [
                    "id": .string("prt-r"),
                    "messageID": .string("msg-1"),
                    "sessionID": .string("ses-1"),
                    "type": .string("reasoning"),
                    "text": .string("hmm"),
                    "time": .dictionary([
                        "start": .int64(2_000),
                        "end": .int64(2_000),
                    ]),
                ],
                delta: nil
            )
        )
        guard case .reasoning(let reasoning) = engine.messages(sessionID: "ses-1").first?.parts.first else {
            Issue.record("expected reasoning part")
            return
        }
        #expect(reasoning.timeStartEpochMs == 1_000)
        #expect(reasoning.timeEndEpochMs == 2_000)
    }

    @Test func runningToolWithoutTime_stampsStartAndPreservesAcrossUpdateAndReplaceAll() {
        let engine = makeEngine()
        engine.replaceAll(
            sessionID: "ses-1",
            messages: [
                Message(
                    id: "msg-1",
                    role: .assistant,
                    text: "",
                    parts: [.tool(ToolPart(id: "prt-task", toolName: "task", status: .running))],
                    model: nil,
                    mode: nil,
                    sentAtEpochMs: nil
                ),
            ]
        )
        guard case .tool(let seeded) = engine.messages(sessionID: "ses-1").first?.parts.first else {
            Issue.record("expected running tool")
            return
        }
        #expect(seeded.timeStartEpochMs != nil)
        let stamped = seeded.timeStartEpochMs

        _ = engine.apply(
            sessionID: "ses-1",
            patch: .partUpdated(
                part: [
                    "id": .string("prt-task"),
                    "messageID": .string("msg-1"),
                    "sessionID": .string("ses-1"),
                    "type": .string("tool"),
                    "tool": .string("task"),
                    "state": .dictionary(["status": .string("running")]),
                ],
                delta: nil
            )
        )
        guard case .tool(let updated) = engine.messages(sessionID: "ses-1").first?.parts.first else {
            Issue.record("expected updated tool")
            return
        }
        #expect(updated.timeStartEpochMs == stamped)

        engine.replaceAll(
            sessionID: "ses-1",
            messages: [
                Message(
                    id: "msg-1",
                    role: .assistant,
                    text: "",
                    parts: [
                        .tool(ToolPart(id: "prt-task", toolName: "task", status: .running, timeStartEpochMs: 10)),
                    ],
                    model: nil,
                    mode: nil,
                    sentAtEpochMs: nil
                ),
            ]
        )
        guard case .tool(let reloaded) = engine.messages(sessionID: "ses-1").first?.parts.first else {
            Issue.record("expected reloaded tool")
            return
        }
        #expect(reloaded.timeStartEpochMs == stamped)
    }

    @Test func finishStreaming_clearsReasoningStreamingFlag() {
        let engine = makeEngine()
        engine.replaceAll(
            sessionID: "ses-1",
            messages: [
                Message(
                    id: "msg-1",
                    role: .assistant,
                    text: "",
                    parts: [.reasoning(ReasoningPart(id: "prt-r", text: "hmm", isStreaming: true))],
                    model: nil,
                    mode: nil,
                    sentAtEpochMs: nil
                ),
            ]
        )
        _ = engine.apply(sessionID: "ses-1", patch: .partDelta(messageID: "msg-1", partID: "prt-r", delta: " more"))
        #expect(engine.hasStreamingText(sessionID: "ses-1"))
        engine.finishStreamingText(sessionID: "ses-1")
        #expect(engine.hasStreamingText(sessionID: "ses-1") == false)
        guard case .reasoning(let reasoning) = engine.messages(sessionID: "ses-1").first?.parts.first else {
            Issue.record("expected reasoning part")
            return
        }
        #expect(reasoning.isStreaming == false)
        #expect(reasoning.text == "hmm more")
    }

    @Test func mergeAuthoritative_patchesToolInputAndKeepsStartTime() {
        let engine = makeEngine()
        engine.replaceAll(
            sessionID: "ses-1",
            messages: [
                Message(
                    id: "msg-1",
                    role: .assistant,
                    text: "",
                    parts: [
                        .tool(
                            ToolPart(
                                id: "prt-task",
                                toolName: "subagent",
                                status: .running,
                                timeStartEpochMs: 1_710_000_000_000
                            )
                        ),
                    ],
                    model: nil,
                    mode: nil,
                    sentAtEpochMs: nil
                ),
            ]
        )
        let stamped: Int64?
        if case .tool(let seeded) = engine.messages(sessionID: "ses-1").first?.parts.first {
            stamped = seeded.timeStartEpochMs
        } else {
            stamped = nil
        }
        let revision = engine.revision(sessionID: "ses-1")
        #expect(
            engine.mergeAuthoritativeIfRevision(
                sessionID: "ses-1",
                messages: [
                    Message(
                        id: "msg-1",
                        role: .assistant,
                        text: "",
                        parts: [
                            .tool(
                                ToolPart(
                                    id: "prt-task",
                                    toolName: "subagent",
                                    status: .completed,
                                    input: ["agent": .string("general")]
                                )
                            ),
                        ],
                        model: nil,
                        mode: nil,
                        sentAtEpochMs: nil
                    ),
                ],
                expectedRevision: revision
            )
        )
        guard case .tool(let tool) = engine.messages(sessionID: "ses-1").first?.parts.first else {
            Issue.record("expected tool part")
            return
        }
        #expect(tool.status == .completed)
        #expect(tool.input?["agent"]?.stringValue == "general")
        #expect(tool.timeStartEpochMs == stamped)
    }

    @Test func mergeAuthoritative_staleRevisionIsNoOp() {
        let engine = makeEngine()
        seedStreamingAssistant(engine)
        let stale = engine.revision(sessionID: "ses-1")
        _ = engine.apply(
            sessionID: "ses-1",
            patch: .partDelta(messageID: "msg-1", partID: "prt-1", delta: " world")
        )
        #expect(
            engine.mergeAuthoritativeIfRevision(
                sessionID: "ses-1",
                messages: [
                    Message(
                        id: "msg-1",
                        role: .assistant,
                        text: "Nope",
                        parts: [.text(TextPart(id: "prt-1", text: "Nope", isStreaming: false))],
                        model: nil,
                        mode: nil,
                        sentAtEpochMs: nil
                    ),
                ],
                expectedRevision: stale
            ) == false
        )
        #expect(engine.messages(sessionID: "ses-1").first?.text == "Hello world")
    }

    @Test func mergeAuthoritative_finishesStreamingWhenRestPartsLackIDs() {
        let engine = makeEngine()
        seedStreamingAssistant(engine)
        let revision = engine.revision(sessionID: "ses-1")
        #expect(
            engine.mergeAuthoritativeIfRevision(
                sessionID: "ses-1",
                messages: [
                    Message(
                        id: "msg-1",
                        role: .assistant,
                        text: "Hello",
                        parts: [.text(TextPart(text: "Hello", isStreaming: false))],
                        model: nil,
                        mode: nil,
                        sentAtEpochMs: nil
                    ),
                ],
                expectedRevision: revision
            )
        )
        guard case .text(let text) = engine.messages(sessionID: "ses-1").first?.parts.first else {
            Issue.record("expected text part")
            return
        }
        #expect(text.text == "Hello")
        #expect(text.isStreaming == false)
    }

    @Test func partUpdated_overlaysToolFieldsInsteadOfReplacing() {
        let engine = makeEngine()
        engine.replaceAll(
            sessionID: "ses-1",
            messages: [
                Message(
                    id: "msg-1",
                    role: .assistant,
                    text: "",
                    parts: [
                        .tool(
                            ToolPart(
                                id: "prt-s",
                                toolName: "websearch",
                                status: .pending,
                                input: ["query": .string("opencode mobile")]
                            )
                        ),
                    ],
                    model: nil,
                    mode: nil,
                    sentAtEpochMs: nil
                ),
            ]
        )
        _ = engine.apply(
            sessionID: "ses-1",
            patch: .partUpdated(
                part: [
                    "id": .string("prt-s"),
                    "messageID": .string("msg-1"),
                    "sessionID": .string("ses-1"),
                    "type": .string("tool"),
                    "tool": .string(""),
                    "state": .dictionary([
                        "status": .string("completed"),
                        "input": .dictionary([:]),
                    ]),
                ],
                delta: nil
            )
        )
        guard case .tool(let tool) = engine.messages(sessionID: "ses-1").first?.parts.first else {
            Issue.record("expected tool part")
            return
        }
        #expect(tool.toolName == "websearch")
        #expect(tool.status == .completed)
        #expect(tool.input?["query"]?.stringValue == "opencode mobile")
    }

    @Test func partUpdated_emptyTextDoesNotReplaceStreamingText() {
        let engine = makeEngine()
        seedStreamingAssistant(engine)
        _ = engine.apply(
            sessionID: "ses-1",
            patch: .partUpdated(
                part: [
                    "id": .string("prt-1"),
                    "messageID": .string("msg-1"),
                    "sessionID": .string("ses-1"),
                    "type": .string("text"),
                    "text": .string(""),
                ],
                delta: nil
            )
        )
        _ = engine.apply(
            sessionID: "ses-1",
            patch: .partDelta(messageID: "msg-1", partID: "prt-1", delta: " world")
        )
        guard case .text(let text) = engine.messages(sessionID: "ses-1").first?.parts.first else {
            Issue.record("expected text part")
            return
        }
        #expect(text.text == "Hello world")
        #expect(text.isStreaming)
    }

    @Test func infoUpdated_keepsDeliveryWhenWaitingUpdateOmitsIt() {
        let engine = makeEngine()
        _ = engine.apply(
            sessionID: "ses-1",
            patch: .infoUpdated([
                "id": .string("msg-1"),
                "role": .string("user"),
                "sessionID": .string("ses-1"),
                "waiting": .bool(true),
                "delivery": .string("queue"),
            ])
        )
        _ = engine.apply(
            sessionID: "ses-1",
            patch: .partUpdated(
                part: [
                    "id": .string("msg-1:text:0"),
                    "messageID": .string("msg-1"),
                    "sessionID": .string("ses-1"),
                    "type": .string("text"),
                    "text": .string("Hello"),
                ],
                delta: nil
            )
        )
        _ = engine.apply(
            sessionID: "ses-1",
            patch: .infoUpdated([
                "id": .string("msg-1"),
                "role": .string("user"),
                "sessionID": .string("ses-1"),
                "waiting": .bool(true),
            ])
        )
        let message = engine.messages(sessionID: "ses-1").first
        #expect(message?.followUpDelivery == .queue)
        #expect(message?.isWaitingForPickup == true)
        #expect(message?.waitingCaption == "Held until idle")
    }

    @Test func prependOlder_doesNotOverwriteNewerLiveText() {
        let engine = makeEngine()
        engine.replaceAll(
            sessionID: "ses-1",
            messages: [
                Message(id: "new", role: .user, text: "Live", model: nil, mode: nil, sentAtEpochMs: nil),
            ]
        )
        let revision = engine.revision(sessionID: "ses-1")
        #expect(
            engine.prependOlderIfRevision(
                sessionID: "ses-1",
                older: [
                    Message(id: "old", role: .user, text: "Older", model: nil, mode: nil, sentAtEpochMs: nil),
                    Message(id: "new", role: .user, text: "Stale", model: nil, mode: nil, sentAtEpochMs: nil),
                ],
                expectedRevision: revision
            )
        )
        let messages = engine.messages(sessionID: "ses-1")
        #expect(messages.map(\.id) == ["old", "new"])
        #expect(messages.last?.text == "Live")
    }

    @Test func prependOlder_staleRevisionIsNoOpAndPreservesSse() {
        let engine = makeEngine()
        engine.replaceAll(
            sessionID: "ses-1",
            messages: [
                Message(
                    id: "live",
                    role: .assistant,
                    text: "Hello",
                    parts: [.text(TextPart(id: "prt-1", text: "Hello", isStreaming: true))],
                    model: nil,
                    mode: nil,
                    sentAtEpochMs: nil
                ),
            ]
        )
        let stale = engine.revision(sessionID: "ses-1")
        _ = engine.apply(
            sessionID: "ses-1",
            patch: .partDelta(messageID: "live", partID: "prt-1", delta: " world")
        )
        #expect(
            engine.prependOlderIfRevision(
                sessionID: "ses-1",
                older: [
                    Message(id: "old", role: .user, text: "Older", model: nil, mode: nil, sentAtEpochMs: nil),
                    Message(id: "live", role: .assistant, text: "Stale", model: nil, mode: nil, sentAtEpochMs: nil),
                ],
                expectedRevision: stale
            ) == false
        )
        #expect(engine.messages(sessionID: "ses-1").map(\.id) == ["live"])
        #expect(engine.messages(sessionID: "ses-1").first?.text == "Hello world")

        #expect(
            engine.prependOlderIfRevision(
                sessionID: "ses-1",
                older: [
                    Message(id: "old", role: .user, text: "Older", model: nil, mode: nil, sentAtEpochMs: nil),
                    Message(id: "live", role: .assistant, text: "Stale", model: nil, mode: nil, sentAtEpochMs: nil),
                ],
                expectedRevision: engine.revision(sessionID: "ses-1")
            )
        )
        let messages = engine.messages(sessionID: "ses-1")
        #expect(messages.map(\.id) == ["old", "live"])
        #expect(messages.last?.text == "Hello world")
    }

    @Test func waitingCaption_usesSteerWhenWaitingWithoutDelivery() {
        let message = Message(
            id: "msg-1",
            role: .user,
            text: "Hello",
            model: nil,
            mode: nil,
            sentAtEpochMs: nil,
            isWaitingForPickup: true
        )
        #expect(message.waitingCaption == "Waiting to steer")
    }

    private func makeEngine() -> SessionMessageEngine {
        SessionMessageEngine(store: InMemoryMessageStore())
    }

    private func seedStreamingAssistant(_ engine: SessionMessageEngine) {
        engine.replaceAll(
            sessionID: "ses-1",
            messages: [
                Message(
                    id: "msg-1",
                    role: .assistant,
                    text: "Hello",
                    parts: [.text(TextPart(id: "prt-1", text: "Hello", isStreaming: true))],
                    model: nil,
                    mode: nil,
                    sentAtEpochMs: nil
                ),
            ]
        )
    }
}
