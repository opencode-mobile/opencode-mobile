import Foundation
import Network
#if canImport(UIKit)
import UIKit
#endif

/// Device conditions that can hold a prompt upload, for the diagnostic log (#1002):
/// app state, Low Power Mode, and the network path (Low Data Mode shows as `constrained`).
nonisolated final class PromptSendEnvironment: @unchecked Sendable {
    static let shared = PromptSendEnvironment()

    private let lock = NSLock()
    private var started = false
    private var appState = "unknown"
    private var pathSummary = "net=unknown"
    private let monitor = NWPathMonitor()
    private var observers: [NSObjectProtocol] = []

    func start() {
        let shouldStart = lock.withLock {
            defer { started = true }
            return !started
        }
        guard shouldStart else { return }
        monitor.pathUpdateHandler = { [weak self] path in
            let summary = Self.describe(path)
            self?.lock.withLock { self?.pathSummary = summary }
        }
        monitor.start(queue: DispatchQueue(label: "dev.opencode.mobile.prompt-send.path", qos: .utility))
        #if canImport(UIKit)
        Task { @MainActor [weak self] in
            self?.observeApplicationState()
        }
        #endif
    }

    /// One log fragment: `app=active lowPower=false net=satisfied constrained=false expensive=false iface=wifi`.
    func summary() -> String {
        let (app, path) = lock.withLock { (appState, pathSummary) }
        return "app=\(app) lowPower=\(ProcessInfo.processInfo.isLowPowerModeEnabled) \(path)"
    }

    @MainActor
    static func isApplicationActive() -> Bool {
        #if canImport(UIKit)
        UIApplication.shared.applicationState == .active
        #else
        true
        #endif
    }

    static func describe(_ path: NWPath) -> String {
        let status = switch path.status {
        case .satisfied: "satisfied"
        case .unsatisfied: "unsatisfied"
        case .requiresConnection: "requiresConnection"
        @unknown default: "unknown"
        }
        let interface: String =
            if path.usesInterfaceType(.wifi) { "wifi" }
            else if path.usesInterfaceType(.cellular) { "cellular" }
            else if path.usesInterfaceType(.wiredEthernet) { "wired" }
            else if path.usesInterfaceType(.loopback) { "loopback" }
            else if path.usesInterfaceType(.other) { "other" }
            else { "none" }
        return "net=\(status) constrained=\(path.isConstrained) expensive=\(path.isExpensive) iface=\(interface)"
    }

    #if canImport(UIKit)
    @MainActor
    private func observeApplicationState() {
        record(Self.describe(UIApplication.shared.applicationState))
        let center = NotificationCenter.default
        let states: [(Notification.Name, String)] = [
            (UIApplication.didBecomeActiveNotification, "active"),
            (UIApplication.willResignActiveNotification, "inactive"),
            (UIApplication.didEnterBackgroundNotification, "background"),
            (UIApplication.willEnterForegroundNotification, "inactive"),
        ]
        observers = states.map { name, state in
            center.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                self?.record(state)
            }
        }
    }

    private func record(_ state: String) {
        lock.withLock { appState = state }
    }

    private static func describe(_ state: UIApplication.State) -> String {
        switch state {
        case .active: "active"
        case .inactive: "inactive"
        case .background: "background"
        @unknown default: "unknown"
        }
    }
    #endif
}

/// Formats `URLSessionTaskMetrics` for the diagnostic log. Timings are milliseconds from the
/// task's start. Remote addresses are left out on purpose: they identify the user's server.
nonisolated enum PromptUploadMetrics {
    static func describe(_ metrics: URLSessionTaskMetrics) -> String {
        let start = metrics.taskInterval.start
        let transactions = metrics.transactionMetrics.enumerated().map { index, transaction in
            describe(transaction, index: index + 1, taskStart: start)
        }
        return "taskMs=\(ms(metrics.taskInterval.duration)) redirects=\(metrics.redirectCount) "
            + "transactions=\(metrics.transactionMetrics.count)"
            + (transactions.isEmpty ? "" : " " + transactions.joined(separator: " "))
    }

    private static func describe(
        _ transaction: URLSessionTaskTransactionMetrics,
        index: Int,
        taskStart: Date
    ) -> String {
        func offset(_ date: Date?) -> String {
            date.map { String(ms($0.timeIntervalSince(taskStart))) } ?? "-"
        }
        let fetchType = switch transaction.resourceFetchType {
        case .networkLoad: "network"
        case .serverPush: "push"
        case .localCache: "cache"
        case .unknown: "unknown"
        @unknown default: "unknown"
        }
        let status = (transaction.response as? HTTPURLResponse).map { String($0.statusCode) } ?? "-"
        return "[t\(index) fetch=\(fetchType) status=\(status) proto=\(transaction.networkProtocolName ?? "-") "
            + "reused=\(transaction.isReusedConnection) proxy=\(transaction.isProxyConnection) "
            + "constrained=\(transaction.isConstrained) expensive=\(transaction.isExpensive) cellular=\(transaction.isCellular) "
            + "fetchStart=\(offset(transaction.fetchStartDate)) connect=\(offset(transaction.connectStartDate))-\(offset(transaction.connectEndDate)) "
            + "tls=\(offset(transaction.secureConnectionStartDate))-\(offset(transaction.secureConnectionEndDate)) "
            + "request=\(offset(transaction.requestStartDate))-\(offset(transaction.requestEndDate)) "
            + "response=\(offset(transaction.responseStartDate))-\(offset(transaction.responseEndDate)) "
            + "sent=\(transaction.countOfRequestBodyBytesSent) received=\(transaction.countOfResponseBodyBytesReceived)]"
    }

    private static func ms(_ interval: TimeInterval) -> Int {
        max(0, Int(interval * 1_000))
    }
}
