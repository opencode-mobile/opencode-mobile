import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct DefaultMessageLiveStoreTests {
    private let decoder = JSONDecoder()

    @Test func initialLoad_emitsMessagesFromRepository() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let store = DefaultMessageLiveStore(messageRepository: repo, eventStream: eventStream)
        let collector = MessageCollector()
        let task = Task { await collector.collect(store.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        try? await Task.sleep(for: .milliseconds(500))

        #expect(collector.emissions.count >= 1)
        #expect(collector.latest()?.first?.text == "Hello")
    }

    @Test func messageUpdated_suppressesEmptyNewSlot() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let store = DefaultMessageLiveStore(messageRepository: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(store.observeMessages(sessionID: "ses-1", directory: "/repo")) }
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
        let store = DefaultMessageLiveStore(messageRepository: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(store.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        try? await Task.sleep(for: .milliseconds(100))

        // Create a user message slot first.
        eventStream.emit(eventWithInfo(id: "msg-2", role: "user", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(50))

        // Add a text part to it.
        let partEvent = eventWithTextPart(partID: "prt-1", messageID: "msg-2", text: "Hello from part", sessionID: "ses-1")
        eventStream.emit(partEvent)
        try? await Task.sleep(for: .milliseconds(200))
        task.cancel()

        let last = collector.latest()
        let msg2 = last?.first(where: { $0.id == "msg-2" })
        #expect(msg2?.text.contains("Hello from part") == true)
        #expect(msg2?.parts.count == 1)
    }

    @Test func messagePartUpdated_textDelta_marksStreaming() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let store = DefaultMessageLiveStore(messageRepository: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(store.observeMessages(sessionID: "ses-1", directory: "/repo")) }
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
        #expect(toolParts?.first?.statusText == "\u{2713} webfetch")
    }

    @Test func messagePartUpdated_textDeltaAppendsToExistingPart() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let store = DefaultMessageLiveStore(messageRepository: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(store.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        try? await Task.sleep(for: .milliseconds(100))

        eventStream.emit(eventWithInfo(id: "msg-2", role: "assistant", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(50))

        eventStream.emit(eventWithTextDelta(partID: "prt-1", messageID: "msg-2", delta: "Hel", sessionID: "ses-1"))
        eventStream.emit(eventWithTextDelta(partID: "prt-1", messageID: "msg-2", delta: "lo", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(300))
        task.cancel()

        let msg2 = collector.latest()?.first(where: { $0.id == "msg-2" })
        #expect(msg2?.text == "Hello")
        guard case .text(let textPart) = msg2?.parts.first else {
            Issue.record("Expected text part")
            return
        }
        #expect(textPart.isStreaming == true)
    }

    @Test func messagePartUpdated_preservesPartOrderAcrossReplacement() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let store = DefaultMessageLiveStore(messageRepository: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(store.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        try? await Task.sleep(for: .milliseconds(100))

        eventStream.emit(eventWithInfo(id: "msg-2", role: "assistant", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(50))

        eventStream.emit(eventWithTextPart(partID: "prt-1", messageID: "msg-2", text: "First", sessionID: "ses-1"))
        eventStream.emit(eventWithToolPart(partID: "prt-t1", messageID: "msg-2", tool: "bash", status: "pending", sessionID: "ses-1"))
        eventStream.emit(eventWithTextPart(partID: "prt-2", messageID: "msg-2", text: "Second", sessionID: "ses-1"))
        eventStream.emit(eventWithToolPart(partID: "prt-t1", messageID: "msg-2", tool: "bash", status: "completed", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(300))
        task.cancel()

        let msg2 = collector.latest()?.first(where: { $0.id == "msg-2" })
        #expect(msg2?.text == "First\n\u{2713} bash\nSecond")
        #expect(msg2?.parts.count == 3)
    }

    @Test func messageRemoved_deletesSlotAndEmits() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let store = DefaultMessageLiveStore(messageRepository: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(store.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        try? await Task.sleep(for: .milliseconds(300))

        // Remove msg-1.
        let json = #"{"payload":{"type":"message.removed","properties":{"sessionID":"ses-1","messageID":"msg-1"}}}"#
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(dataLine: json)!
        eventStream.emit(event)
        try? await Task.sleep(for: .milliseconds(500))
        task.cancel()

        let last = collector.latest()
        #expect(last?.isEmpty == true)
    }

    @Test func messagePartRemoved_updatesText() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let store = DefaultMessageLiveStore(messageRepository: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(store.observeMessages(sessionID: "ses-1", directory: "/repo")) }
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
        let store = DefaultMessageLiveStore(messageRepository: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(store.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        try? await Task.sleep(for: .milliseconds(300))

        repo.messages = [
            Message(id: "msg-1", role: .user, text: "After reconnect", parts: [], model: nil, mode: nil, sentAtEpochMs: nil),
        ]

        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(dataLine: #"{"payload":{"type":"stream.reconnected","properties":{}}}"#)!
        eventStream.emit(event)
        try? await Task.sleep(for: .milliseconds(800))
        task.cancel()

        let last = collector.latest()
        #expect(last?.first?.text == "After reconnect")
    }

    @Test func eventsForOtherSessionsAreIgnored() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let store = DefaultMessageLiveStore(messageRepository: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(store.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        try? await Task.sleep(for: .milliseconds(300))

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

    private func eventWithTextDelta(partID: String, messageID: String, delta: String, sessionID: String) -> OpencodeEvent {
        let json = """
        {"payload":{"type":"message.part.updated","properties":{"part":{"id":"\(partID)","messageID":"\(messageID)","sessionID":"\(sessionID)","type":"text"},"delta":"\(delta)"}}}
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

    func collect(_ stream: AsyncStream<[Message]>) async {
        for await messages in stream {
            emissions.append(messages)
        }
    }

    func latest() -> [Message]? {
        emissions.last
    }
}

private final class FakeLiveMessageRepository: MessageRepository, @unchecked Sendable {
    var messages: [Message] = [
        Message(id: "msg-1", role: .user, text: "Hello", parts: [
            .text(TextPart(text: "Hello", isStreaming: false)),
        ], model: nil, mode: nil, sentAtEpochMs: nil),
    ]

    func listMessages(sessionID: String, directory: String?) async throws -> [Message] { messages }
    func getMessage(sessionID: String, messageID: String, directory: String?) async throws -> Message { fatalError() }
    func sendMessage(sessionID: String, text: String, directory: String?) async throws -> Message { fatalError() }
    func sendPromptAsync(sessionID: String, options: PromptSendOptions, directory: String?) async throws {}
}
