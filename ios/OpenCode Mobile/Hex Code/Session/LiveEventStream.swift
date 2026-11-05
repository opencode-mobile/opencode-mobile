import Foundation
import Network
import OpenCodeKit

/// Today's session's event stream, kept open: it retries as OpenCode Mobile does (1, 2, 4 and
/// 8 s, then every 8 s, with ±25 % jitter, through OpenCodeKit's `SseAttemptPolicy`), at once on
/// `wake()` (back in front, the network returns), and treats more than 35 s with no data as down
/// (the server sends a heartbeat every 15 s).
@MainActor
final class LiveEventStream {
    static let stallTimeout: TimeInterval = 35
    static let watchdogInterval: Duration = .seconds(5)

    /// The stream is up (`server.connected` arrived). Read the state, then return.
    var onConnected: () async -> Void = {}
    /// The stream went down; it retries on its own.
    var onDisconnected: () -> Void = {}
    var onEvent: (SessionEvent) -> Void = { _ in }

    private let transport: any EventStreamTransport
    private let now: () -> Date
    private let sleep: @Sendable (Duration) async throws -> Void
    private let watchdogInterval: Duration
    private var loop: Task<Void, Never>?
    private var attemptTask: Task<SseAttemptOutcome, Error>?
    private var waitTask: Task<Void, Error>?
    private var watchdog: Task<Void, Never>?
    private var pathMonitor: NWPathMonitor?
    private var lastActivity = Date()
    private var attempt = 0
    private var holdUntil: Date?
    private var networkSatisfied = true
    private(set) var isUp = false

    init(
        transport: any EventStreamTransport,
        now: @escaping () -> Date = Date.init,
        sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) },
        watchdogInterval: Duration = LiveEventStream.watchdogInterval
    ) {
        self.transport = transport
        self.now = now
        self.sleep = sleep
        self.watchdogInterval = watchdogInterval
    }

    var isRunning: Bool { loop != nil }

    func start() {
        guard loop == nil else { return }
        attempt = 0
        loop = Task { await run() }
        watchdog = Task { await watch() }
    }

    func stop() {
        loop?.cancel()
        loop = nil
        watchdog?.cancel()
        watchdog = nil
        attemptTask?.cancel()
        waitTask?.cancel()
        markDown()
    }

    /// Retry now instead of waiting out the backoff.
    func wake() {
        guard loop != nil else { return start() }
        attempt = 0
        holdUntil = nil
        waitTask?.cancel()
    }

    /// Drops the connection as a network failure would, and keeps it down for `seconds` (a
    /// developer action, to see Reconnecting… and the read-back).
    func drop(holdFor seconds: TimeInterval) {
        holdUntil = now().addingTimeInterval(seconds)
        attemptTask?.cancel()
    }

    /// Wakes the stream when the network path becomes usable again.
    func watchNetwork() {
        guard pathMonitor == nil else { return }
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] path in
            let satisfied = path.status == .satisfied
            Task { @MainActor in self?.networkChanged(satisfied: satisfied) }
        }
        monitor.start(queue: DispatchQueue(label: "hexcode.event-stream.path"))
        pathMonitor = monitor
    }

    /// The network path changed; its return wakes the stream at once.
    func networkChanged(satisfied: Bool) {
        let returned = satisfied && !networkSatisfied
        networkSatisfied = satisfied
        if returned { wake() }
    }

    private func run() async {
        while !Task.isCancelled {
            lastActivity = now()
            let transport = transport
            let task = Task {
                try await transport.stream(
                    types: SessionEvent.types,
                    onActivity: { [weak self] in await self?.noteActivity() },
                    onLine: { [weak self] line in await self?.handle(line) }
                )
            }
            attemptTask = task
            let outcome: SseAttemptOutcome
            do {
                outcome = try await task.value
            } catch {
                outcome = .transport(error)
            }
            attemptTask = nil
            markDown()
            guard !Task.isCancelled else { break }
            attempt += 1
            var delay = switch SseAttemptPolicy.decide(outcome: outcome, protocolConfirmed: true, attempt: attempt) {
            case let .retry(ms): Double(ms) / 1000
            case let .authFailed(ms): Double(ms) / 1000
            }
            if let holdUntil {
                delay = max(delay, holdUntil.timeIntervalSince(now()))
                self.holdUntil = nil
            }
            let sleep = sleep
            let wait = Task { try await sleep(.milliseconds(Int(delay * 1000))) }
            waitTask = wait
            _ = try? await wait.value
            waitTask = nil
        }
    }

    private func watch() async {
        while !Task.isCancelled {
            try? await Task.sleep(for: watchdogInterval)
            guard let attemptTask, now().timeIntervalSince(lastActivity) > Self.stallTimeout else { continue }
            attemptTask.cancel()
        }
    }

    private func noteActivity() {
        lastActivity = now()
    }

    private func handle(_ line: String) async {
        lastActivity = now()
        guard let event = SessionEvent.decode(line) else { return }
        if event == .connected {
            isUp = true
            attempt = 0
            await onConnected()
        } else {
            onEvent(event)
        }
    }

    private func markDown() {
        isUp = false
        onDisconnected()
    }
}
