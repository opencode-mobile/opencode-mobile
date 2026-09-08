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
                sessionID: "ses-1",
                epoch: 1
            )
        }
        #expect(staging.currentHealth == .recovering(.overflow))
        #expect(staging.highWaterBytes > 0)
    }

    @Test func enqueue_ignoresNonMessageIntakeTypes() {
        let staging = MessageEventStaging(capacity: 64, byteCapacity: 200)
        staging.enqueue(
            OpencodeEvent(type: "permission.asked", sessionID: "ses-1", rawProperties: nil),
            sessionID: "ses-1",
            epoch: 1
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
            sessionID: "ses-1",
            epoch: 1
        )
        let item = await waiter.value
        guard case .event(let event, _, _)? = item else {
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
