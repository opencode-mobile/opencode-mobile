import Foundation
import Testing
@testable import Hex_Code

/// The screenshot samples (`TodaySessionDemo`): the `artifact` preset's tile and the locked take.
@MainActor
struct ScreenshotSampleTests {
    @Test func theArtifactPresetShowsItsArtifactOnTheCard() async throws {
        let preset = TodaySessionDemo.artifact
        let session = preset.make(toasts: HexToaster())
        await preset.start(session)
        for _ in 0 ..< 100 where session.summary.summary?.asCard.artifact == nil {
            try await Task.sleep(for: .milliseconds(20))
        }
        #expect(session.summary.summary?.asCard.artifact == CardArtifact(id: "art_demo", title: "Sign-in speed"))
    }

    @Test func otherPresetsHaveNoArtifact() async throws {
        let preset = TodaySessionDemo.plain
        let session = preset.make(toasts: HexToaster())
        await preset.start(session)
        for _ in 0 ..< 100 where session.summary.summary == nil {
            try await Task.sleep(for: .milliseconds(20))
        }
        #expect(session.summary.summary != nil)
        #expect(session.summary.summary?.asCard.artifact == nil)
    }

    @Test func theSampleLockedTakeListensWithItsTextSoFar() async {
        let home = HomeModel(recorder: FakeRecorder(), speech: FakeSpeech(), sleep: { _ in })
        home.showSampleLockedTake(DemoTake.lockedText, elapsed: DemoTake.lockedElapsed)
        #expect(home.isRecording)
        #expect(home.locked)
        #expect(home.dock == .lockedTake)
        #expect(home.draft.chunks.count == 1)
        #expect(home.draft.chunks.first?.state == .listening)
        #expect(home.draft.chunks.first?.partial == DemoTake.lockedText)
        #expect(home.takeStartedAt.map { Date().timeIntervalSince($0) } ?? 0 >= DemoTake.lockedElapsed)
        #expect((home.level ?? 0) >= 0 && (home.level ?? 2) <= 1)

        // ✓ keeps the text so far.
        await home.finishLockedTake()
        #expect(!home.isRecording)
        #expect(home.dock == .standard)
        #expect(home.draft.text == DemoTake.lockedText)
    }
}
