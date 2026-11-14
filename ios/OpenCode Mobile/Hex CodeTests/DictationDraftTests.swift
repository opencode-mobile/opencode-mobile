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
    var level: Float? = 0
    private(set) var prepared = 0
    private(set) var released = 0

    func prepare() { prepared += 1 }
    func release() { released += 1 }
    var startFails = false
    /// Seconds of audio each take holds.
    var duration: TimeInterval = 1
    private(set) var started = 0
    private(set) var cancelled = 0
    private(set) var takes: [URL] = []
    var onPart: ((TakePart) -> Void)?
    /// How the next take ends when split (the pause finder was on); nil: it wasn't.
    var split: TakeSplit?
    /// Parts the splitter finds in the take's last blocks, while it stops.
    var partsAtStop: [TakePart] = []

    func startRecording() async throws {
        started += 1
        if startFails { throw CancellationError() }
    }

    func stopRecording() async -> RecordedTake? {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("fake-take-\(takes.count)-\(UUID().uuidString).wav")
        takes.append(url)
        await Task.yield()
        partsAtStop.forEach { onPart?($0) }
        partsAtStop = []
        return RecordedTake(url: url, duration: duration, split: split)
    }

    func cancelRecording() { cancelled += 1 }
}

/// A speech model whose readiness the test controls.
@MainActor
final class FakeSpeech: SpeechTranscribing {
    var status: SpeechStatus {
        didSet {
            guard status == .ready || status == .failed else { return }
            let waiting = waiters
            waiters = []
            waiting.forEach { $0.resume() }
        }
    }

    /// The text of each take, in turn; then `transcript`.
    var transcripts: [String] = []
    var transcript = "hello there"
    var failsTranscription = false
    private(set) var transcribed: [URL] = []
    private(set) var reloads = 0
    /// Times `start()` was called.
    private(set) var starts = 0
    private var waiters: [CheckedContinuation<Void, Never>] = []

    init(_ status: SpeechStatus = .ready) {
        self.status = status
    }

    func waitUntilReady() async -> Bool {
        if status != .ready, status != .failed {
            await withCheckedContinuation { waiters.append($0) }
        }
        return status == .ready
    }

    func start() {
        starts += 1
    }

    func reload() {
        reloads += 1
        status = .loading
    }

    func transcribe(_ take: RecordedTake) async throws -> String {
        transcribed.append(take.url)
        if failsTranscription { throw CancellationError() }
        return transcripts.isEmpty ? transcript : transcripts.removeFirst()
    }

    /// Parts transcribed, in order (their ranges).
    private(set) var transcribedParts: [Range<Int>] = []
    /// Each part's text is its range's lower bound in seconds ("part 3") unless given here, by range start.
    var partTexts: [Int: String] = [:]
    /// Parts at these starts fail.
    var failingParts: Set<Int> = []
    /// While set, part transcriptions wait until `releaseParts()`.
    var holdsParts = false
    private var heldParts: [CheckedContinuation<Void, Never>] = []

    func transcribe(part: TakePart) async throws -> String {
        transcribedParts.append(part.range)
        if holdsParts { await withCheckedContinuation { heldParts.append($0) } }
        if failsTranscription || failingParts.contains(part.range.lowerBound) { throw CancellationError() }
        return partTexts[part.range.lowerBound] ?? "part \(part.range.lowerBound / 16_000)"
    }

    /// Lets held part transcriptions finish.
    func releaseParts() {
        holdsParts = false
        let held = heldParts
        heldParts = []
        held.forEach { $0.resume() }
    }

    var partsHeld: Int { heldParts.count }
}

@MainActor
struct HomeModelTests {
    final class Clock {
        var now = Date(timeIntervalSince1970: 1_000)
    }

    private func make(_ given: FakeRecorder? = nil, speech: FakeSpeech? = nil) -> (HomeModel, FakeRecorder, Clock) {
        let recorder = given ?? FakeRecorder()
        let clock = Clock()
        return (HomeModel(recorder: recorder, speech: speech ?? FakeSpeech(), now: { clock.now }), recorder, clock)
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

    @Test func theTalkButtonDoesNotWaitForTheSpeechModel() async {
        let speech = FakeSpeech(.loading)
        let (home, recorder, _) = make(speech: speech)
        home.pressBegan()
        #expect(home.isRecording)
        #expect(home.toast == nil)
        await Task.yield()
        #expect(recorder.started == 1)
    }

    @Test func reRecordFromTheChunkActionsIsHandsFree() async {
        let speech = FakeSpeech()
        let (home, _, clock) = make(speech: speech)
        home.pressBegan()
        clock.now += 2
        await home.pressEnded()
        home.toggleChunk(home.draft.chunks[0].id)
        speech.transcript = "better words"
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
        let home = HomeModel(recorder: recorder, speech: FakeSpeech())
        home.pressBegan()
        home.pressMoved(dx: 70)
        // Back to 55: inside the hysteresis, still armed.
        home.pressMoved(dx: 55)
        #expect(home.arm == .lock)
        await home.pressEnded()
        #expect(home.locked)
    }
}

/// Takes released before the speech model is ready wait for it (app plan, "The speech model").
@MainActor
struct WaitingTakeTests {
    final class Clock {
        var now = Date(timeIntervalSince1970: 1_000)
    }

    private func make(_ speech: FakeSpeech, recorder: FakeRecorder? = nil) -> (HomeModel, Clock) {
        let clock = Clock()
        return (HomeModel(recorder: recorder ?? FakeRecorder(), speech: speech, now: { clock.now }), clock)
    }

    private func take(_ home: HomeModel, _ clock: Clock) async {
        home.pressBegan()
        clock.now += 2
        await home.pressEnded()
    }

    private func eventually(_ condition: @MainActor () -> Bool) async {
        for _ in 0 ..< 200 where !condition() {
            try? await Task.sleep(for: .milliseconds(5))
        }
    }

    @Test func aTakeWhileLoadingWaitsThenGetsItsText() async {
        let speech = FakeSpeech(.loading)
        let (home, clock) = make(speech)
        await take(home, clock)
        #expect(home.draft.chunks.map(\.state) == [.waiting])
        #expect(!home.draft.isComplete)
        speech.status = .ready
        await eventually { home.draft.chunks.first?.text != nil }
        #expect(home.draft.chunks.map(\.text) == ["hello there"])
    }

    @Test func threeWaitingTakesComeOutInTheOrderTheyWereRecorded() async {
        let speech = FakeSpeech(.downloading(percent: 45))
        let recorder = FakeRecorder()
        let (home, clock) = make(speech, recorder: recorder)
        for _ in 0 ..< 3 { await take(home, clock) }
        #expect(home.draft.chunks.map(\.state) == [.waiting, .waiting, .waiting])
        speech.transcripts = ["one", "two", "three"]
        speech.status = .ready
        await eventually { home.draft.isComplete }
        #expect(home.draft.chunks.map(\.text) == ["one", "two", "three"])
        #expect(speech.transcribed == recorder.takes)
    }

    @Test func aFailedModelThenTryAgainGivesText() async {
        let speech = FakeSpeech(.loading)
        let (home, clock) = make(speech)
        await take(home, clock)
        speech.status = .failed
        await eventually { home.canRetryTakes }
        #expect(home.draft.chunks.map(\.state) == [.failed("Couldn’t turn this take into text")])
        #expect(DraftStatusLine.of(isRecording: false, draft: home.draft, speech: speech.status) == .speechFailed)
        home.retryFailedTakes()
        #expect(speech.reloads == 1)
        #expect(home.draft.chunks.map(\.state) == [.waiting])
        speech.status = .ready
        await eventually { home.draft.isComplete }
        #expect(home.draft.chunks.map(\.text) == ["hello there"])
    }

    @Test func aFailedTakeCanStillBeReRecordedOrDeleted() async {
        let speech = FakeSpeech(.failed)
        let (home, clock) = make(speech)
        await take(home, clock)
        await eventually { home.canRetryTakes }
        let id = home.draft.chunks[0].id
        home.toggleChunk(id)
        #expect(home.draft.selectedID == id)
        home.deleteSelectedChunk()
        #expect(home.draft.isEmpty)
    }

    @Test func sendWaitsWhileAChunkWaits() {
        var draft = DictationDraft()
        let first = draft.beginTake()!
        draft.endTake(duration: 1)
        draft.finishTranscription(first, text: "Fix the login")
        let second = draft.beginTake()!
        draft.endTake(duration: 1)
        draft.markWaiting(second)
        // Send, Ask and Set as goal wait for isComplete.
        #expect(!draft.isComplete)
        let began = draft.beginTranscribing(second)
        #expect(began)
        draft.finishTranscription(second, text: "then the tests")
        #expect(draft.isComplete)
    }

    @Test func theLabelLineRules() {
        var draft = DictationDraft()
        let id = draft.beginTake()!
        #expect(DraftStatusLine.of(isRecording: true, draft: draft, speech: .loading) == .recording)
        draft.endTake(duration: 1)
        #expect(DraftStatusLine.of(isRecording: false, draft: draft, speech: .ready) == .transcribing)
        draft.markWaiting(id)
        #expect(DraftStatusLine.of(isRecording: false, draft: draft, speech: .loading) == .speechLoading)
        #expect(DraftStatusLine.of(isRecording: false, draft: draft, speech: .downloading(percent: 45)) == .speechDownloading(percent: 45))
        #expect(DraftStatusLine.of(isRecording: false, draft: draft, speech: .failed) == .speechFailed)
        _ = draft.beginTranscribing(id)
        draft.finishTranscription(id, text: "Hello")
        // Nothing waits: the model's state doesn't show.
        #expect(DraftStatusLine.of(isRecording: false, draft: draft, speech: .loading) == .summary)
        #expect(DraftStatusLine.of(isRecording: false, draft: draft, speech: .downloading(percent: 10)) == .summary)
    }

    @Test func aTooShortTakeLeavesNoChunk() async {
        let recorder = FakeRecorder()
        recorder.duration = 0.2
        let speech = FakeSpeech()
        let (home, clock) = make(speech, recorder: recorder)
        await take(home, clock)
        #expect(home.draft.isEmpty)
        #expect(speech.transcribed.isEmpty)
    }

    @Test func aSilentTakeLeavesNoChunk() async {
        let speech = FakeSpeech()
        speech.transcript = ""
        let (home, clock) = make(speech)
        await take(home, clock)
        #expect(home.draft.isEmpty)
    }
}

/// Long drafts stay running text as long as they can (app plan, "Long drafts").
struct DraftLayoutTests {
    @Test func moreThanThreeChunksAreAlwaysRows() {
        #expect(DraftLayout.of(chunkCount: 4, fitsAtSmallest: true) == .rows)
        #expect(DraftLayout.of(chunkCount: 9, fitsAtSmallest: false) == .rows)
    }

    @Test func upToThreeChunksStayTextWhileTheyFit() {
        for count in 1 ... 3 {
            #expect(DraftLayout.of(chunkCount: count, fitsAtSmallest: true) == .text)
        }
    }

    @Test func twoOrMoreThatDontFitBecomeRows() {
        #expect(DraftLayout.of(chunkCount: 2, fitsAtSmallest: false) == .rows)
        #expect(DraftLayout.of(chunkCount: 3, fitsAtSmallest: false) == .rows)
    }

    @Test func aSingleChunkNeverBecomesAList() {
        #expect(DraftLayout.of(chunkCount: 1, fitsAtSmallest: false) == .scrolling)
        #expect(DraftLayout.whenTooLong(chunkCount: 1) == .scrolling)
        #expect(DraftLayout.whenTooLong(chunkCount: 2) == .rows)
    }

    @Test func theTypeStartsAt24UpTo150CharactersThenStepsDown() {
        #expect(DraftSize.candidates(forCharacters: 0) == [.regular, .medium, .small, .fit])
        #expect(DraftSize.candidates(forCharacters: 150) == [.regular, .medium, .small, .fit])
        #expect(DraftSize.candidates(forCharacters: 151) == [.medium, .small, .fit])
    }
}
