import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite(.serialized)
@MainActor
struct MessageSyncTests {
    private let decoder = JSONDecoder()

    @Test func initialLoad_emitsMessagesFromRepository() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)
        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }

        let loaded = await waitUntil {
            collector.latest()?.first?.text == "Hello"
        }
        task.cancel()
        #expect(loaded)
        #expect(collector.emissions.count >= 1)
    }

    @Test func initialLoad_buffersEventsThatArriveDuringRestRequest() async {
        let repo = FakeLiveMessageRepository()
        repo.holdNextListMessages()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)
        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let blocked = await waitUntil { repo.isListBlocked }
        #expect(blocked)

        eventStream.emit(eventWithInfo(id: "msg-2", role: "assistant", sessionID: "ses-1"))
        eventStream.emit(eventWithTextPart(partID: "prt-1", messageID: "msg-2", text: "", sessionID: "ses-1"))
        eventStream.emit(eventWithPartDelta(partID: "prt-1", messageID: "msg-2", delta: "Hello", sessionID: "ses-1"))
        repo.resumeListMessages()

        let streamed = await waitUntil {
            collector.latest()?.first(where: { $0.id == "msg-2" })?.text == "Hello"
        }
        task.cancel()
        #expect(streamed)
    }

    @Test func initialLoad_overflowDuringRestTriggersAuthoritativeRecovery() async {
        let repo = FakeLiveMessageRepository()
        repo.messages = [
            Message(
                id: "msg-rest",
                role: .assistant,
                text: "from-rest",
                parts: [.text(TextPart(text: "from-rest", isStreaming: false))],
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            ),
        ]
        repo.holdNextListMessages()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream, eventStagingCapacity: 2)
        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let blocked = await waitUntil { repo.isListBlocked }
        #expect(blocked)

        for index in 0..<4 {
            eventStream.emit(eventWithInfo(id: "msg-burst-\(index)", role: "assistant", sessionID: "ses-1"))
        }
        repo.messages = [
            Message(
                id: "msg-recovered",
                role: .assistant,
                text: "recovered",
                parts: [.text(TextPart(text: "recovered", isStreaming: false))],
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            ),
        ]
        repo.resumeListMessages()

        let recovered = await waitUntil {
            collector.latest()?.count == 1 && collector.latest()?.first?.id == "msg-recovered"
        }
        task.cancel()
        #expect(recovered)
        #expect(collector.emissions.contains { frame in frame.contains { $0.id == "msg-rest" } } == false)
    }

    @Test func messageUpdated_suppressesEmptyNewSlot() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        try? await Task.sleep(for: .milliseconds(300))

        let event = eventWithInfo(id: "msg-2", role: "assistant", sessionID: "ses-1")
        eventStream.emit(event)
        try? await Task.sleep(for: .milliseconds(500))
        task.cancel()

        let last = collector.latest()
        #expect(last?.count == 1)
        #expect(last?.contains(where: { $0.id == "msg-2" }) == false)
    }

    @Test func messagePartUpdated_textPartAppendsToSlot() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        try? await Task.sleep(for: .milliseconds(100))

        // Create a user message slot first.
        eventStream.emit(eventWithInfo(id: "msg-2", role: "user", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(50))

        // Add a text part to it.
        let partEvent = eventWithTextPart(partID: "prt-1", messageID: "msg-2", text: "Hello from part", sessionID: "ses-1")
        eventStream.emit(partEvent)
        let updated = await waitUntil {
            collector.latest()?.first(where: { $0.id == "msg-2" })?.text.contains("Hello from part") == true
        }
        task.cancel()

        let msg2 = collector.latest()?.first(where: { $0.id == "msg-2" })
        #expect(updated)
        #expect(msg2?.text.contains("Hello from part") == true)
        #expect(msg2?.parts.count == 1)
    }

    @Test func messagePartUpdated_textDelta_marksStreaming() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        try? await Task.sleep(for: .milliseconds(300))

        eventStream.emit(eventWithInfo(id: "msg-2", role: "assistant", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(200))

        // Tool pending.
        eventStream.emit(eventWithToolPart(partID: "prt-t1", messageID: "msg-2", tool: "webfetch", status: "pending", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(200))

        // Tool running.
        eventStream.emit(eventWithToolPart(partID: "prt-t1", messageID: "msg-2", tool: "webfetch", status: "running", title: "Searching", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(200))

        // Tool completed.
        eventStream.emit(eventWithToolPart(partID: "prt-t1", messageID: "msg-2", tool: "webfetch", status: "completed", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(500))

        // Add a text part to it.
        let partEvent = eventWithTextPart(partID: "prt-1", messageID: "msg-2", text: "Hello from part", sessionID: "ses-1")
        eventStream.emit(partEvent)
        try? await Task.sleep(for: .milliseconds(500))
        task.cancel()

        let last = collector.latest()
        let msg2 = last?.first(where: { $0.id == "msg-2" })
        let toolParts = msg2?.parts.compactMap { part -> ToolPart? in if case .tool(let t) = part { t } else { nil } }
        #expect(toolParts?.count == 1)
        #expect(toolParts?.first?.toolName == "webfetch")
        #expect(toolParts?.first?.status == .completed)
        // Tool labels stay on structured parts; flattened text is TextPart-only (OPE-49).
        #expect(msg2?.text.contains("webfetch") != true)
        #expect(msg2?.text == "Hello from part")
    }

    @Test func messagePartDelta_appendsStreamingText() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        try? await Task.sleep(for: .milliseconds(100))

        eventStream.emit(eventWithInfo(id: "msg-2", role: "assistant", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(50))
        eventStream.emit(eventWithTextPart(partID: "prt-1", messageID: "msg-2", text: "", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(50))
        eventStream.emit(eventWithPartDelta(partID: "prt-1", messageID: "msg-2", delta: "Hel", sessionID: "ses-1"))
        eventStream.emit(eventWithPartDelta(partID: "prt-1", messageID: "msg-2", delta: "lo", sessionID: "ses-1"))
        let updated = await waitUntil {
            collector.latest()?.first(where: { $0.id == "msg-2" })?.text == "Hello"
        }
        task.cancel()

        let msg2 = collector.latest()?.first(where: { $0.id == "msg-2" })
        #expect(updated)
        #expect(msg2?.text == "Hello")
        guard case .text(let textPart) = msg2?.parts.first else {
            Issue.record("Expected text part")
            return
        }
        #expect(textPart.isStreaming == true)
    }

    @Test func displayPipelinePublishesStreamingAndFinalStatesFromRealDeltaEvents() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)
        let pipeline = DisplayMessagePipeline(
            messageSync: sync,
            contentPreparer: MessageContentPreparer()
        )
        let collector = DisplayMessageCollector()
        let task = Task {
            await collector.collect(pipeline.observeDisplayMessages(sessionID: "ses-1", directory: "/repo"))
        }
        let loaded = await waitUntil { collector.latest() != nil }
        #expect(loaded)

        eventStream.emit(eventWithInfo(id: "msg-2", role: "assistant", sessionID: "ses-1"))
        eventStream.emit(eventWithTextPart(partID: "prt-1", messageID: "msg-2", text: "", sessionID: "ses-1"))
        eventStream.emit(eventWithPartDelta(partID: "prt-1", messageID: "msg-2", delta: "Hello", sessionID: "ses-1"))
        let streaming = await waitUntil {
            collector.latest()?.first(where: { $0.id == "msg-2" })?.isStreaming == true
        }
        #expect(streaming)

        eventStream.emit(eventWithTextPart(partID: "prt-1", messageID: "msg-2", text: "Hello", sessionID: "ses-1"))
        let finalized = await waitUntil {
            collector.latest()?.first(where: { $0.id == "msg-2" })?.isStreaming == false
                && collector.latest()?.first(where: { $0.id == "msg-2" })?.text == "Hello"
        }
        task.cancel()
        #expect(finalized)
    }

    @Test func messagePartDelta_ignoresNonTextField() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        try? await Task.sleep(for: .milliseconds(100))

        eventStream.emit(eventWithInfo(id: "msg-2", role: "assistant", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(50))
        eventStream.emit(eventWithTextPart(partID: "prt-1", messageID: "msg-2", text: "Hi", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(50))
        eventStream.emit(eventWithPartDelta(partID: "prt-1", messageID: "msg-2", delta: "!", field: "reasoning", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(200))
        task.cancel()

        let msg2 = collector.latest()?.first(where: { $0.id == "msg-2" })
        #expect(msg2?.text == "Hi")
        guard case .text(let textPart) = msg2?.parts.first else {
            Issue.record("Expected text part")
            return
        }
        #expect(textPart.isStreaming == false)
    }

    @Test func messagePartDelta_ignoresMissingField() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        try? await Task.sleep(for: .milliseconds(100))

        eventStream.emit(eventWithInfo(id: "msg-2", role: "assistant", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(50))
        eventStream.emit(eventWithTextPart(partID: "prt-1", messageID: "msg-2", text: "Hi", sessionID: "ses-1"))
        eventStream.emit(
            eventWithPartDelta(
                partID: "prt-1",
                messageID: "msg-2",
                delta: "!",
                field: nil,
                sessionID: "ses-1"
            )
        )
        try? await Task.sleep(for: .milliseconds(100))
        task.cancel()

        let msg2 = collector.latest()?.first(where: { $0.id == "msg-2" })
        #expect(msg2?.text == "Hi")
        guard case .text(let textPart) = msg2?.parts.first else {
            Issue.record("Expected text part")
            return
        }
        #expect(textPart.isStreaming == false)
    }

    @Test func messagePartDelta_ignoresReasoningPartDeltas() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        try? await Task.sleep(for: .milliseconds(100))

        eventStream.emit(eventWithInfo(id: "msg-2", role: "assistant", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(50))
        eventStream.emit(eventWithReasoningPart(partID: "prt-reason", messageID: "msg-2", text: "", sessionID: "ses-1"))
        eventStream.emit(eventWithPartDelta(partID: "prt-reason", messageID: "msg-2", delta: "The user said hello.", sessionID: "ses-1"))
        eventStream.emit(eventWithTextPart(partID: "prt-1", messageID: "msg-2", text: "", sessionID: "ses-1"))
        eventStream.emit(eventWithPartDelta(partID: "prt-1", messageID: "msg-2", delta: "Hello!", sessionID: "ses-1"))
        let updated = await waitUntil {
            collector.latest()?.first(where: { $0.id == "msg-2" })?.text == "Hello!"
        }
        task.cancel()

        #expect(updated)
        let msg2 = collector.latest()?.first(where: { $0.id == "msg-2" })
        #expect(msg2?.text == "Hello!")
        #expect(msg2?.parts.count == 1)
    }

    @Test func sessionIdle_reloadClearsStreaming() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        try? await Task.sleep(for: .milliseconds(100))
        eventStream.emit(eventWithInfo(id: "msg-2", role: "assistant", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(50))
        eventStream.emit(eventWithTextPart(partID: "prt-1", messageID: "msg-2", text: "", sessionID: "ses-1"))
        eventStream.emit(eventWithPartDelta(partID: "prt-1", messageID: "msg-2", delta: "Hello", sessionID: "ses-1"))
        let streaming = await waitUntil {
            guard case .text(let part) = collector.latest()?.first(where: { $0.id == "msg-2" })?.parts.first else {
                return false
            }
            return part.isStreaming
        }
        #expect(streaming)

        repo.messages = [
            Message(
                id: "msg-2",
                role: .assistant,
                text: "Hello",
                parts: [.text(TextPart(text: "Hello", isStreaming: false))],
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            ),
        ]
        eventStream.emit(OpencodeEvent(type: "session.idle", sessionID: "ses-1"))
        let finalized = await waitUntil {
            guard case .text(let part) = collector.latest()?.first(where: { $0.id == "msg-2" })?.parts.first else {
                return false
            }
            return part.text == "Hello" && !part.isStreaming
        }
        task.cancel()

        #expect(finalized)
        #expect(repo.listMessagesCallCount == 2)
    }

    @Test func failedIdleReloadEndsStreamingAndPollingRecovers() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream, pollIntervalMs: 25)
        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let loaded = await waitUntil { collector.latest() != nil }
        #expect(loaded)

        eventStream.emit(eventWithInfo(id: "msg-2", role: "assistant", sessionID: "ses-1"))
        eventStream.emit(eventWithTextPart(partID: "prt-1", messageID: "msg-2", text: "", sessionID: "ses-1"))
        eventStream.emit(eventWithPartDelta(partID: "prt-1", messageID: "msg-2", delta: "Partial", sessionID: "ses-1"))
        let streaming = await waitUntil {
            guard case .text(let part) = collector.latest()?.first(where: { $0.id == "msg-2" })?.parts.first else {
                return false
            }
            return part.isStreaming
        }
        #expect(streaming)

        repo.shouldFailList = true
        eventStream.emit(OpencodeEvent(type: "session.idle", sessionID: "ses-1"))
        let recovered = await waitUntil {
            guard case .text(let part) = collector.latest()?.first(where: { $0.id == "msg-2" })?.parts.first else {
                return false
            }
            return !part.isStreaming
        }
        #expect(recovered)

        repo.shouldFailList = false
        repo.messages = [
            Message(
                id: "msg-2",
                role: .assistant,
                text: "Final",
                parts: [.text(TextPart(text: "Final", isStreaming: false))],
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            ),
        ]
        let finalized = await waitUntil {
            collector.latest()?.first?.text == "Final"
        }
        task.cancel()
        #expect(finalized)
    }

    @Test func pollResponseIsDiscardedWhenStreamingStartsInFlight() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream, pollIntervalMs: 25)
        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let loaded = await waitUntil { collector.latest() != nil }
        #expect(loaded)

        repo.holdNextListMessages()
        let pollBlocked = await waitUntil { repo.isListBlocked }
        #expect(pollBlocked)

        eventStream.emit(eventWithInfo(id: "msg-2", role: "assistant", sessionID: "ses-1"))
        eventStream.emit(eventWithTextPart(partID: "prt-1", messageID: "msg-2", text: "", sessionID: "ses-1"))
        eventStream.emit(eventWithPartDelta(partID: "prt-1", messageID: "msg-2", delta: "Live", sessionID: "ses-1"))
        let streamed = await waitUntil {
            collector.latest()?.first(where: { $0.id == "msg-2" })?.text == "Live"
        }
        repo.resumeListMessages()
        try? await Task.sleep(for: .milliseconds(50))
        task.cancel()

        #expect(streamed)
        #expect(collector.latest()?.first(where: { $0.id == "msg-2" })?.text == "Live")
    }

    @Test func replaceAll_clearsStreamingAndMakesRestAuthoritative() async {
        let store = InMemoryMessageStore()
        store.apply(
            sessionID: "ses-1",
            patch: .infoUpdated([
                "id": .string("msg-2"),
                "role": .string("assistant"),
                "sessionID": .string("ses-1"),
            ])
        )
        store.apply(
            sessionID: "ses-1",
            patch: .partUpdated(part: [
                "id": .string("prt-1"),
                "messageID": .string("msg-2"),
                "sessionID": .string("ses-1"),
                "type": .string("text"),
                "text": .string(""),
            ], delta: nil)
        )
        store.apply(
            sessionID: "ses-1",
            patch: .partDelta(messageID: "msg-2", partID: "prt-1", delta: "Hel")
        )
        store.replaceAll(
            sessionID: "ses-1",
            messages: [
                Message(
                    id: "msg-2",
                    role: .assistant,
                    text: "Final",
                    parts: [.text(TextPart(text: "Final", isStreaming: false))],
                    model: nil,
                    mode: nil,
                    sentAtEpochMs: nil
                ),
            ]
        )
        store.apply(
            sessionID: "ses-1",
            patch: .partDelta(messageID: "msg-2", partID: "prt-1", delta: "lo")
        )

        let msg2 = store.messages(sessionID: "ses-1").first(where: { $0.id == "msg-2" })
        #expect(msg2?.text == "Final")
        guard case .text(let textPart) = msg2?.parts.first else {
            Issue.record("Expected authoritative REST text part after replaceAll")
            return
        }
        #expect(textPart.isStreaming == false)
    }

    @Test func messagePartDelta_finalPartUpdatedClearsStreaming() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        try? await Task.sleep(for: .milliseconds(100))

        eventStream.emit(eventWithInfo(id: "msg-2", role: "assistant", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(50))
        eventStream.emit(eventWithTextPart(partID: "prt-1", messageID: "msg-2", text: "", sessionID: "ses-1"))
        eventStream.emit(eventWithPartDelta(partID: "prt-1", messageID: "msg-2", delta: "Hello", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(50))
        eventStream.emit(eventWithTextPart(partID: "prt-1", messageID: "msg-2", text: "Hello", sessionID: "ses-1"))
        let cleared = await waitUntil {
            guard case .text(let part) = collector.latest()?.first(where: { $0.id == "msg-2" })?.parts.first else {
                return false
            }
            return part.text == "Hello" && part.isStreaming == false
        }
        task.cancel()

        #expect(cleared)
    }

    @Test func messagePartUpdated_preservesPartOrderAcrossReplacement() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        try? await Task.sleep(for: .milliseconds(100))

        eventStream.emit(eventWithInfo(id: "msg-2", role: "assistant", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(50))

        eventStream.emit(eventWithTextPart(partID: "prt-1", messageID: "msg-2", text: "First", sessionID: "ses-1"))
        eventStream.emit(eventWithToolPart(partID: "prt-t1", messageID: "msg-2", tool: "bash", status: "pending", sessionID: "ses-1"))
        eventStream.emit(eventWithTextPart(partID: "prt-2", messageID: "msg-2", text: "Second", sessionID: "ses-1"))
        eventStream.emit(eventWithToolPart(partID: "prt-t1", messageID: "msg-2", tool: "bash", status: "completed", sessionID: "ses-1"))
        // Tool labels stay on structured parts; flattened text is TextPart-only (OPE-49).
        let expectedText = "First\nSecond"
        let updated = await waitUntil {
            collector.latest()?.first(where: { $0.id == "msg-2" })?.text == expectedText
        }
        task.cancel()

        let msg2 = collector.latest()?.first(where: { $0.id == "msg-2" })
        #expect(updated)
        #expect(msg2?.text == expectedText)
        #expect(msg2?.parts.count == 3)
        guard case .text(let first) = msg2?.parts[0] else {
            Issue.record("Expected first text part")
            return
        }
        guard case .tool(let tool) = msg2?.parts[1] else {
            Issue.record("Expected middle tool part")
            return
        }
        guard case .text(let second) = msg2?.parts[2] else {
            Issue.record("Expected last text part")
            return
        }
        #expect(first.text == "First")
        #expect(tool.toolName == "bash")
        #expect(tool.status == .completed)
        #expect(second.text == "Second")
    }

    @Test func messagePartUpdated_forRestSeededMessageReloadsTimeline() async {
        let repo = FakeLiveMessageRepository()
        repo.messages = [
            Message(
                id: "msg-1",
                role: .assistant,
                text: "Old\nSibling",
                parts: [
                    .text(TextPart(text: "Old", isStreaming: false)),
                    .text(TextPart(text: "Sibling", isStreaming: false)),
                ],
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            ),
        ]
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        try? await Task.sleep(for: .milliseconds(300))
        let callsAfterInitial = repo.listMessagesCallCount

        repo.messages = [
            Message(
                id: "msg-1",
                role: .assistant,
                text: "New\nSibling",
                parts: [
                    .text(TextPart(text: "New", isStreaming: false)),
                    .text(TextPart(text: "Sibling", isStreaming: false)),
                ],
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            ),
        ]
        eventStream.emit(eventWithInfo(id: "msg-1", role: "assistant", sessionID: "ses-1"))
        eventStream.emit(eventWithTextPart(partID: "prt-1", messageID: "msg-1", text: "New", sessionID: "ses-1"))
        let updated = await waitUntil {
            collector.latest()?.first?.text == "New\nSibling"
        }
        task.cancel()

        #expect(updated)
        #expect(collector.latest()?.first?.text == "New\nSibling")
        #expect(collector.latest()?.first?.parts.count == 2)
        #expect(repo.listMessagesCallCount == callsAfterInitial + 1)
    }

    @Test func restSeededPartIdentity_preservesSiblingsAndStreamsFollowingDelta() async {
        let repo = FakeLiveMessageRepository()
        repo.messages = [
            Message(
                id: "msg-1",
                role: .assistant,
                text: "Old\nSibling",
                parts: [
                    .text(TextPart(id: "prt-1", text: "Old", isStreaming: false)),
                    .text(TextPart(id: "prt-2", text: "Sibling", isStreaming: false)),
                ],
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            ),
        ]
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)
        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let loaded = await waitUntil { collector.latest()?.first?.text == "Old\nSibling" }
        #expect(loaded)
        let callsAfterInitial = repo.listMessagesCallCount

        eventStream.emit(eventWithInfo(id: "msg-1", role: "assistant", sessionID: "ses-1"))
        eventStream.emit(eventWithTextPart(partID: "prt-1", messageID: "msg-1", text: "New", sessionID: "ses-1"))
        eventStream.emit(eventWithPartDelta(partID: "prt-1", messageID: "msg-1", delta: " live", sessionID: "ses-1"))

        let streamed = await waitUntil { collector.latest()?.first?.text == "New live\nSibling" }
        task.cancel()
        #expect(streamed)
        #expect(collector.latest()?.first?.parts.count == 2)
        #expect(repo.listMessagesCallCount == callsAfterInitial)
    }

    @Test func messagePartUpdated_fileChangeEmitsWhenFlattenedTextIsUnchanged() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)
        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let loaded = await waitUntil { collector.latest()?.first?.text == "Hello" }
        #expect(loaded)

        let file = FilePart(
            id: "prt-file",
            filename: "notes.txt",
            mime: "text/plain",
            kind: .text,
            dataURL: "data:text/plain;base64,SGVsbG8="
        )
        repo.messages = [
            Message(
                id: "msg-1",
                role: .user,
                text: "Hello",
                parts: [.text(TextPart(text: "Hello", isStreaming: false)), .file(file)],
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            ),
        ]
        eventStream.emit(eventWithFilePart(file, messageID: "msg-1", sessionID: "ses-1"))

        let updated = await waitUntil {
            collector.latest()?.first?.parts.contains(.file(file)) == true
        }
        task.cancel()
        #expect(updated)
    }

    @Test func messageRemoved_deletesSlotAndEmits() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        try? await Task.sleep(for: .milliseconds(300))

        // Remove msg-1.
        let json = #"{"payload":{"type":"message.removed","properties":{"sessionID":"ses-1","messageID":"msg-1"}}}"#
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(dataLine: json)!
        eventStream.emit(event)
        let removed = await waitUntil {
            collector.latest()?.isEmpty == true
        }
        task.cancel()

        #expect(removed)
        #expect(collector.latest()?.isEmpty == true)
    }

    @Test func messagePartRemoved_updatesText() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        try? await Task.sleep(for: .milliseconds(300))

        // Add msg-2 via SSE (incremental cache).
        eventStream.emit(eventWithInfo(id: "msg-2", role: "assistant", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(200))

        // Add a text part to msg-2.
        eventStream.emit(eventWithTextPart(partID: "prt-2", messageID: "msg-2", text: "Part content", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(200))

        // Now remove that part.
        let json = #"{"payload":{"type":"message.part.removed","properties":{"sessionID":"ses-1","messageID":"msg-2","partID":"prt-2"}}}"#
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(dataLine: json)!
        eventStream.emit(event)
        try? await Task.sleep(for: .milliseconds(500))
        task.cancel()

        let last = collector.latest()
        let msg2 = last?.first(where: { $0.id == "msg-2" })
        // Brand-new SSE slots without renderable content stay hidden.
        #expect(msg2 == nil)
    }

    @Test func streamReconnected_triggersFullReload() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        try? await Task.sleep(for: .milliseconds(300))

        repo.messages = [
            Message(id: "msg-1", role: .user, text: "After reconnect", parts: [], model: nil, mode: nil, sentAtEpochMs: nil),
        ]

        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(dataLine: #"{"payload":{"type":"stream.reconnected","properties":{}}}"#)!
        eventStream.emit(event)
        let reloaded = await waitUntil {
            collector.latest()?.first?.text == "After reconnect"
        }
        task.cancel()

        #expect(reloaded)
        #expect(collector.latest()?.first?.text == "After reconnect")
    }

    @Test func eventsForOtherSessionsAreIgnored() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let loaded = await waitUntil(timeoutMs: 5_000) {
            collector.latest() != nil
        }
        #expect(loaded)

        let countBefore = collector.latest()?.count

        // Emit an event for ses-2.
        let event = eventWithInfo(id: "msg-99", role: "user", sessionID: "ses-2")
        eventStream.emit(event)
        try? await Task.sleep(for: .milliseconds(500))

        let countAfter = collector.latest()?.count
        #expect(countBefore == countAfter)
        task.cancel()
    }

    // MARK: - Helpers

    private func waitUntil(
        timeoutMs: Int = 5_000,
        predicate: @MainActor () -> Bool
    ) async -> Bool {
        let deadline = Date().addingTimeInterval(TimeInterval(timeoutMs) / 1_000)
        while Date() < deadline {
            if predicate() { return true }
            try? await Task.sleep(for: .milliseconds(10))
        }
        return predicate()
    }

    private func eventWithInfo(id: String, role: String, sessionID: String) -> OpencodeEvent {
        let json = """
        {"payload":{"type":"message.updated","properties":{"info":{"id":"\(id)","role":"\(role)","sessionID":"\(sessionID)"}}}}
        """
        return EventDomainAdapter().parseEvent(dataLine: json)!
    }

    private func eventWithTextPart(partID: String, messageID: String, text: String, sessionID: String) -> OpencodeEvent {
        let json = """
        {"payload":{"type":"message.part.updated","properties":{"part":{"id":"\(partID)","messageID":"\(messageID)","sessionID":"\(sessionID)","type":"text","text":"\(text)"}}}}
        """
        return EventDomainAdapter().parseEvent(dataLine: json)!
    }

    private func eventWithReasoningPart(partID: String, messageID: String, text: String, sessionID: String) -> OpencodeEvent {
        let json = """
        {"payload":{"type":"message.part.updated","properties":{"part":{"id":"\(partID)","messageID":"\(messageID)","sessionID":"\(sessionID)","type":"reasoning","text":"\(text)"}}}}
        """
        return EventDomainAdapter().parseEvent(dataLine: json)!
    }

    private func eventWithFilePart(_ file: FilePart, messageID: String, sessionID: String) -> OpencodeEvent {
        let json = """
        {"payload":{"type":"message.part.updated","properties":{"part":{"id":"\(file.id)","messageID":"\(messageID)","sessionID":"\(sessionID)","type":"file","mime":"\(file.mime)","filename":"\(file.filename)","url":"\(file.dataURL)"}}}}
        """
        return EventDomainAdapter().parseEvent(dataLine: json)!
    }

    private func eventWithPartDelta(
        partID: String,
        messageID: String,
        delta: String,
        field: String? = "text",
        sessionID: String
    ) -> OpencodeEvent {
        let fieldJSON = field.map { ",\"field\":\"\($0)\"" } ?? ""
        let json = """
        {"payload":{"type":"message.part.delta","properties":{"sessionID":"\(sessionID)","messageID":"\(messageID)","partID":"\(partID)"\(fieldJSON),"delta":"\(delta)"}}}
        """
        return EventDomainAdapter().parseEvent(dataLine: json)!
    }

    private func eventWithToolPart(partID: String, messageID: String, tool: String, status: String, title: String? = nil, sessionID: String) -> OpencodeEvent {
        let titleJson = title.map { ", \"title\": \"\($0)\"" } ?? ""
        let json = """
        {"payload":{"type":"message.part.updated","properties":{"part":{"id":"\(partID)","messageID":"\(messageID)","sessionID":"\(sessionID)","type":"tool","tool":"\(tool)","state":{"status":"\(status)"\(titleJson)}}}}}
        """
        return EventDomainAdapter().parseEvent(dataLine: json)!
    }
}

// MARK: - Helpers

private final class MessageCollector: @unchecked Sendable {
    private(set) var emissions: [[Message]] = []

    func collect(_ stream: AsyncStream<MessageListUpdate>) async {
        for await update in stream {
            if case .messages(let messages) = update {
                emissions.append(messages)
            }
        }
    }

    func latest() -> [Message]? {
        emissions.last
    }
}

private final class DisplayMessageCollector: @unchecked Sendable {
    private(set) var emissions: [[DisplayMessage]] = []

    func collect(_ stream: AsyncStream<DisplayMessageListUpdate>) async {
        for await update in stream {
            if case .messages(let messages) = update {
                emissions.append(messages)
            }
        }
    }

    func latest() -> [DisplayMessage]? {
        emissions.last
    }
}

@MainActor
private func makeMessageSync(
    repo: FakeLiveMessageRepository,
    eventStream: FakeForegroundEventStream,
    pollIntervalMs: Int = LiveStoreRefreshPolicy.workingPollIntervalMs,
    eventStagingCapacity: Int = messageEventStagingCapacity
) -> MessageSync {
    MessageSync(
        listMessages: { sessionID, directory in
            try await repo.listMessages(sessionID: sessionID, directory: directory)
        },
        store: InMemoryMessageStore(),
        eventStream: eventStream,
        pollIntervalMs: pollIntervalMs,
        eventStagingCapacity: eventStagingCapacity
    )
}

private final class FakeLiveMessageRepository: MessageRepository, @unchecked Sendable {
    var messages: [Message] = [
        Message(id: "msg-1", role: .user, text: "Hello", parts: [
            .text(TextPart(text: "Hello", isStreaming: false)),
        ], model: nil, mode: nil, sentAtEpochMs: nil),
    ]
    var listMessagesCallCount = 0
    var shouldFailList = false
    private var holdNextList = false
    private var blockedListContinuation: CheckedContinuation<Void, Never>?

    var isListBlocked: Bool {
        blockedListContinuation != nil
    }

    func holdNextListMessages() {
        holdNextList = true
    }

    func resumeListMessages() {
        blockedListContinuation?.resume()
        blockedListContinuation = nil
    }

    func observeMessages(sessionID: String, directory: String?) -> AsyncStream<MessageListUpdate> {
        AsyncStream { _ in }
    }

    func observeDisplayMessages(sessionID: String, directory: String?) -> AsyncStream<DisplayMessageListUpdate> {
        AsyncStream { _ in }
    }

    func refreshMessages(sessionID: String, directory: String?) async throws -> [Message] {
        try await listMessages(sessionID: sessionID, directory: directory)
    }

    func refreshDisplayMessages(sessionID: String, directory: String?) async throws -> [DisplayMessage] {
        let messages = try await refreshMessages(sessionID: sessionID, directory: directory)
        return messages.map { DisplayMessage(message: $0) }
    }

    func prepareOptimisticUserMessage(id: String, text: String) async -> DisplayMessage {
        DisplayMessage(
            message: Message(id: id, role: .user, text: text, model: nil, mode: nil, sentAtEpochMs: nil)
        )
    }

    func clearMessageCache() {}

    func listMessages(sessionID: String, directory: String?) async throws -> [Message] {
        listMessagesCallCount += 1
        if holdNextList {
            holdNextList = false
            await withCheckedContinuation { continuation in
                blockedListContinuation = continuation
            }
        }
        if shouldFailList {
            throw URLError(.cannotConnectToHost)
        }
        return messages
    }
    func getMessage(sessionID: String, messageID: String, directory: String?) async throws -> Message { fatalError() }
    func sendMessage(sessionID: String, text: String, directory: String?) async throws -> Message { fatalError() }
    func sendPromptAsync(sessionID: String, options: PromptSendOptions, directory: String?) async throws {}
}
