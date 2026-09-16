import Testing
@testable import OpenCode_Mobile

struct SseEventIngressTests {
    private let ingress = SseEventIngress(adapter: EventDomainAdapter())

    @Test func heartbeat_isParsedAndDropped() {
        let result = ingress.accept(#"{"payload":{"type":"server.heartbeat","properties":{}}}"#)
        #expect(result.parsed.map(\.type) == ["server.heartbeat"])
        #expect(result.toEmit.isEmpty)
        #expect(result.parseFailed == false)
    }

    @Test func serverConnected_emitsOnceUntilReset() {
        let first = ingress.accept(#"{"payload":{"type":"server.connected","properties":{}}}"#)
        let second = ingress.accept(#"{"payload":{"type":"server.connected","properties":{}}}"#)
        #expect(first.toEmit.map(\.type) == ["server.connected"])
        #expect(second.toEmit.isEmpty)
        ingress.reset()
        let third = ingress.accept(#"{"payload":{"type":"server.connected","properties":{}}}"#)
        #expect(third.toEmit.map(\.type) == ["server.connected"])
    }

    @Test func duplicateIds_areDroppedUntilReset() {
        let line = #"{"payload":{"id":"evt_1","type":"message.updated","properties":{"sessionID":"ses-1"}}}"#
        let first = ingress.accept(line)
        let second = ingress.accept(line)
        #expect(first.toEmit.map(\.type) == ["message.updated"])
        #expect(second.toEmit.isEmpty)
        ingress.reset()
        let third = ingress.accept(line)
        #expect(third.toEmit.map(\.type) == ["message.updated"])
    }

    @Test func blankLine_isNotAParseFailure() {
        let result = ingress.accept("   ")
        #expect(result.parsed.isEmpty)
        #expect(result.toEmit.isEmpty)
        #expect(result.parseFailed == false)
    }

    @Test func unparseableLine_emitsParseFailed() {
        let result = ingress.accept(#"{"nope":true}"#)
        #expect(result.parseFailed)
        #expect(result.toEmit.map(\.type) == ["sse.parse.failed"])
    }

    @Test func droppedUsageUpdated_isNotAParseFailure() {
        let result = ingress.accept(
            #"{"type":"session.usage.updated","data":{"sessionID":"ses-1","tokens":{"input":120,"output":4}}}"#
        )
        #expect(result.parsed.isEmpty)
        #expect(result.toEmit.isEmpty)
        #expect(result.parseFailed == false)
    }

    @Test func droppedUsageUpdated_v2Envelope_isNotAParseFailure() {
        let result = ingress.accept(
            #"{"payload":{"type":"session.usage.updated","properties":{"sessionID":"ses-1","tokens":{"input":120,"output":4},"cost":0.01}}}"#
        )
        #expect(result.parsed.isEmpty)
        #expect(result.toEmit.isEmpty)
        #expect(result.parseFailed == false)
    }

    @Test func projectedUsageUpdated_emitsMessageUpdated() {
        let result = ingress.accept(
            #"{"type":"session.usage.updated","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","tokens":{"input":120,"output":4,"reasoning":0}}}"#
        )
        #expect(result.parseFailed == false)
        #expect(result.toEmit.map(\.type) == ["message.updated"])
    }
}
