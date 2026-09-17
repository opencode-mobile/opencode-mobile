import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite struct MessageEventStagingTests {
    @Test func enqueue_byteCapacityOverflow_entersRecovering() {
        let staging = MessageEventStaging(capacity: 64, byteCapacity: 200)
        let delta = String(repeating: "x", count: 80)
        for _ in 0..<8 {
            staging.enqueue(
                eventWithPartDelta(partID: "prt-1", messageID: "msg-1", delta: delta, sessionID: "ses-1"),
                sessionID: "ses-1"
            )
        }
        #expect(staging.currentHealth == .recovering(.overflow))
        #expect(staging.highWaterBytes > 0)
    }

    @Test func enqueue_serverConnectedWhileRecovering_doesNotRaceAuthority() {
        let staging = MessageEventStaging(capacity: 8, byteCapacity: 10_000)
        staging.enterRecovering(.reconnect, wakeConsumer: false)
        staging.beginAuthorityWindow()
        staging.enqueue(
            OpencodeEvent(type: "server.connected", sessionID: nil, rawProperties: nil),
            sessionID: "ses-1"
        )
        #expect(staging.completeAuthorityWindow(.accepted))
        #expect(staging.currentHealth == .healthy)
    }

    @Test func enqueue_sessionEventWhileRecovering_racesAuthority() {
        let staging = MessageEventStaging(capacity: 8, byteCapacity: 10_000)
        staging.enterRecovering(.reconnect, wakeConsumer: false)
        staging.beginAuthorityWindow()
        staging.enqueue(
            eventWithPartDelta(partID: "prt-1", messageID: "msg-1", delta: "x", sessionID: "ses-1"),
            sessionID: "ses-1"
        )
        #expect(!staging.completeAuthorityWindow(.accepted))
        #expect(staging.currentHealth == .recovering(.racedAuthority))
    }

    @Test func enqueue_sessionIdleDuringHealthyAuthorityWindow_racesAuthority() {
        let staging = MessageEventStaging(capacity: 8, byteCapacity: 10_000)
        staging.beginAuthorityWindow()
        staging.enqueue(
            OpencodeEvent(type: "session.idle", sessionID: "ses-1", rawProperties: nil),
            sessionID: "ses-1"
        )
        #expect(!staging.completeAuthorityWindow(.accepted))
        #expect(staging.currentHealth == .recovering(.racedAuthority))
    }

    @Test func enqueue_partDeltaDuringHealthyAuthorityWindow_doesNotRace() {
        let staging = MessageEventStaging(capacity: 8, byteCapacity: 10_000)
        staging.beginAuthorityWindow()
        staging.enqueue(
            eventWithPartDelta(partID: "prt-1", messageID: "msg-1", delta: "x", sessionID: "ses-1"),
            sessionID: "ses-1"
        )
        #expect(staging.completeAuthorityWindow(.accepted))
        #expect(staging.currentHealth == .healthy)
    }

    @Test func clearCatchUpLatch_keepsStaleHealth() {
        let staging = MessageEventStaging(capacity: 8, byteCapacity: 10_000)
        staging.enterStale(.transportUnavailable)
        staging.clearCatchUpLatch()
        #expect(staging.currentHealth == .stale(.transportUnavailable))
    }

    @Test func enqueue_ignoresNonMessageIntakeTypes() {
        let staging = MessageEventStaging(capacity: 64, byteCapacity: 200)
        staging.enqueue(
            OpencodeEvent(type: "permission.asked", sessionID: "ses-1", rawProperties: nil),
            sessionID: "ses-1"
        )
        #expect(staging.currentHealth == .healthy)
        #expect(staging.highWater == 0)
    }

    @Test func dequeue_waiterResumesAfterUnlock() async {
        let staging = MessageEventStaging(capacity: 8, byteCapacity: 10_000)
        let waiter = Task {
            await staging.dequeue()
        }
        // Give the waiter time to park under the lock, then enqueue.
        try? await Task.sleep(for: .milliseconds(20))
        staging.enqueue(
            eventWithPartDelta(partID: "prt-1", messageID: "msg-1", delta: "hi", sessionID: "ses-1"),
            sessionID: "ses-1"
        )
        let item = await waiter.value
        guard case .event(let event, _)? = item else {
            Issue.record("expected staged event")
            return
        }
        #expect(event.type == "message.part.delta")
    }

    private func eventWithPartDelta(
        partID: String,
        messageID: String,
        delta: String,
        sessionID: String
    ) -> OpencodeEvent {
        let json = """
        {"payload":{"type":"message.part.delta","properties":{"sessionID":"\(sessionID)","messageID":"\(messageID)","partID":"\(partID)","field":"text","delta":"\(delta)"}}}
        """
        return EventDomainAdapter().parseEvent(dataLine: json)!
    }
}
