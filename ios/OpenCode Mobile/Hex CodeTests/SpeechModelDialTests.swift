import Foundation
import os
import Testing
import VoiceEngineKit
@testable import Hex_Code

/// The speech line, the draft label's words and the details sheet's copy (app plan, "Speech line and dial").
struct SpeechLineTests {
    private let now = Date(timeIntervalSinceReferenceDate: 1_000)

    private func line(_ download: ModelDownload) -> SpeechLine {
        SpeechLine.of(.downloading(download), now: now)
    }

    @Test func downloadingReadsMegabytesAndTheTimeLeftOnlyInTheLabel() {
        let words = line(ModelDownload(done: 120_900_000, total: 640_000_000, secondsLeft: 100))
        #expect(words == .downloading(doneMB: 120, totalMB: 640, minutesLeft: 2))
        #expect(words.text(timeLeft: false) == "Speech model · 120 of 640 MB")
        #expect(words.text(timeLeft: true) == "Speech model · 120 of 640 MB · 2 min left")
        let soon = line(ModelDownload(done: 600_000_000, total: 640_000_000, secondsLeft: 59))
        #expect(soon.text(timeLeft: true) == "Speech model · 600 of 640 MB · under a minute left")
        // Until the speed is known, no time left.
        let measuring = line(ModelDownload(done: 1_000_000, total: 640_000_000))
        #expect(measuring.text(timeLeft: true) == "Speech model · 1 of 640 MB")
    }

    @Test func minutesAreWholeAndRoundedUp() {
        #expect(SpeechLine.minutesLeft(0) == 0)
        #expect(SpeechLine.minutesLeft(59) == 0)
        #expect(SpeechLine.minutesLeft(60) == 1)
        #expect(SpeechLine.minutesLeft(61) == 2)
        #expect(SpeechLine.minutesLeft(120) == 2)
        #expect(SpeechLine.minutesLeft(121) == 3)
        #expect(SpeechLine.megabytes(999_999) == 0)
        #expect(SpeechLine.megabytes(640_000_000) == 640)
    }

    @Test func aDroppedConnectionCountsDownThenTriesAgain() {
        let waiting = ModelDownload(done: 1, total: 2, connection: .waiting(retryAt: now.addingTimeInterval(3.2)))
        #expect(line(waiting) == .retrying(inSeconds: 4))
        #expect(line(waiting).text(timeLeft: true) == "Speech model · retrying in 4 s")
        let due = ModelDownload(done: 1, total: 2, connection: .waiting(retryAt: now.addingTimeInterval(-0.1)))
        #expect(line(due) == .tryingAgain)
        #expect(line(ModelDownload(done: 1, total: 2, connection: .retrying)).text(timeLeft: false) == "Speech model · trying again…")
    }

    @Test func theOtherStatesWords() {
        #expect(SpeechLine.of(.notOnPhone, now: now).text(timeLeft: false) == "Speech model not on this phone yet")
        #expect(SpeechLine.of(.loading, now: now).text(timeLeft: false) == "Speech model loading…")
        #expect(SpeechLine.of(.warmingUp, now: now) == .loading)
        #expect(SpeechLine.of(.ready, now: now).text(timeLeft: false) == "Speech ready")
        #expect(SpeechLine.of(.failed, now: now).text(timeLeft: true) == "Speech model didn’t load")
        #expect(line(ModelDownload(done: 0, total: nil)).text(timeLeft: true) == "Speech model · downloading…")
    }

    @Test @MainActor func theLineShowsWhileGettingReadyThenSaysReadyOnceForTheProcess() {
        let memory = SpeechLineMemory()
        #expect(memory.next(.loading) == .gettingReady)
        #expect(memory.next(.failed) == .gettingReady)
        #expect(memory.next(.ready) == .ready)
        #expect(memory.isGone)
        // Once it has gone it doesn't come back, even if the model loads again.
        #expect(memory.next(.loading) == .hidden)
        #expect(memory.next(.ready) == .hidden)
        // Ready before the line ever showed: nothing to say.
        let quiet = SpeechLineMemory()
        #expect(quiet.next(.ready) == .hidden)
        #expect(quiet.next(.loading) == .hidden)
    }

    @Test func theReadyMomentStaysReady() {
        // "Speech ready" for its 1.6 s even if the model starts loading again meanwhile.
        #expect(SpeechLineView.status(.loading, show: .ready) == .ready)
        #expect(SpeechLineView.status(.failed, show: .ready) == .ready)
        #expect(SpeechLineView.status(.loading, show: .gettingReady) == .loading)
    }

    @Test func theDialGoesGreyWhileTheConnectionIsDown() {
        let flowing = ModelDownload(done: 320, total: 640)
        #expect(HexSpeechDial.Look(.downloading(flowing)) == .downloading(0.5))
        var dropped = flowing
        dropped.connection = .retrying
        #expect(HexSpeechDial.Look(.downloading(dropped)) == .stalled(0.5))
        #expect(HexSpeechDial.Look(.notOnPhone) == .stalled(0))
        #expect(HexSpeechDial.Look(.failed, lastFraction: 0.2) == .failed(0.2))
        #expect(HexSpeechDial.Look(.warmingUp) == .warmingUp)
    }
}

struct SpeechSheetCopyTests {
    private let details = SpeechDetails.placeholder
    private let posix = Locale(identifier: "en_US_POSIX")

    @Test func theHeroWhileDownloading() {
        let measuring = SpeechSheetCopy.hero(.downloading(ModelDownload(done: 120_000_000, total: 640_000_000)), details: details)
        #expect(measuring == .init(title: "Downloading", tone: .plain, line: "120 of 640 MB", detail: "measuring speed…"))
        let known = ModelDownload(done: 120_000_000, total: 640_000_000, bytesPerSecond: 8_400_000, secondsLeft: 62)
        #expect(SpeechSheetCopy.hero(.downloading(known), details: details, locale: posix).detail == "8.4 MB/s · about 2 min left")
        // The speed's decimal follows the phone's region.
        #expect(SpeechSheetCopy.speed(known, locale: Locale(identifier: "de_DE")) == "8,4 MB/s · about 2 min left")
        var dropped = known
        dropped.connection = .retrying
        let hero = SpeechSheetCopy.hero(.downloading(dropped), details: details)
        #expect(hero.title == "Connection dropped")
        #expect(hero.tone == .amber)
        #expect(hero.detail == "picks up where it stopped")
    }

    @Test func aFailedDownloadSaysWhereItStopped() {
        var failed = details
        failed.lastDownload = ModelDownload(done: 120_000_000, total: 640_000_000)
        let hero = SpeechSheetCopy.hero(.failed, details: failed)
        #expect(hero.line == "Stopped at 120 of 640 MB")
        #expect(hero.tone == .red)
        #expect(SpeechSheetCopy.phases(.failed, lastDownload: failed.lastDownload) == [.failed, .later, .later, .later])
        // Downloaded but didn't load: it failed at Load.
        let loaded = ModelDownload(done: 640_000_000, total: 640_000_000)
        #expect(SpeechSheetCopy.phases(.failed, lastDownload: loaded) == [.done, .failed, .later, .later])
        #expect(SpeechSheetCopy.phases(.failed, lastDownload: nil) == [.done, .failed, .later, .later])
    }

    @Test func thePhases() {
        #expect(SpeechSheetCopy.phases(.notOnPhone, lastDownload: nil) == [.later, .later, .later, .later])
        #expect(SpeechSheetCopy.phases(.downloading(ModelDownload(done: 1, total: 2)), lastDownload: nil) == [.now, .later, .later, .later])
        #expect(SpeechSheetCopy.phases(.downloading(ModelDownload(done: 1, total: 2, connection: .retrying)), lastDownload: nil) == [.stalled, .later, .later, .later])
        #expect(SpeechSheetCopy.phases(.loading, lastDownload: nil) == [.done, .now, .later, .later])
        #expect(SpeechSheetCopy.phases(.warmingUp, lastDownload: nil) == [.done, .done, .now, .later])
        #expect(SpeechSheetCopy.phases(.ready, lastDownload: nil) == [.done, .done, .done, .done])
    }

    @Test func readyNamesTheSizeOnThePhone() {
        var ready = details
        ready.modelBytes = 482_600_000
        #expect(SpeechSheetCopy.hero(.ready, details: ready) == .init(title: "Ready", tone: .green, line: "482 MB on this phone", detail: "loaded · warmed up"))
        // Every MB figure rounds down; a small file still reads 1 MB, never 0.
        #expect(SpeechSheetCopy.size(1_700_000) == "1 MB")
        #expect(SpeechSheetCopy.size(200_000) == "1 MB")
    }

    @Test func theSheetAndTheSpeechLineReadTheSameMegabytes() {
        let download = ModelDownload(done: 120_900_000, total: 640_700_000)
        #expect(SpeechSheetCopy.progressText(download) == "120 of 640 MB")
        #expect(SpeechLine.of(.downloading(download), now: Date()).text(timeLeft: false) == "Speech model · 120 of 640 MB")
        #expect(SpeechSheetCopy.size(640_700_000) == "640 MB")
        #expect(SpeechSheetCopy.progressText(ModelDownload(done: 1, total: nil)) == nil)
    }
}

/// The download's retries (app plan, "The speech model" › Download): after 1, 2, 4 and 8 s, counting again whenever
/// an attempt got further, a stalled attempt stopped, and Try now.
struct ResumableDownloadTests {
    /// What the fake attempts do and what the download asked for.
    final class Script: @unchecked Sendable {
        private let lock = OSAllocatedUnfairLock()
        private var _saved: Int64 = 0
        private var _attempts = 0
        private var _sleeps: [Duration] = []
        private var _events: [ResumableDownload.Event] = []

        var saved: Int64 { lock.withLock { _saved } }
        var attempts: Int { lock.withLock { _attempts } }
        var sleeps: [Duration] { lock.withLock { _sleeps } }
        var events: [ResumableDownload.Event] { lock.withLock { _events } }

        func attempt() -> Int { lock.withLock { _attempts += 1; return _attempts } }
        func grow(_ bytes: Int64) { lock.withLock { _saved += bytes } }
        func slept(_ duration: Duration) { lock.withLock { _sleeps.append(duration) } }
        func event(_ event: ResumableDownload.Event) { lock.withLock { _events.append(event) } }
    }

    private func make(_ script: Script, stallTimeout: Duration = .seconds(30), sleeps: Bool = false, attempt: @escaping @Sendable (Int, @escaping @Sendable () -> Void) async throws -> Void) -> ResumableDownload {
        ResumableDownload(
            stallTimeout: stallTimeout,
            sleep: { duration in
                script.slept(duration)
                if sleeps { try await Task.sleep(for: .seconds(60)) }
            },
            saved: { script.saved },
            attempt: { alive in try await attempt(script.attempt(), alive) }
        )
    }

    @Test func networkErrorsAreRetriedAfterOneTwoFourAndEightSecondsThenItStops() async {
        let script = Script()
        let download = make(script) { _, _ in throw URLError(.notConnectedToInternet) }
        await #expect(throws: URLError.self) { try await download.run { script.event($0) } }
        #expect(script.sleeps == ResumableDownload.backoff)
        #expect(script.sleeps == [.seconds(1), .seconds(2), .seconds(4), .seconds(8)])
        #expect(script.attempts == 5)
        #expect(script.events == [.waiting(.seconds(1)), .retrying, .waiting(.seconds(2)), .retrying, .waiting(.seconds(4)), .retrying, .waiting(.seconds(8)), .retrying])
    }

    @Test func anAttemptThatGotFurtherStartsTheCountAgain() async throws {
        let script = Script()
        let download = make(script) { attempt, _ in
            if attempt == 3 { script.grow(1_000) }
            if attempt < 5 { throw URLError(.networkConnectionLost) }
        }
        try await download.run()
        #expect(script.sleeps == [.seconds(1), .seconds(2), .seconds(1), .seconds(2)])
        #expect(script.attempts == 5)
    }

    @Test func aStalledAttemptIsStoppedAndTriedAgain() async throws {
        let script = Script()
        // Long enough that a busy test run can't stall the second attempt before it starts.
        let download = make(script, stallTimeout: .milliseconds(300)) { attempt, alive in
            alive()
            // The first attempt hangs with nothing coming; it is stopped, not waited for.
            if attempt == 1 { try await Task.sleep(for: .seconds(60)) }
        }
        let began = ContinuousClock.now
        try await download.run { script.event($0) }
        #expect(ContinuousClock.now - began < .seconds(5))
        #expect(script.attempts == 2)
        #expect(script.events == [.waiting(.seconds(1)), .retrying])
    }

    @Test func tryNowEndsTheWait() async throws {
        let script = Script()
        let download = make(script, sleeps: true) { attempt, _ in
            if attempt == 1 { throw URLError(.timedOut) }
        }
        let run = Task { try await download.run() }
        // The wait is a minute long; Try now ends it.
        for _ in 0 ..< 500 where script.attempts < 2 {
            download.retryNow()
            try await Task.sleep(for: .milliseconds(10))
        }
        try await run.value
        #expect(script.attempts == 2)
    }

    @Test func anErrorThatIsNotRetriedStopsAtOnce() async {
        let script = Script()
        let download = ResumableDownload(isRetryable: { !($0 is CocoaError) }, saved: { 0 }) { _ in
            _ = script.attempt()
            throw CocoaError(.fileWriteOutOfSpace)
        }
        await #expect(throws: CocoaError.self) { try await download.run() }
        #expect(script.attempts == 1)
    }
}

/// The download's updates in order, each from its attempt: a late one from a failed attempt is dropped.
struct DownloadTrackerTests {
    private let date = Date(timeIntervalSinceReferenceDate: 1_000)

    @Test func lateBytesFromAFailedAttemptDontUndoTheRetry() {
        var progress = DownloadTracker(ModelDownload(done: 0, total: nil))
        progress.apply(.bytes(done: 100, total: 1_000, attempt: 1), uptime: 0, date: date)
        #expect(progress.download.connection == .flowing)
        progress.apply(.event(.waiting(.seconds(4)), attempt: 1), uptime: 1, date: date)
        #expect(progress.download.connection == .waiting(retryAt: date.addingTimeInterval(4)))
        // Bytes from attempt 1 that were still on their way when it failed.
        let late = progress.apply(.bytes(done: 120, total: 1_000, attempt: 1), uptime: 1.1, date: date)
        #expect(!late)
        #expect(progress.download.connection == .waiting(retryAt: date.addingTimeInterval(4)))
        #expect(progress.download.done == 100)
        progress.apply(.event(.retrying, attempt: 1), uptime: 5, date: date)
        #expect(progress.download.connection == .retrying)
        let stillLate = progress.apply(.bytes(done: 130, total: 1_000, attempt: 1), uptime: 5.1, date: date)
        #expect(!stillLate)
        #expect(progress.download.connection == .retrying)
        // The next attempt's bytes: the connection is back.
        let fromTheNext = progress.apply(.bytes(done: 140, total: 1_000, attempt: 2), uptime: 5.2, date: date)
        #expect(fromTheNext)
        #expect(progress.download.connection == .flowing)
        #expect(progress.download.done == 140)
    }

    @Test func aWaitClearsTheSpeedAndTheTotalIsKept() {
        var progress = DownloadTracker(ModelDownload(done: 0, total: 1_000))
        for tenth in 0 ... 40 {
            progress.apply(.bytes(done: Int64(tenth) * 10, total: nil, attempt: 1), uptime: Double(tenth) / 10, date: date)
        }
        #expect(progress.download.total == 1_000)
        #expect(progress.download.bytesPerSecond != nil)
        progress.apply(.event(.waiting(.seconds(1)), attempt: 1), uptime: 4.1, date: date)
        #expect(progress.download.bytesPerSecond == nil)
        #expect(progress.download.secondsLeft == nil)
    }
}

struct DownloadRateTests {
    @Test func silentForTheFirstThreeSecondsThenSmoothed() throws {
        var rate = DownloadRate()
        #expect(rate.update(done: 100_000_000, total: 640_000_000, now: 0) == nil)
        // 1 MB/s from bytes already on the phone; nothing for the first 3 s.
        for tenth in 1 ..< 30 {
            #expect(rate.update(done: 100_000_000 + Int64(tenth) * 100_000, total: 640_000_000, now: Double(tenth) / 10) == nil)
        }
        let last = rate.update(done: 103_000_000, total: 640_000_000, now: 3)
        let reading = try #require(last)
        #expect(abs(reading.bytesPerSecond - 1_000_000) < 1)
        #expect(reading.secondsLeft == 537)
    }
}
