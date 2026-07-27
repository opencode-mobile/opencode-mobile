import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct EventDomainAdapterTests {
    @Test func parseEvent_readsWrappedPayload() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(dataLine: #"{"payload":{"type":"message.updated","properties":{"sessionID":"ses-1"}}}"#)
        #expect(event?.type == "message.updated")
        #expect(event?.sessionID == "ses-1")
    }

    @Test func parseEvent_readsSessionIDFromMessageInfo() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"payload":{"type":"message.updated","properties":{"info":{"sessionID":"ses-1","id":"msg-1","role":"user"}}}}"#
        )
        #expect(event?.type == "message.updated")
        #expect(event?.sessionID == "ses-1")
    }

    @Test func parseEvent_readsSessionIDFromMessagePart() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"payload":{"type":"message.part.updated","properties":{"part":{"sessionID":"ses-1","id":"prt-1","type":"text"}}}}"#
        )
        #expect(event?.type == "message.part.updated")
        #expect(event?.sessionID == "ses-1")
    }

    @Test func parseEvent_readsMessagePartDelta() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"payload":{"type":"message.part.delta","properties":{"sessionID":"ses-1","messageID":"msg-1","partID":"prt-1","field":"text","delta":"Hi"}}}"#
        )
        #expect(event?.type == "message.part.delta")
        #expect(event?.sessionID == "ses-1")
        #expect(event?.triggersMessageRefresh == true)
    }

    @Test func parseEvent_readsServerConnected() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"payload":{"type":"server.connected","properties":{}}}"#
        )
        #expect(event?.type == "server.connected")
        #expect(event?.sessionID == nil)
    }

    @Test func parseEvent_readsDirectoryWrappedEnvelope() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"directory":"/repo","payload":{"type":"message.updated","properties":{"info":{"sessionID":"ses-1"}}}}"#
        )
        #expect(event?.type == "message.updated")
        #expect(event?.sessionID == "ses-1")
    }

    @Test func statusLabel_mapsBusyAndRetry() {
        let adapter = EventDomainAdapter()
        let busy = adapter.statusLabel(from: SessionStatus(sessionID: "1", raw: ["type": "busy"]))
        let retry = adapter.statusLabel(from: SessionStatus(sessionID: "1", raw: ["type": "retry"]))
        let idle = adapter.statusLabel(from: SessionStatus(sessionID: "1", raw: ["type": "idle"]))
        #expect(busy == "Working")
        #expect(retry == "Retrying")
        #expect(idle == nil)
    }

    @Test func sessionTitleUpdate_readsInfoTitle() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"payload":{"type":"session.updated","properties":{"info":{"id":"ses-1","title":"Live title"}}}}"#
        )
        let update = adapter.sessionTitleUpdate(from: event!)
        #expect(update?.sessionID == "ses-1")
        #expect(update?.title == "Live title")
    }

    @Test func sessionTitleUpdate_ignoresNonUpdatedEvents() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"payload":{"type":"session.created","properties":{"info":{"id":"ses-1","title":"New"}}}}"#
        )
        #expect(adapter.sessionTitleUpdate(from: event!) == nil)
    }
}

final class FakeForegroundEventStream: ForegroundEventStream, @unchecked Sendable {
    var connectionState: EventStreamConnectionState = .idle
    private var continuations: [UUID: AsyncStream<OpencodeEvent>.Continuation] = [:]
    private var connectionStateContinuations: [UUID: AsyncStream<EventStreamConnectionState>.Continuation] = [:]

    func connectionStateUpdates() -> AsyncStream<EventStreamConnectionState> {
        let id = UUID()
        return AsyncStream { continuation in
            continuation.yield(connectionState)
            connectionStateContinuations[id] = continuation
            continuation.onTermination = { [weak self] _ in
                Task { @MainActor [weak self] in
                    self?.connectionStateContinuations.removeValue(forKey: id)
                }
            }
        }
    }

    func events() -> AsyncStream<OpencodeEvent> {
        let id = UUID()
        return AsyncStream { continuation in
            continuations[id] = continuation
            continuation.onTermination = { [weak self] _ in
                Task { @MainActor [weak self] in
                    self?.continuations.removeValue(forKey: id)
                }
            }
        }
    }

    func start() {
        setConnectionState(.connected)
    }

    func stop() {
        setConnectionState(.idle)
    }

    func setConnectionState(_ state: EventStreamConnectionState) {
        connectionState = state
        for continuation in connectionStateContinuations.values {
            continuation.yield(state)
        }
    }

    func emit(_ event: OpencodeEvent) {
        for continuation in continuations.values {
            continuation.yield(event)
        }
    }
}
