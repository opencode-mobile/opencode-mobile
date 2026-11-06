import Foundation
import Testing
@testable import Hex_Code

/// The talk button's meter (app plan, "Dictation" › Meter).
struct MeterLevelTests {
    @Test func silenceRestsAsDots() {
        #expect(HexMeterLevel.height(dBFS: -160) == 0)
        #expect(HexMeterLevel.height(dBFS: -50) == 0)
        #expect(HexMeterLevel.height(dBFS: -.infinity) == 0)
        #expect(HexMeterLevel.height(dBFS: .nan) == 0)
    }

    @Test func fullFromMinus18() {
        #expect(HexMeterLevel.height(dBFS: -18) == 1)
        #expect(HexMeterLevel.height(dBFS: 0) == 1)
    }

    @Test func easeOutLiftsQuietSpeech() {
        // Half way in dB is three quarters of the height.
        #expect(abs(HexMeterLevel.height(dBFS: -34) - 0.75) < 0.0001)
        // Speech around −40 to −24 dBFS fills about half to nine tenths.
        #expect(HexMeterLevel.height(dBFS: -40) > 0.5)
        #expect(HexMeterLevel.height(dBFS: -24) > 0.9)
        // Always rising.
        let heights = stride(from: -50.0, through: -18, by: 2).map { HexMeterLevel.height(dBFS: $0) }
        #expect(heights == heights.sorted())
    }

    @Test func fastAttackSlowRelease() {
        // After 40 ms the rise has covered about 63 % of the way; the fall only about 17 %.
        let up = HexMeterLevel.follow(0, towards: 1, elapsed: 0.04)
        let down = HexMeterLevel.follow(1, towards: 0, elapsed: 0.04)
        #expect(abs(up - 0.632) < 0.01)
        #expect(abs((1 - down) - 0.166) < 0.01)
        // At 30 fps a peak is most of the way up in two frames and takes a while to settle.
        var level = 0.0
        for _ in 0 ..< 2 { level = HexMeterLevel.follow(level, towards: 1, elapsed: 1.0 / 30) }
        #expect(level > 0.8)
        for _ in 0 ..< 6 { level = HexMeterLevel.follow(level, towards: 0, elapsed: 1.0 / 30) }
        #expect(level > 0.3)
    }

    @Test func neighbouringBarsVaryALittle() {
        let values = (0 ..< 36).map(HexMeterLevel.variation)
        #expect(values.allSatisfy { $0 >= 0.88 && $0 <= 1.12 })
        #expect(Set(values.map { ($0 * 100).rounded() }).count > 10)
        #expect(HexMeterLevel.variation(bar: 5) == HexMeterLevel.variation(bar: 5))
    }
}

/// VoiceOver can't hold and drag, so the talk button has actions instead.
@MainActor
struct TalkButtonAccessibilityTests {
    @Test func activateStartsAHandsFreeTakeAndEndsIt() async {
        let home = HomeModel(recorder: FakeRecorder(), speech: FakeSpeech())
        await home.toggleHandsFreeTake()
        #expect(home.isRecording && home.locked)
        await home.toggleHandsFreeTake()
        #expect(!home.isRecording && !home.locked)
        #expect(home.draft.chunks.map(\.text) == ["hello there"])
    }

    @Test func dropForgetsTheTake() async {
        let recorder = FakeRecorder()
        let home = HomeModel(recorder: recorder, speech: FakeSpeech())
        await home.toggleHandsFreeTake()
        await home.dropCurrentTake()
        #expect(home.draft.isEmpty)
        #expect(!home.locked)
        #expect(recorder.cancelled == 1)
    }
}

/// The dock around the talk button (app plan, "Dictation" › Locked take).
@MainActor
struct DockTests {
    @Test func theDockSwapsOnlyForALockedTake() async {
        let home = HomeModel(recorder: FakeRecorder(), speech: FakeSpeech())
        // Idle.
        #expect(home.dock == .standard)
        // Holding.
        home.pressBegan()
        #expect(home.dock == .standard)
        // Locked.
        home.pressMoved(dx: TalkDrag.armDistance)
        await home.pressEnded()
        #expect(home.locked && home.dock == .lockedTake)
        // ✓ finishes it into the draft; the dock comes back.
        await home.finishLockedTake()
        #expect(home.dock == .standard)
        #expect(home.draft.chunks.map(\.text) == ["hello there"])
        // A draft.
        #expect(!home.draft.isEmpty && home.dock == .standard)
    }

    @Test func dropForgetsTheLockedTakeWithTheNotice() async {
        let home = HomeModel(recorder: FakeRecorder(), speech: FakeSpeech())
        await home.toggleHandsFreeTake()
        #expect(home.dock == .lockedTake)
        await home.dropLockedTake()
        #expect(home.dock == .standard)
        #expect(home.draft.isEmpty)
        #expect(home.toast?.title == "Take dropped")
    }

    @Test func reRecordingHandsFreeUsesTheSameDock() async {
        let home = HomeModel(recorder: FakeRecorder(), speech: FakeSpeech())
        await home.toggleHandsFreeTake()
        await home.toggleHandsFreeTake()
        #expect(home.draft.chunks.count == 1)
        guard let chunk = home.draft.chunks.first else { return }
        home.toggleChunk(chunk.id)
        home.rerecordSelected()
        #expect(home.dock == .lockedTake)
    }
}
