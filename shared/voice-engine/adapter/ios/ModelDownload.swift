import Foundation
import os

/// The speech model's download as the app shows it: how much is on the phone, how fast it comes, and whether the
/// connection dropped (app plan, "The speech model" › Download).
public struct ModelDownload: Equatable, Sendable {
    public enum Connection: Equatable, Sendable {
        /// Bytes are coming (or the first attempt is starting).
        case flowing
        /// An attempt failed; the next one starts at `retryAt` (or at once with Try now).
        case waiting(retryAt: Date)
        /// The next attempt is under way and nothing has arrived from it yet.
        case retrying
    }

    /// Bytes of the model on the phone, partial files included.
    public var done: Int64
    /// The whole download in bytes; nil until the first bytes have come in on a fresh install.
    public var total: Int64?
    /// Smoothed; nil for the first 3 s of an attempt, and while nothing comes.
    public var bytesPerSecond: Double?
    /// At the current speed; nil while the speed isn't known.
    public var secondsLeft: Int?
    public var connection: Connection

    public init(done: Int64, total: Int64?, bytesPerSecond: Double? = nil, secondsLeft: Int? = nil, connection: Connection = .flowing) {
        self.done = done
        self.total = total
        self.bytesPerSecond = bytesPerSecond
        self.secondsLeft = secondsLeft
        self.connection = connection
    }

    /// The share on the phone, 0 to 1; 0 while the total isn't known.
    public var fraction: Double {
        guard let total, total > 0 else { return 0 }
        return min(1, max(0, Double(done) / Double(total)))
    }
}

/// How long a download has left, from its speed smoothed over the last several seconds. Says nothing for its first
/// `warmUp`, so bytes resumed from an earlier attempt and a slow start don't count as speed, nor while it's stalled.
/// The same rules as Android's `DownloadRate`.
public struct DownloadRate: Sendable {
    public static let warmUp: TimeInterval = 3
    static let sample: TimeInterval = 0.5
    static let smoothing = 0.2

    private var startedAt: TimeInterval?
    private var lastAt: TimeInterval = 0
    private var lastDone: Int64 = 0
    /// Bytes per second.
    private var speed = 0.0

    public init() {}

    /// Adds a reading (`done` bytes at `now`, in seconds of any monotonic clock) and returns the speed and the
    /// seconds left, once known.
    public mutating func update(done: Int64, total: Int64?, now: TimeInterval) -> (bytesPerSecond: Double, secondsLeft: Int?)? {
        guard let startedAt else {
            self.startedAt = now
            lastAt = now
            lastDone = done
            return nil
        }
        let elapsed = now - lastAt
        if elapsed >= Self.sample {
            let reading = Double(max(0, done - lastDone)) / elapsed
            speed = speed == 0 ? reading : speed + Self.smoothing * (reading - speed)
            lastAt = now
            lastDone = done
        }
        guard now - startedAt >= Self.warmUp, speed > 0 else { return nil }
        let left = total.map { Int((Double(max(0, $0 - done)) / speed).rounded(.down)) }
        return (speed, left)
    }
}

/// The download as it stands, from its updates in the order they were sent: bytes from an attempt, and the retry
/// events between attempts. Each update names its attempt (1 for the first), so bytes that arrive late from an
/// attempt that already failed are dropped: they can't turn "retrying in 4 s" back into a flowing download.
public struct DownloadTracker: Sendable {
    public enum Update: Equatable, Sendable {
        /// Bytes on the phone during `attempt`, and the whole download when it can be told.
        case bytes(done: Int64, total: Int64?, attempt: Int)
        /// `attempt` failed (`.waiting`), or the one after it is starting (`.retrying`).
        case event(ResumableDownload.Event, attempt: Int)
    }

    public private(set) var download: ModelDownload
    private var rate = DownloadRate()
    /// The last attempt known to have ended; bytes from it or an earlier one are late.
    private var ended = 0

    public init(_ download: ModelDownload) {
        self.download = download
    }

    /// Applies `update` (`uptime` from a monotonic clock for the speed, `date` for the retry time). Returns false when
    /// it was dropped as late.
    @discardableResult
    public mutating func apply(_ update: Update, uptime: TimeInterval, date: Date) -> Bool {
        switch update {
        case let .bytes(done, total, attempt):
            guard attempt > ended else { return false }
            download.done = done
            if let total { download.total = total }
            if download.connection != .flowing {
                // Bytes again from a live attempt: the connection is back, and the speed is measured afresh.
                download.connection = .flowing
                rate = DownloadRate()
            }
            let reading = rate.update(done: download.done, total: download.total, now: uptime)
            download.bytesPerSecond = reading?.bytesPerSecond
            download.secondsLeft = reading?.secondsLeft
        case let .event(event, attempt):
            ended = max(ended, attempt)
            switch event {
            case let .waiting(pause):
                download.connection = .waiting(retryAt: date.addingTimeInterval(TimeInterval(pause.components.seconds)))
                download.bytesPerSecond = nil
                download.secondsLeft = nil
            case .retrying:
                download.connection = .retrying
            }
        }
        return true
    }
}

/// Runs a download that resumes, one attempt after another, until it finishes (app plan, "The speech model" ›
/// Download). Each attempt carries on from what is already on the phone. Errors are retried after 1, 2, 4 and 8 s;
/// an attempt that got more onto the phone starts that count again, so a long download on a flaky network keeps
/// going as long as it moves. An attempt that gets nothing for `stallTimeout` is stopped and counts as a dropped
/// connection. Try now (`retryNow()`) cuts the wait short.
public final class ResumableDownload: Sendable {
    /// Waits between failed attempts that got no further: four retries, then the download fails.
    public static let backoff: [Duration] = [.seconds(1), .seconds(2), .seconds(4), .seconds(8)]
    public static let defaultStallTimeout: Duration = .seconds(30)

    public enum Event: Equatable, Sendable {
        /// The attempt failed; the next one starts after this long.
        case waiting(Duration)
        /// The next attempt starts now.
        case retrying
    }

    /// An attempt got nothing for `stallTimeout`.
    public struct Stalled: Error, Equatable {}

    public typealias Attempt = @Sendable (_ alive: @escaping @Sendable () -> Void) async throws -> Void

    private let attempt: Attempt
    private let saved: @Sendable () -> Int64
    private let stallTimeout: Duration
    private let sleep: @Sendable (Duration) async throws -> Void
    private let isRetryable: @Sendable (any Error) -> Bool
    private let wait = OSAllocatedUnfairLock<Task<Void, any Error>?>(initialState: nil)

    /// - Parameters:
    ///   - attempt: One try at the whole download; it calls `alive` whenever something arrives.
    ///   - saved: Bytes on the phone so far, to tell whether an attempt got further.
    public init(
        stallTimeout: Duration = defaultStallTimeout,
        sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) },
        isRetryable: @escaping @Sendable (any Error) -> Bool = { !($0 is CancellationError) },
        saved: @escaping @Sendable () -> Int64,
        attempt: @escaping Attempt
    ) {
        self.attempt = attempt
        self.saved = saved
        self.stallTimeout = stallTimeout
        self.sleep = sleep
        self.isRetryable = isRetryable
    }

    /// Returns once an attempt finished, or throws the last error once the retries are used up.
    public func run(events: @escaping @Sendable (Event) -> Void = { _ in }) async throws {
        var failures = 0
        while true {
            let before = saved()
            do {
                try await watched()
                return
            } catch {
                if Task.isCancelled { throw CancellationError() }
                guard isRetryable(error) else { throw error }
                if saved() > before { failures = 0 }
                guard failures < Self.backoff.count else { throw error }
                let pause = Self.backoff[failures]
                failures += 1
                events(.waiting(pause))
                try await self.pause(pause)
                events(.retrying)
            }
        }
    }

    /// Try now: the wait before the next attempt ends at once.
    public func retryNow() {
        wait.withLock { $0 }?.cancel()
    }

    /// Sleeps for `duration`, or less after Try now. Throws only when the caller is cancelled.
    private func pause(_ duration: Duration) async throws {
        let sleep = sleep
        let sleeper = Task { try await sleep(duration) }
        wait.withLock { $0 = sleeper }
        await withTaskCancellationHandler {
            _ = await sleeper.result
        } onCancel: {
            sleeper.cancel()
        }
        wait.withLock { $0 = nil }
        try Task.checkCancellation()
    }

    /// One attempt, stopped when nothing arrives for `stallTimeout`.
    private func watched() async throws {
        let clock = ContinuousClock()
        let last = OSAllocatedUnfairLock(initialState: clock.now)
        let attempt = attempt
        let timeout = stallTimeout
        let check = min(Duration.seconds(1), timeout / 4)
        try await withThrowingTaskGroup(of: Void.self) { group in
            group.addTask {
                try await attempt { last.withLock { $0 = clock.now } }
            }
            group.addTask {
                while true {
                    try await Task.sleep(for: check)
                    if clock.now - last.withLock({ $0 }) >= timeout { throw Stalled() }
                }
            }
            // The first to end: the attempt finished, or one of them threw. The other is cancelled.
            try await group.next()
            group.cancelAll()
        }
    }
}
