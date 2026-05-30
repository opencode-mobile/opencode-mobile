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
                self?.connectionStateContinuations.removeValue(forKey: id)
            }
        }
    }

    func events() -> AsyncStream<OpencodeEvent> {
        let id = UUID()
        return AsyncStream { continuation in
            continuations[id] = continuation
            continuation.onTermination = { [weak self] _ in
                self?.continuations.removeValue(forKey: id)
            }
        }
    }

    func start() {
        connectionState = .connected
        for continuation in connectionStateContinuations.values {
            continuation.yield(.connected)
        }
    }

    func stop() {
        connectionState = .idle
        for continuation in connectionStateContinuations.values {
            continuation.yield(.idle)
        }
    }

    func emit(_ event: OpencodeEvent) {
        for continuation in continuations.values {
            continuation.yield(event)
        }
    }
}
