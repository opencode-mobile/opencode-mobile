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

    @Test func parseEvent_readsV2NativeEnvelope() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(dataLine: #"{"id":"evt_1","type":"server.connected","data":{}}"#)
        #expect(event?.type == "server.connected")
        #expect(event?.sessionID == nil)
    }

    @Test func parseEvent_projectsV2TextDeltaIntoMessagePartDelta() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"id":"evt_2","type":"session.next.text.delta","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","textID":"prt-1","delta":"Hi"}}"#
        )
        #expect(event?.type == "message.part.delta")
        #expect(event?.sessionID == "ses-1")
        #expect(event?.rawProperties?["messageID"]?.stringValue == "msg-2")
        #expect(event?.rawProperties?["partID"]?.stringValue == "prt-1")
        #expect(event?.rawProperties?["field"]?.stringValue == "text")
        #expect(event?.rawProperties?["delta"]?.stringValue == "Hi")
        #expect(event?.triggersMessageRefresh == true)
    }

    @Test func parseEvents_projectsStepStartedIntoAssistantMessageAndBusyStatus() {
        let adapter = EventDomainAdapter()
        let events = adapter.parseEvents(
            dataLine: #"{"type":"session.next.step.started","data":{"sessionID":"ses-1","assistantMessageID":"msg-2"}}"#
        )
        #expect(events.map(\.type) == ["message.updated", "session.status"])
        #expect(events[0].sessionID == "ses-1")
        #expect(events[0].rawProperties?["info"]?.dictionaryValue?["role"]?.stringValue == "assistant")
        #expect(events[1].rawProperties?["status"]?.dictionaryValue?["type"]?.stringValue == "busy")
    }

    @Test func parseEvent_projectsPromptAdmittedIntoUserMessageUpdated() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"type":"session.next.prompt.admitted","data":{"sessionID":"ses-1","messageID":"msg-1"}}"#
        )
        #expect(event?.type == "message.updated")
        #expect(event?.sessionID == "ses-1")
        #expect(event?.rawProperties?["info"]?.dictionaryValue?["role"]?.stringValue == "user")
        #expect(event?.rawProperties?["info"]?.dictionaryValue?["id"]?.stringValue == "msg-1")
    }

    @Test func parseEvents_projectsStepFailedIntoSessionErrorWithV2Message() {
        let adapter = EventDomainAdapter()
        let events = adapter.parseEvents(
            dataLine: #"{"type":"session.next.step.failed","data":{"sessionID":"ses-1","error":{"type":"ProviderError","message":"model overloaded"}}}"#
        )
        #expect(events.map(\.type) == ["session.error", "session.idle"])
        #expect(events[0].sessionID == "ses-1")
        #expect(events[0].sessionErrorMessage == "model overloaded")
    }

    @Test func parseEvent_projectsLiveExecutionStartedIntoBusyStatus() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"id":"evt_3","type":"session.execution.started","data":{"sessionID":"ses-1"}}"#
        )
        #expect(event?.type == "session.status")
        #expect(event?.sessionID == "ses-1")
        #expect(event?.rawProperties?["status"]?.dictionaryValue?["type"]?.stringValue == "busy")
        #expect(event?.triggersStatusRefresh == true)
    }

    @Test func parseEvent_projectsLiveExecutionSucceededIntoIdle() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"type":"session.execution.succeeded","data":{"sessionID":"ses-1"}}"#
        )
        #expect(event?.type == "session.idle")
        #expect(event?.sessionID == "ses-1")
        #expect(event?.triggersStatusRefresh == true)
    }

    @Test func parseEvent_doesNotTreatLiveStepEndedAsIdle() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"type":"session.step.ended","data":{"sessionID":"ses-1","assistantMessageID":"msg-2"}}"#
        )
        #expect(event?.type == "session.step.ended")
        #expect(event?.sessionID == "ses-1")
        #expect(event?.triggersStatusRefresh == false)
    }

    @Test func parseEvents_projectsStepEndedIntoAssistantMessageAndActiveSnapshot() {
        let adapter = EventDomainAdapter()
        let events = adapter.parseEvents(
            dataLine: #"{"type":"session.next.step.ended","data":{"sessionID":"ses-1","assistantMessageID":"msg-2"}}"#
        )
        #expect(events.map(\.type) == ["message.updated", "session.active.snapshot"])
        #expect(events[0].sessionID == "ses-1")
        #expect(events[0].rawProperties?["info"]?.dictionaryValue?["role"]?.stringValue == "assistant")
        #expect(events[1].triggersStatusRefresh == true)
    }

    @Test func parseEvent_readsOfficialSSEEnvelopeWithEventAndJSONStringData() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"id":"evt_9","event":"session.next.tool.called","data":"{\"sessionID\":\"ses-1\",\"assistantMessageID\":\"msg-2\",\"callID\":\"call-1\",\"tool\":\"bash\",\"input\":{}}"}"#
        )
        #expect(event?.type == "message.part.updated")
        #expect(event?.sessionID == "ses-1")
        #expect(event?.rawProperties?["part"]?.dictionaryValue?["tool"]?.stringValue == "bash")
        #expect(event?.rawProperties?["part"]?.dictionaryValue?["state"]?.dictionaryValue?["status"]?.stringValue == "running")
    }

    @Test func parseEvent_projectsLiveTextDeltaUsingOrdinalPartID() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"type":"session.text.delta","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","ordinal":0,"delta":"Hi"}}"#
        )
        #expect(event?.type == "message.part.delta")
        #expect(event?.sessionID == "ses-1")
        #expect(event?.rawProperties?["messageID"]?.stringValue == "msg-2")
        #expect(event?.rawProperties?["partID"]?.stringValue == "text-0")
        #expect(event?.rawProperties?["delta"]?.stringValue == "Hi")
        #expect(event?.triggersMessageRefresh == true)
    }

    @Test func parseEvent_projectsLiveRenamedIntoSessionUpdated() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"type":"session.renamed","data":{"sessionID":"ses-1","title":"Testing conversation"}}"#
        )
        #expect(event?.type == "session.updated")
        #expect(event?.sessionID == "ses-1")
        #expect(event?.rawProperties?["info"]?.dictionaryValue?["title"]?.stringValue == "Testing conversation")
    }

    @Test func parseEvent_remapsPermissionV2Asked() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"type":"permission.v2.asked","data":{"sessionID":"ses-1"}}"#
        )
        #expect(event?.type == "permission.asked")
        #expect(event?.sessionID == "ses-1")
    }

    @Test func parseEvent_readsV2SameNamedLegacyMessageUpdated() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"type":"message.updated","data":{"sessionID":"ses-1","info":{"id":"msg-1","role":"user"}}}"#
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

    @Test func sessionStatus_readsBusyPayload() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"payload":{"type":"session.status","properties":{"sessionID":"ses-1","status":{"type":"busy"}}}}"#
        )
        let status = adapter.sessionStatus(from: event!)
        #expect(status?.sessionID == "ses-1")
        #expect(status?.raw["type"] == "busy")
        #expect(adapter.statusLabel(from: status!) == "Working")
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
    private let lock = NSLock()
    private var _connectionState: EventStreamConnectionState = .idle
    var connectionState: EventStreamConnectionState {
        lock.lock()
        defer { lock.unlock() }
        return _connectionState
    }
    private var continuations: [UUID: AsyncStream<OpencodeEvent>.Continuation] = [:]
    private var connectionStateContinuations: [UUID: AsyncStream<EventStreamConnectionState>.Continuation] = [:]

    func connectionStateUpdates() -> AsyncStream<EventStreamConnectionState> {
        let id = UUID()
        return AsyncStream { continuation in
            self.lock.lock()
            let current = self._connectionState
            self.connectionStateContinuations[id] = continuation
            self.lock.unlock()
            continuation.yield(current)
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                self.lock.lock()
                self.connectionStateContinuations.removeValue(forKey: id)
                self.lock.unlock()
            }
        }
    }

    func events() -> AsyncStream<OpencodeEvent> {
        let id = UUID()
        return AsyncStream { continuation in
            self.lock.lock()
            self.continuations[id] = continuation
            self.lock.unlock()
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                self.lock.lock()
                self.continuations.removeValue(forKey: id)
                self.lock.unlock()
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
        lock.lock()
        _connectionState = state
        let conts = Array(connectionStateContinuations.values)
        lock.unlock()
        for continuation in conts {
            continuation.yield(state)
        }
    }

    func emit(_ event: OpencodeEvent) {
        lock.lock()
        let conts = Array(continuations.values)
        lock.unlock()
        for continuation in conts {
            continuation.yield(event)
        }
    }
}
