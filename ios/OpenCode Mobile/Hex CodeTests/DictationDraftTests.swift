import Foundation
import Testing
@testable import Hex_Code

/// The draft's chunk states: Listening → Transcribing → text, re-record, drop, delete, Clear.
struct DictationDraftTests {
    private func draft(_ texts: [String]) -> DictationDraft {
        var draft = DictationDraft()
        for text in texts {
            let id = draft.beginTake()!
            draft.endTake(duration: 2)
            draft.finishTranscription(id, text: text)
        }
        return draft
    }

    @Test func aTakeGoesListeningTranscribingText() throws {
        var draft = DictationDraft()
        let id = draft.beginTake()!
        #expect(draft.chunks.map(\.state) == [.listening])
        #expect(draft.isListening)
        #expect(!draft.isComplete)
        // Only one take at a time.
        #expect(draft.beginTake() == nil)

        draft.endTake(duration: 3.2)
        #expect(draft.chunks.map(\.state) == [.transcribing])
        #expect(!draft.isListening)
        #expect(draft.isTranscribing)
        #expect(draft.chunks[0].duration == 3.2)

        draft.finishTranscription(id, text: "  fix the login form  ")
        #expect(draft.chunks.map(\.state) == [.text("fix the login form")])
        #expect(draft.isComplete)
        #expect(draft.text == "fix the login form")
    }

    @Test func takesAddChunksInOrderAndTheDraftWaitsForEveryOne() throws {
        var draft = draft(["one"])
        let second = draft.beginTake()!
        draft.endTake(duration: 1)
        #expect(!draft.isComplete)
        draft.finishTranscription(second, text: "two")
        #expect(draft.text == "one two")
        #expect(draft.totalDuration == 3)
        #expect(draft.wordCount == 2)
    }

    @Test func silenceLeavesNoChunk() throws {
        var draft = DictationDraft()
        let id = draft.beginTake()!
        draft.endTake(duration: 1)
        draft.finishTranscription(id, text: "   ")
        #expect(draft.isEmpty)
    }

    @Test func droppingANewTakeRemovesIt() throws {
        var draft = draft(["keep this"])
        _ = draft.beginTake()!
        draft.dropTake()
        #expect(draft.chunks.map(\.state) == [.text("keep this")])
        #expect(!draft.isListening)
    }

    @Test func aFailedTranscriptionStaysAsAFailedChunk() throws {
        var draft = DictationDraft()
        let id = draft.beginTake()!
        draft.endTake(duration: 1)
        draft.failTranscription(id, message: "Couldn’t turn this take into text")
        #expect(draft.chunks.map(\.state) == [.failed("Couldn’t turn this take into text")])
        #expect(!draft.isComplete)
    }

    @Test func selectingAChunkTogglesAndOnlySettledChunksCanBeSelected() throws {
        var draft = draft(["one", "two"])
        let first = draft.chunks[0].id
        draft.toggleSelection(first)
        #expect(draft.selectedIndex == 0)
        draft.toggleSelection(first)
        #expect(draft.selectedIndex == nil)

        let live = draft.beginTake()!
        draft.toggleSelection(first)
        #expect(draft.selectedIndex == nil)
        draft.endTake(duration: 1)
        draft.toggleSelection(live)
        #expect(draft.selectedIndex == nil)
    }

    @Test func reRecordReplacesTheSelectedChunkInPlace() throws {
        var draft = draft(["one", "two", "three"])
        let second = draft.chunks[1].id
        draft.toggleSelection(second)
        let id = draft.beginTake()!
        #expect(id == second)
        #expect(draft.isRerecording)
        #expect(draft.chunks.count == 3)
        #expect(draft.chunks[1].state == .listening)
        #expect(draft.selectedIndex == nil)
        draft.endTake(duration: 4)
        draft.finishTranscription(id, text: "TWO")
        #expect(draft.chunks.map(\.text) == ["one", "TWO", "three"])
        #expect(draft.chunks[1].duration == 4)
    }

    @Test func aDroppedOrSilentReRecordKeepsTheEarlierText() throws {
        var draft = draft(["one", "two"])
        draft.toggleSelection(draft.chunks[0].id)
        _ = draft.beginTake()!
        draft.dropTake()
        #expect(draft.chunks.map(\.text) == ["one", "two"])
        #expect(draft.chunks[0].duration == 2)

        draft.toggleSelection(draft.chunks[0].id)
        let id = draft.beginTake()!
        draft.endTake(duration: 5)
        draft.finishTranscription(id, text: "")
        #expect(draft.chunks.map(\.text) == ["one", "two"])
        #expect(draft.chunks[0].duration == 2)
    }

    @Test func deleteAndUndo() throws {
        var draft = draft(["one", "two", "three"])
        draft.toggleSelection(draft.chunks[1].id)
        let deleted = draft.deleteSelected()!
        #expect(deleted.index == 1)
        #expect(draft.chunks.map(\.text) == ["one", "three"])
        #expect(draft.selectedIndex == nil)
        draft.restore(deleted.chunk, at: deleted.index)
        #expect(draft.chunks.map(\.text) == ["one", "two", "three"])
    }

    @Test func clearEmptiesTheDraftButNotDuringATake() throws {
        var draft = draft(["one", "two"])
        _ = draft.beginTake()!
        draft.clear()
        #expect(draft.chunks.count == 3)
        draft.dropTake()
        draft.clear()
        #expect(draft.isEmpty)
    }

    @Test func longAfterThreeChunksOr220Characters() {
        #expect(!draft(["a", "b", "c"]).isLong)
        #expect(draft(["a", "b", "c", "d"]).isLong)
        #expect(draft([String(repeating: "word ", count: 45)]).isLong)
        #expect(!draft([String(repeating: "word ", count: 40)]).isLong)
    }

    @Test func longListShowsTheNewestEightAndKeepsTheFocusInView() throws {
        var draft = draft((1 ... 12).map { "chunk \($0)" })
        #expect(draft.visibleRange() == 4 ..< 12)
        #expect(draft.visibleRange(showEarlier: true) == 0 ..< 8)

        draft.toggleSelection(draft.chunks[1].id)
        #expect(draft.visibleRange() == 1 ..< 9)
        draft.deselect()

        // A new take's live row sits under the list and takes one slot.
        _ = draft.beginTake()!
        #expect(draft.visibleRange() == 5 ..< 12)
    }
}

// MARK: - Home: talk button gestures

@MainActor
final class FakeRecorder: TakeRecording {
    var isReady = true
    var level: Float = 0
    var startFails = false
    var transcript = "hello there"
    private(set) var started = 0
    private(set) var cancelled = 0

    func startRecording() async throws {
        started += 1
        if startFails { throw CancellationError() }
    }

    func stopAndTranscribe() async throws -> String { transcript }

    func cancelRecording() { cancelled += 1 }
}

@MainActor
struct HomeModelTests {
    final class Clock {
        var now = Date(timeIntervalSince1970: 1_000)
    }

    private func make(_ given: FakeRecorder? = nil) -> (HomeModel, FakeRecorder, Clock) {
        let recorder = given ?? FakeRecorder()
        let clock = Clock()
        return (HomeModel(recorder: recorder, now: { clock.now }), recorder, clock)
    }

    @Test func holdAndReleaseMakesAChunkAndNeverSends() async {
        let (home, recorder, clock) = make()
        home.pressBegan()
        #expect(home.isRecording)
        clock.now += 2
        await home.pressEnded()
        #expect(recorder.started == 1)
        #expect(home.draft.chunks.map(\.state) == [.text("hello there")])
        #expect(home.draft.chunks[0].duration == 2)
    }

    @Test func dragRightPastTheTraceLocksAndATapPauses() async {
        let (home, _, clock) = make()
        home.pressBegan()
        home.pressMoved(dx: 40)
        #expect(home.dragOffset == 40 && !home.isArmed)
        home.pressMoved(dx: TalkDrag.armDistance)
        #expect(home.isArmed)
        clock.now += 1
        await home.pressEnded()
        #expect(home.locked)
        #expect(home.isRecording)

        clock.now += 10
        home.pressBegan()
        await home.pressEnded()
        #expect(!home.locked)
        #expect(home.draft.chunks.map(\.text) == ["hello there"])
        #expect(home.draft.chunks[0].duration == 11)
    }

    @Test func dragLeftPastTheTraceDropsTheTake() async {
        let (home, recorder, clock) = make()
        home.pressBegan()
        home.pressMoved(dx: -TalkDrag.armDistance)
        clock.now += 2
        await home.pressEnded()
        #expect(home.draft.isEmpty)
        #expect(recorder.cancelled == 1)
        #expect(home.toast?.title == "Take dropped")
    }

    @Test func aShortDragDoesNothingSpecial() async {
        let (home, _, clock) = make()
        home.pressBegan()
        home.pressMoved(dx: TalkDrag.armDistance / 2)
        clock.now += 1
        await home.pressEnded()
        #expect(!home.locked)
        #expect(home.draft.chunks.map(\.text) == ["hello there"])
    }

    @Test func aTapIsNotATake() async {
        let (home, recorder, clock) = make()
        home.pressBegan()
        clock.now += 0.1
        await home.pressEnded()
        #expect(home.draft.isEmpty)
        #expect(recorder.cancelled == 1)
    }

    @Test func nothingRecordsBeforeTheSpeechModelIsReady() async {
        let recorder = FakeRecorder()
        recorder.isReady = false
        let (home, _, _) = make(recorder)
        home.pressBegan()
        #expect(!home.isRecording)
        #expect(recorder.started == 0)
    }

    @Test func reRecordFromTheChunkActionsIsHandsFree() async {
        let (home, recorder, clock) = make()
        home.pressBegan()
        clock.now += 2
        await home.pressEnded()
        home.toggleChunk(home.draft.chunks[0].id)
        recorder.transcript = "better words"
        home.rerecordSelected()
        #expect(home.locked)
        #expect(home.draft.isRerecording)
        clock.now += 3
        home.pressBegan()
        await home.pressEnded()
        #expect(home.draft.chunks.map(\.text) == ["better words"])
    }

    @Test func deleteUndoAndClear() async {
        let (home, _, clock) = make()
        for _ in 0 ..< 2 {
            home.pressBegan()
            clock.now += 2
            await home.pressEnded()
        }
        home.toggleChunk(home.draft.chunks[0].id)
        home.deleteSelectedChunk()
        #expect(home.draft.chunks.count == 1)
        #expect(home.toast?.action?.title == "Undo")
        #expect(home.toast?.tone == .undo)
        home.undoDelete()
        #expect(home.draft.chunks.count == 2)
        home.clearDraft()
        #expect(home.draft.isEmpty)
    }
}

/// The drag's dead zone, look and arming (app plan, "Dictation": Drag feedback and Arming).
struct TalkDragTests {
    @Test func nothingShowsInTheFirst12Points() {
        #expect(TalkDrag.progress(0) == 0)
        #expect(TalkDrag.progress(12) == 0)
        #expect(TalkDrag.progress(-12) == 0)
        #expect(TalkDrag.progress(38) == 0.5)
        #expect(TalkDrag.progress(-38) == -0.5)
        #expect(TalkDrag.progress(64) == 1)
        #expect(TalkDrag.progress(-200) == -1)
    }

    @Test func armsAt64AndDisarmsOnlyBelow52() {
        #expect(TalkDrag.arm(nil, offset: 63.9) == nil)
        #expect(TalkDrag.arm(nil, offset: 64) == .lock)
        #expect(TalkDrag.arm(.lock, offset: 55) == .lock)
        #expect(TalkDrag.arm(.lock, offset: 52) == .lock)
        #expect(TalkDrag.arm(.lock, offset: 51.9) == nil)
        #expect(TalkDrag.arm(nil, offset: -64) == .drop)
        #expect(TalkDrag.arm(.drop, offset: -53) == .drop)
        #expect(TalkDrag.arm(.drop, offset: -51) == nil)
    }

    @Test func aShakyFingerAtTheThresholdDoesNotFlip() {
        var arm: TalkDrag.Arm?
        var ticks = 0
        for offset: CGFloat in [60, 64, 61, 65, 58, 63, 66, 53, 64] {
            let next = TalkDrag.arm(arm, offset: offset)
            if next != nil, arm == nil { ticks += 1 }
            arm = next
        }
        #expect(arm == .lock)
        #expect(ticks == 1)
    }

    @Test func crossingStraightToTheOtherSideSwitchesTheArm() {
        #expect(TalkDrag.arm(.lock, offset: -70) == .drop)
        #expect(TalkDrag.arm(.drop, offset: 70) == .lock)
    }

    @Test func releaseActsOnTheArmNotTheLook() async {
        let recorder = FakeRecorder()
        let home = HomeModel(recorder: recorder)
        home.pressBegan()
        home.pressMoved(dx: 70)
        // Back to 55: inside the hysteresis, still armed.
        home.pressMoved(dx: 55)
        #expect(home.arm == .lock)
        await home.pressEnded()
        #expect(home.locked)
    }
}
