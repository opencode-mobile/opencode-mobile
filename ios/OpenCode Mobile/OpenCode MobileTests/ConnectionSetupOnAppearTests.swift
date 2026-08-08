import Testing
@testable import OpenCode_Mobile

struct ConnectionSetupOnAppearTests {
    @Test func consume_isSingleShotAcrossTaskReruns() {
        var pending = true
        var presentCount = 0

        func runHomeTask() {
            let decision = ConnectionSetupOnAppear.consume(pending)
            pending = decision.pending
            if decision.shouldPresent {
                presentCount += 1
            }
        }

        runHomeTask()
        runHomeTask()
        runHomeTask()

        #expect(presentCount == 1)
        #expect(pending == false)
    }

    @Test func consume_whenNotPending_doesNotPresent() {
        let decision = ConnectionSetupOnAppear.consume(false)
        #expect(decision.shouldPresent == false)
        #expect(decision.pending == false)
    }
}
