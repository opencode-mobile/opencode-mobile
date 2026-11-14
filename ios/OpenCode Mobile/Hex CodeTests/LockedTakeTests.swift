import Foundation
import Testing
@testable import Hex_Code

/// Where a locked take is cut, from synthetic VAD blocks (app plan, "Locked takes"; spike S3 §5).
struct PauseCutterTests {
    static let block = 4096
    static let speech: Float = 0.97
    static let silence: Float = 0.02

    /// Feeds `count` blocks of `probability`; returns the parts they end.
    private func feed(_ cutter: inout PauseCutter, _ count: Int, _ probability: Float) -> [Range<Int>] {
        (0 ..< count).compactMap { _ in cutter.add(count: Self.block, probability: probability, pauseStart: nil) }
    }

    /// Four silent blocks, the last one carrying FluidAudio's speech end (the start of the first silent block).
    private func pause(_ cutter: inout PauseCutter) -> Range<Int>? {
        let start = cutter.position
        _ = feed(&cutter, 3, Self.silence)
        return cutter.add(count: Self.block, probability: Self.silence, pauseStart: start)
    }

    @Test func aPauseCutsInItsMiddle() {
        var cutter = PauseCutter()
        #expect(feed(&cutter, 10, Self.speech).isEmpty)
        let part = pause(&cutter)
        // Speech ended at block 10; the pause was found at the end of block 13: the cut is halfway, at block 12.
        #expect(part == 0 ..< 12 * Self.block)
        #expect(cutter.lastCut == 12 * Self.block)
    }

    @Test func partsTouchAndComeInOrder() {
        var cutter = PauseCutter()
        var parts: [Range<Int>] = []
        for _ in 0 ..< 3 {
            parts += feed(&cutter, 8, Self.speech)
            parts += [pause(&cutter)].compactMap { $0 }
        }
        #expect(parts.count == 3)
        #expect(parts[0].lowerBound == 0)
        #expect(parts[1].lowerBound == parts[0].upperBound)
        #expect(parts[2].lowerBound == parts[1].upperBound)
    }

    @Test func aPartLongerThan12sEndsAtTheFirstBlockBelow09() {
        var cutter = PauseCutter()
        // 45 blocks (11.5 s) of speech, then a dip before 12 s: no cut yet.
        #expect(feed(&cutter, 45, Self.speech).isEmpty)
        #expect(cutter.add(count: Self.block, probability: 0.8, pauseStart: nil) == nil)
        // Past 12 s, speech at 0.95 and over doesn't cut either.
        #expect(feed(&cutter, 3, 0.95).isEmpty)
        #expect(cutter.position > PauseCutter.maxPart)
        // The first block below 0.9 does, in its middle.
        let start = cutter.position
        #expect(cutter.add(count: Self.block, probability: 0.89, pauseStart: nil) == 0 ..< start + Self.block / 2)
        // The next part starts there, and runs another 12 s before the next forced cut.
        #expect(feed(&cutter, 40, 0.6).isEmpty)
    }

    @Test func runOnSpeechWithNoDipEndsAtTheEarliestQuietestBlockBetween12And15s() {
        var cutter = PauseCutter()
        // The VAD stays sure of speech: 1.00 on every block. Nothing cuts until the part reaches 15 s.
        #expect(feed(&cutter, 58, 1.0).isEmpty)
        #expect(cutter.position < PauseCutter.hardMaxPart)
        // The block that reaches 15 s cuts, back at the first block whose middle is past 12 s (all tie at 1.00).
        let part = cutter.add(count: Self.block, probability: 1.0, pauseStart: nil)
        #expect(part == 0 ..< 47 * Self.block + Self.block / 2)
        #expect(Double(part!.upperBound) / 16_000 > 12 && Double(part!.upperBound) / 16_000 < 12.3)
        // The next part counts from that cut: another 15 s of run-on speech before the next one.
        let next = cutter.lastCut
        var more: [Range<Int>] = []
        while more.isEmpty { more += feed(&cutter, 1, 1.0) }
        #expect(more.first?.lowerBound == next)
        #expect(more.first!.count >= PauseCutter.maxPart && more.first!.count <= PauseCutter.hardMaxPart)
    }

    @Test func withoutADipThePartEndsAtItsQuietestBlockBetween12And15s() {
        var cutter = PauseCutter()
        _ = feed(&cutter, 50, 1.0)
        // Over 12 s but never below 0.9: no dip.
        #expect(cutter.add(count: Self.block, probability: 0.95, pauseStart: nil) == nil)
        _ = feed(&cutter, 3, 1.0)
        #expect(cutter.add(count: Self.block, probability: 0.92, pauseStart: nil) == nil)
        let quietest = cutter.position - Self.block / 2
        // The block that reaches 15 s cuts in the middle of the 0.92 block.
        #expect(feed(&cutter, 4, 0.99) == [0 ..< quietest])
    }

    @Test func aPauseWithNoSpeechSinceTheLastCutDoesNotCut() {
        var cutter = PauseCutter()
        _ = feed(&cutter, 6, Self.speech)
        #expect(pause(&cutter) != nil)
        // A late pause event (FluidAudio still counting the same silence) finds only silence since the cut.
        _ = feed(&cutter, 3, Self.silence)
        #expect(cutter.add(count: Self.block, probability: Self.silence, pauseStart: cutter.position - 4 * Self.block) == nil)
    }

    @Test func releaseWithNoSpeechSinceTheLastCutLeavesNothing() {
        var cutter = PauseCutter()
        _ = feed(&cutter, 6, Self.speech)
        #expect(pause(&cutter) != nil)
        _ = feed(&cutter, 2, Self.silence)
        _ = cutter.add(count: 1000, probability: Self.silence, pauseStart: nil)
        #expect(cutter.tail == nil)
    }

    @Test func releaseAfterSpeechLeavesTheRestAsTheLastPart() {
        var cutter = PauseCutter()
        _ = feed(&cutter, 6, Self.speech)
        let first = pause(&cutter)!
        _ = feed(&cutter, 5, Self.speech)
        _ = cutter.add(count: 1000, probability: Self.silence, pauseStart: nil)
        #expect(cutter.tail == first.upperBound ..< cutter.position)
        // Quiet speech the VAD doesn't start on (0.5 to 0.85) still counts as heard.
        var quiet = PauseCutter()
        _ = feed(&quiet, 4, 0.6)
        #expect(quiet.tail == 0 ..< 4 * Self.block)
    }
}

/// One locked take's parts: in order, joined with a space as the engine wrote them.
struct TakePartsTests {
    private func part(_ from: Double, _ to: Double) -> TakePart {
        TakePart(range: Int(from * 16_000) ..< Int(to * 16_000), samples: [])
    }

    @Test func partsAreTranscribedInOrderOnlyOnceLocked() {
        var parts = TakeParts()
        parts.add(part(0, 3))
        parts.add(part(3, 7))
        #expect(parts.next == nil)
        parts.lock()
        #expect(parts.next == 0)
        parts.finish(0, text: "Fix the login.")
        #expect(parts.next == 1)
    }

    @Test func textJoinsFinishedPartsWithOneSpaceInOrder() {
        var parts = TakeParts()
        parts.lock()
        parts.add(part(0, 3))
        parts.add(part(3, 7))
        parts.add(part(7, 9))
        parts.finish(1, text: " Then the tests. ")
        // A later part doesn't show before the one ahead of it.
        #expect(parts.text == "")
        parts.finish(0, text: "Fix the login.")
        #expect(parts.text == "Fix the login. Then the tests.")
        // A part with no speech adds nothing.
        parts.finish(2, text: "")
        #expect(parts.text == "Fix the login. Then the tests.")
        #expect(!parts.isSettled)
        parts.end(tail: nil)
        #expect(parts.isSettled)
    }

    @Test func aFailedPartIsKeptForTryAgain() {
        var parts = TakeParts()
        parts.lock()
        parts.add(part(0, 3))
        parts.add(part(3, 6))
        parts.fail(0)
        #expect(parts.next == 1)
        parts.finish(1, text: "second")
        parts.end(tail: nil)
        #expect(parts.isSettled && parts.hasFailed)
        parts.retry()
        #expect(parts.next == 0)
        parts.finish(0, text: "first")
        #expect(parts.text == "first second")
        #expect(parts.isSettled && !parts.hasFailed)
    }
}

/// A locked take in the draft: one chunk whose text grows part by part while it records.
@MainActor
struct LockedTakeTests {
    final class Clock {
        var now = Date(timeIntervalSince1970: 1_000)
    }

    private func make(_ given: FakeSpeech? = nil) -> (HomeModel, FakeRecorder, FakeSpeech, Clock) {
        let speech = given ?? FakeSpeech()
        let recorder = FakeRecorder()
        let clock = Clock()
        return (HomeModel(recorder: recorder, speech: speech, now: { clock.now }), recorder, speech, clock)
    }

    private func part(_ from: Double, _ to: Double) -> TakePart {
        TakePart(range: Int(from * 16_000) ..< Int(to * 16_000), samples: [Float](repeating: Float(from), count: 4))
    }

    private func eventually(_ condition: @MainActor () -> Bool) async {
        for _ in 0 ..< 400 where !condition() {
            try? await Task.sleep(for: .milliseconds(5))
        }
    }

    /// Starts a hands-free take and waits for the recorder to start.
    private func startLocked(_ home: HomeModel, _ recorder: FakeRecorder) async {
        await home.toggleHandsFreeTake()
        await eventually { recorder.started == 1 }
    }

    @Test func textGrowsWhileListeningAndOnlyTheLastPartWaitsAfterRelease() async {
        let (home, recorder, speech, clock) = make()
        await startLocked(home, recorder)
        recorder.onPart?(part(0, 4))
        await eventually { home.draft.chunks.first?.partial == "part 0" }
        #expect(home.draft.chunks.map(\.state) == [.listening])
        #expect(DraftText.item(home.draft.chunks[0]).text == "part 0")
        recorder.onPart?(part(4, 9))
        await eventually { home.draft.chunks.first?.partial == "part 0 part 4" }
        #expect(home.draft.chunks.map(\.state) == [.listening])
        #expect(home.draft.chunks.count == 1)
        #expect(!home.canSend)

        recorder.split = TakeSplit(parts: 2, tail: part(9, 11))
        clock.now += 11
        await home.finishLockedTake()
        await eventually { home.draft.isComplete }
        #expect(home.draft.chunks.map(\.text) == ["part 0 part 4 part 9"])
        #expect(home.draft.chunks[0].duration == 11)
        #expect(speech.transcribedParts.map(\.lowerBound) == [0, 4, 9].map { $0 * 16_000 })
        // The whole file is never transcribed.
        #expect(speech.transcribed.isEmpty)
        #expect(home.canSend)
    }

    @Test func releaseWithNoSpeechSinceTheLastCutTranscribesNothingMore() async {
        let (home, recorder, speech, _) = make()
        await startLocked(home, recorder)
        recorder.onPart?(part(0, 4))
        await eventually { home.draft.chunks.first?.partial != nil }
        recorder.split = TakeSplit(parts: 1, tail: nil)
        await home.finishLockedTake()
        await eventually { home.draft.isComplete }
        #expect(home.draft.chunks.map(\.text) == ["part 0"])
        #expect(speech.transcribedParts.count == 1)
        #expect(speech.transcribed.isEmpty)
    }

    @Test func aPartCutWhileTheTakeStopsBelongsToIt() async {
        let (home, recorder, speech, _) = make()
        await startLocked(home, recorder)
        recorder.onPart?(part(0, 4))
        recorder.partsAtStop = [part(4, 8)]
        recorder.split = TakeSplit(parts: 2, tail: part(8, 9))
        await home.finishLockedTake()
        await eventually { home.draft.isComplete }
        #expect(home.draft.chunks.map(\.text) == ["part 0 part 4 part 8"])
        #expect(speech.transcribedParts.map(\.lowerBound) == [0, 4, 8].map { $0 * 16_000 })
    }

    @Test func partsAreTranscribedOneAtATimeInOrder() async {
        let speech = FakeSpeech()
        speech.holdsParts = true
        let (home, recorder, _, _) = make(speech)
        await startLocked(home, recorder)
        recorder.onPart?(part(0, 3))
        recorder.onPart?(part(3, 6))
        recorder.onPart?(part(6, 9))
        await eventually { speech.partsHeld == 1 }
        try? await Task.sleep(for: .milliseconds(30))
        // One at a time: the second waits for the first.
        #expect(speech.partsHeld == 1)
        #expect(home.draft.chunks[0].partial == nil)
        speech.releaseParts()
        await eventually { home.draft.chunks.first?.partial == "part 0 part 3 part 6" }
        #expect(speech.transcribedParts.map(\.lowerBound) == [0, 3, 6].map { $0 * 16_000 })
    }

    @Test func droppingTheTakeDuringAPartThrowsItsTextAway() async {
        let speech = FakeSpeech()
        speech.holdsParts = true
        let (home, recorder, _, _) = make(speech)
        await startLocked(home, recorder)
        recorder.onPart?(part(0, 3))
        recorder.onPart?(part(3, 6))
        await eventually { speech.partsHeld == 1 }
        await home.dropLockedTake()
        #expect(home.draft.isEmpty)
        #expect(home.toast?.title == "Take dropped")
        speech.releaseParts()
        try? await Task.sleep(for: .milliseconds(50))
        // The part in progress came back after the drop: nothing of it shows, and the next part never starts.
        #expect(home.draft.isEmpty)
        #expect(speech.transcribedParts.count == 1)

        // The next take starts clean.
        await home.toggleHandsFreeTake()
        recorder.onPart?(part(0, 2))
        await eventually { home.draft.chunks.first?.partial == "part 0" }
        #expect(home.draft.chunks.count == 1)
    }

    @Test func aHeldTakeIsTranscribedWhole() async {
        let (home, recorder, speech, clock) = make()
        home.pressBegan()
        await eventually { recorder.started == 1 }
        recorder.onPart?(part(0, 4))
        try? await Task.sleep(for: .milliseconds(30))
        #expect(home.draft.chunks[0].partial == nil)
        recorder.split = TakeSplit(parts: 1, tail: part(4, 6))
        clock.now += 6
        await home.pressEnded()
        #expect(home.draft.chunks.map(\.text) == ["hello there"])
        #expect(speech.transcribedParts.isEmpty)
        #expect(speech.transcribed.count == 1)
    }

    @Test func partsFoundBeforeLockingAreTranscribedOnceLocked() async {
        let (home, recorder, speech, _) = make()
        home.pressBegan()
        await eventually { recorder.started == 1 }
        recorder.onPart?(part(0, 4))
        home.pressMoved(dx: TalkDrag.armDistance)
        await home.pressEnded()
        #expect(home.locked)
        await eventually { home.draft.chunks.first?.partial == "part 0" }
        #expect(speech.transcribedParts.count == 1)
    }

    @Test func withoutTheVADALockedTakeIsTranscribedWholeAfterRelease() async {
        let (home, recorder, speech, clock) = make()
        await startLocked(home, recorder)
        // No VAD: no parts, and the recorder reports no split.
        recorder.split = nil
        clock.now += 20
        await home.finishLockedTake()
        #expect(home.draft.chunks.map(\.text) == ["hello there"])
        #expect(speech.transcribedParts.isEmpty)
        #expect(speech.transcribed == recorder.takes)
    }

    @Test func aFailedPartKeepsTheAudioForTryAgain() async {
        let speech = FakeSpeech()
        speech.failingParts = [4 * 16_000]
        let (home, recorder, _, _) = make(speech)
        await startLocked(home, recorder)
        recorder.onPart?(part(0, 4))
        recorder.onPart?(part(4, 8))
        recorder.split = TakeSplit(parts: 2, tail: part(8, 10))
        await eventually { speech.transcribedParts.count == 2 }
        await home.finishLockedTake()
        await eventually { home.canRetryTakes }
        #expect(home.draft.chunks.map(\.state) == [.failed("Couldn’t turn this take into text")])
        #expect(!home.canSend)
        // The failure doesn't stop later parts.
        #expect(speech.transcribedParts.map(\.lowerBound) == [0, 4, 8].map { $0 * 16_000 })

        speech.failingParts = []
        home.retryFailedTakes()
        await eventually { home.draft.isComplete }
        #expect(home.draft.chunks.map(\.text) == ["part 0 part 4 part 8"])
        // Only the failed part was transcribed again.
        #expect(speech.transcribedParts.map(\.lowerBound) == [0, 4, 8, 4].map { $0 * 16_000 })
    }

    @Test func partsWaitForTheSpeechModel() async {
        let speech = FakeSpeech(.loading)
        let (home, recorder, _, _) = make(speech)
        await startLocked(home, recorder)
        recorder.onPart?(part(0, 4))
        try? await Task.sleep(for: .milliseconds(30))
        #expect(home.draft.chunks.map(\.state) == [.listening])
        recorder.split = TakeSplit(parts: 1, tail: part(4, 6))
        await home.finishLockedTake()
        #expect(home.draft.chunks.map(\.state) == [.waiting])
        speech.status = .ready
        await eventually { home.draft.isComplete }
        #expect(home.draft.chunks.map(\.text) == ["part 0 part 4"])
    }

    @Test func reRecordingAChunkSplitsAndReplacesItWhole() async {
        let (home, recorder, speech, clock) = make()
        home.pressBegan()
        clock.now += 2
        await home.pressEnded()
        #expect(home.draft.chunks.map(\.text) == ["hello there"])
        home.toggleChunk(home.draft.chunks[0].id)
        home.rerecordSelected()
        recorder.onPart?(part(0, 4))
        await eventually { home.draft.chunks.first?.partial == "part 0" }
        recorder.split = TakeSplit(parts: 1, tail: part(4, 7))
        await home.finishLockedTake()
        await eventually { home.draft.isComplete }
        #expect(home.draft.chunks.map(\.text) == ["part 0 part 4"])
        #expect(speech.transcribedParts.count == 2)
    }
}
