import Foundation
import Testing
@testable import Hex_Code

/// One notice at a time; its action hides it, then runs.
@MainActor
struct HexToasterTests {
    @Test func aNewNoticeReplacesTheOneShowing() {
        let toasts = HexToaster()
        toasts.show(HexToastItem(tone: .info, title: "First"))
        toasts.show(HexToastItem(tone: .steer, title: "Second"))
        #expect(toasts.current?.title == "Second")
    }

    @Test func theActionHidesTheNoticeThenRuns() {
        let toasts = HexToaster()
        var ran = false
        toasts.show(HexToastItem(tone: .undo, title: "Chunk deleted", action: .init(title: "Undo", icon: .undo))) {
            ran = true
        }
        toasts.performAction()
        #expect(ran)
        #expect(toasts.current == nil)
    }

    @Test func itHidesAfterItsDuration() async throws {
        let toasts = HexToaster()
        toasts.show(HexToastItem(tone: .info, title: "Brief", duration: 0.05))
        try await Task.sleep(for: .milliseconds(300))
        #expect(toasts.current == nil)
    }
}
