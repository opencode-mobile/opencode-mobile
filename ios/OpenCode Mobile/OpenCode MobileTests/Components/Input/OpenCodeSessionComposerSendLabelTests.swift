import Testing
@testable import OpenCode_Mobile

struct OpenCodeSessionComposerSendLabelTests {
    @Test func idleSession_isPlainSend() {
        #expect(OpenCodeSessionComposer.sendLabel(followUpDelivery: .steer, hasActiveWork: false) == "Send")
        #expect(OpenCodeSessionComposer.sendLabel(followUpDelivery: .queue, hasActiveWork: false) == "Send")
        #expect(OpenCodeSessionComposer.sendLabel(followUpDelivery: nil, hasActiveWork: false) == "Send")
    }

    @Test func activeWork_namesTheFollowUpDelivery() {
        #expect(OpenCodeSessionComposer.sendLabel(followUpDelivery: .steer, hasActiveWork: true) == "Send as Steer")
        #expect(OpenCodeSessionComposer.sendLabel(followUpDelivery: .queue, hasActiveWork: true) == "Send as Queue")
        #expect(OpenCodeSessionComposer.sendLabel(followUpDelivery: nil, hasActiveWork: true) == "Send")
    }

    @Test func shellMode_alwaysRunsNow() {
        #expect(OpenCodeSessionComposer.sendLabel(followUpDelivery: nil, hasActiveWork: false, shellMode: true) == "Run shell command")
        #expect(OpenCodeSessionComposer.sendLabel(followUpDelivery: .queue, hasActiveWork: true, shellMode: true) == "Run shell command")
    }
}
