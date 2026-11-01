import Foundation
import Testing
@testable import OpenCode_Mobile

struct PendingPromptsStripGateTests {
    private let waiting = PendingPrompt(id: "msg-1", text: "Check the flash", delivery: .steer, isSettling: false)
    private let settling = PendingPrompt(id: "pending-1", text: "Hold this", delivery: .queue, isSettling: true)

    @Test func idleSendInboxBlip_neverMountsStrip() {
        var gate = PendingPromptsStripGate()

        let enqueued = gate.resolve([waiting], nowMs: 1_000)
        let delivered = gate.resolve([], nowMs: 1_006)

        #expect(enqueued.prompts.isEmpty)
        #expect(enqueued.wakeAfterMs == PendingPromptsStripGate.graceMilliseconds)
        #expect(delivered == .init(prompts: [], wakeAfterMs: nil))
    }

    @Test func serverWaitingPrompt_mountsAfterGrace() {
        var gate = PendingPromptsStripGate()

        _ = gate.resolve([waiting], nowMs: 1_000)
        let early = gate.resolve([waiting], nowMs: 1_200)
        let due = gate.resolve([waiting], nowMs: 1_300)

        #expect(early.prompts.isEmpty)
        #expect(early.wakeAfterMs == 100)
        #expect(due == .init(prompts: [waiting], wakeAfterMs: nil))
    }

    @Test func ownBusySend_mountsAtOnce() {
        var gate = PendingPromptsStripGate()

        let resolution = gate.resolve([settling], nowMs: 1_000)

        #expect(resolution == .init(prompts: [settling], wakeAfterMs: nil))
    }

    @Test func mountedStrip_staysThroughEchoHandoff() {
        var gate = PendingPromptsStripGate()

        _ = gate.resolve([settling], nowMs: 1_000)
        let echoed = gate.resolve([waiting], nowMs: 1_010)

        #expect(echoed == .init(prompts: [waiting], wakeAfterMs: nil))
    }

    @Test func emptyList_resetsGrace() {
        var gate = PendingPromptsStripGate()

        _ = gate.resolve([waiting], nowMs: 1_000)
        _ = gate.resolve([waiting], nowMs: 1_400)
        _ = gate.resolve([], nowMs: 1_500)
        let next = gate.resolve([waiting], nowMs: 2_000)

        #expect(next.prompts.isEmpty)
        #expect(next.wakeAfterMs == PendingPromptsStripGate.graceMilliseconds)
    }
}
