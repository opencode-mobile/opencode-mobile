import Foundation

/// Foreground-resume timing for logcat/os_log. One line per Foreground that later
/// bootstraps from `server.connected`.
nonisolated final class ResumeTiming: @unchecked Sendable {
    static let shared = ResumeTiming()

    private let lock = NSLock()
    private var originMs: Int64?
    private var cachePaintedDeltaMs: Int64?
    private var connectedDeltaMs: Int64?
    private var logged = false

    func begin(nowMs: Int64 = ResumeTiming.nowMs()) {
        lock.lock()
        originMs = nowMs
        cachePaintedDeltaMs = nil
        connectedDeltaMs = nil
        logged = false
        lock.unlock()
    }

    func markCachePainted(nowMs: Int64 = ResumeTiming.nowMs()) {
        lock.lock()
        guard let originMs, cachePaintedDeltaMs == nil else {
            lock.unlock()
            return
        }
        cachePaintedDeltaMs = max(0, nowMs - originMs)
        lock.unlock()
    }

    func markConnected(nowMs: Int64 = ResumeTiming.nowMs()) {
        lock.lock()
        guard let originMs, connectedDeltaMs == nil else {
            lock.unlock()
            return
        }
        connectedDeltaMs = max(0, nowMs - originMs)
        lock.unlock()
    }

    func markBootstrapDone(nowMs: Int64 = ResumeTiming.nowMs()) {
        lock.lock()
        guard let originMs, !logged else {
            lock.unlock()
            return
        }
        logged = true
        let cache = cachePaintedDeltaMs ?? 0
        let connected = connectedDeltaMs ?? max(0, nowMs - originMs)
        let bootstrap = max(0, nowMs - originMs)
        lock.unlock()
        DiagnosticLog.info(
            .sse,
            "resume: cache painted +\(cache)ms, connected +\(connected)ms, bootstrap done +\(bootstrap)ms"
        )
    }

    private static func nowMs() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1_000)
    }
}
