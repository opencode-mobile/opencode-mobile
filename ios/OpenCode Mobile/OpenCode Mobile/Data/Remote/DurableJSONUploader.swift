import Foundation
#if canImport(UIKit)
import UIKit
#endif

/// Sends prompt and shell bodies so a send survives the app leaving the foreground (OPE-221).
///
/// A `directFirst` send (prompts) goes over an in-process session while the app is active:
/// the background session runs in a system daemon that can defer or hold an upload with no
/// signal to the app (#1002). The background session takes over when the app is about to be
/// suspended with the send unanswered, or when the direct attempt fails. Other sends keep
/// using the background session only, because a resend would run a shell command twice.
///
/// Every attempt uploads the same body, so the prompt's message id lets the server ignore a
/// repeat. The diagnostic log gets one line per attempt, a pending line every 10 s, and the
/// task metrics, so a stuck send shows where it stopped.
nonisolated final class DurableJSONUploader: NSObject, URLSessionTaskDelegate, URLSessionDataDelegate, URLSessionDownloadDelegate, @unchecked Sendable {
    static let sessionIdentifier = "dev.opencode.mobile.prompt-send"
    /// A background upload the app no longer tracks is cancelled at launch once it is older than this.
    static let orphanMaxAgeMs: Int64 = 2 * 60 * 1_000
    static let pendingLogIntervalNanoseconds: UInt64 = 10_000_000_000

    static let shared: DurableJSONUploader = {
        let configuration = URLSessionConfiguration.background(withIdentifier: sessionIdentifier)
        configuration.isDiscretionary = false
        configuration.sessionSendsLaunchEvents = true
        configuration.waitsForConnectivity = true
        configuration.timeoutIntervalForRequest = 60 * 15
        configuration.timeoutIntervalForResource = 60 * 30
        configuration.sharedContainerIdentifier = nil
        PromptSendEnvironment.shared.start()
        let uploader = DurableJSONUploader(configuration: configuration, directConfiguration: directConfiguration())
        uploader.sweepOrphanedTasks()
        return uploader
    }()

    /// In-process session for foreground prompt sends. A prompt answers once the server admits
    /// it; 60 s of silence covers a cold project loading its plugins.
    static func directConfiguration() -> URLSessionConfiguration {
        let configuration = URLSessionConfiguration.default
        configuration.waitsForConnectivity = false
        configuration.timeoutIntervalForRequest = 60
        configuration.timeoutIntervalForResource = 60 * 15
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        return configuration
    }

    private enum Transport: String {
        case direct
        case background
    }

    private final class Send {
        let id: Int
        let request: URLRequest
        let fileURL: URL
        let path: String
        let startedAt = Date()
        let continuation: CheckedContinuation<(Data, URLResponse), Error>
        let onProgress: (@Sendable (Int64, Int64) -> Void)?
        let control: PromptSendControl?
        var attempts = 0
        var current: Attempt?
        var finished = false
        var assertion: Any?
        var pendingLog: Task<Void, Never>?

        init(
            id: Int,
            request: URLRequest,
            fileURL: URL,
            continuation: CheckedContinuation<(Data, URLResponse), Error>,
            onProgress: (@Sendable (Int64, Int64) -> Void)?,
            control: PromptSendControl?
        ) {
            self.id = id
            self.request = request
            self.fileURL = fileURL
            self.path = request.url?.path ?? "-"
            self.continuation = continuation
            self.onProgress = onProgress
            self.control = control
        }
    }

    private final class Attempt {
        let task: URLSessionTask
        let transport: Transport
        let number: Int
        let send: Send
        var data = Data()
        var response: URLResponse?
        /// A newer attempt replaced this one; it still wins if it gets a success answer first.
        var superseded = false

        init(task: URLSessionTask, transport: Transport, number: Int, send: Send) {
            self.task = task
            self.transport = transport
            self.number = number
            self.send = send
        }

        var label: String { "#\(send.id) attempt=\(number) via=\(transport.rawValue)" }
    }

    private var durableSession: URLSession!
    private let directConfiguration: URLSessionConfiguration?
    private var directSession: URLSession?
    private let isAppActive: @Sendable () async -> Bool
    private let lock = NSLock()
    private var nextSendID = 0
    private var sends: [Int: Send] = [:]
    private var attempts: [ObjectIdentifier: Attempt] = [:]
    private var backgroundEventsResume: (() -> Void)?

    init(
        configuration: URLSessionConfiguration,
        directConfiguration: URLSessionConfiguration? = nil,
        isAppActive: (@Sendable () async -> Bool)? = nil
    ) {
        self.directConfiguration = directConfiguration
        self.isAppActive = isAppActive ?? { await PromptSendEnvironment.isApplicationActive() }
        super.init()
        durableSession = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
    }

    func upload(
        _ request: URLRequest,
        body: Data,
        directFirst: Bool = false,
        onProgress: (@Sendable (Int64, Int64) -> Void)?
    ) async throws -> (Data, URLResponse) {
        let fileURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("prompt-send-\(UUID().uuidString).json")
        try body.write(to: fileURL, options: .atomic)
        return try await upload(request, fileURL: fileURL, directFirst: directFirst, onProgress: onProgress)
    }

    func upload(
        _ request: URLRequest,
        fileURL: URL,
        directFirst: Bool = false,
        onProgress: (@Sendable (Int64, Int64) -> Void)?
    ) async throws -> (Data, URLResponse) {
        let size = (try? FileManager.default.attributesOfItem(atPath: fileURL.path)[.size] as? NSNumber)?.int64Value ?? 0
        onProgress?(0, size)
        let canGoDirect = directFirst && directConfiguration != nil
        let appActive = canGoDirect ? await isAppActive() : false
        let control = canGoDirect ? PromptSendControl.current : nil
        let id = lock.withLock {
            nextSendID += 1
            return nextSendID
        }
        let assertion = await Self.beginBackgroundAssertion { [weak self] in
            self?.backgroundTimeExpiring(sendID: id)
        }
        return try await withCheckedThrowingContinuation { continuation in
            let send = Send(
                id: id,
                request: request,
                fileURL: fileURL,
                continuation: continuation,
                onProgress: onProgress,
                control: control
            )
            send.assertion = assertion
            lock.withLock { sends[id] = send }
            control?.bind { [weak self] in self?.retry(sendID: id) }
            DiagnosticLog.info(
                .connection,
                "prompt-send #\(id) start path=\(send.path) bytes=\(size) directFirst=\(canGoDirect) \(PromptSendEnvironment.shared.summary())"
            )
            startAttempt(send, via: appActive ? .direct : .background, reason: "send")
            startPendingLog(send)
        }
    }

    func waitForBackgroundEvents() async {
        await withCheckedContinuation { continuation in
            lock.lock()
            if sends.isEmpty {
                lock.unlock()
                continuation.resume()
                return
            }
            backgroundEventsResume = { continuation.resume() }
            lock.unlock()
        }
    }

    // MARK: - Attempts

    private func startAttempt(_ send: Send, via transport: Transport, reason: String) {
        lock.lock()
        guard !send.finished else {
            lock.unlock()
            return
        }
        let previous = send.current
        previous?.superseded = true
        let session = transport == .direct ? directSessionLocked() : durableSession!
        var uploadRequest = send.request
        uploadRequest.httpBody = nil
        let task = session.uploadTask(with: uploadRequest, fromFile: send.fileURL)
        task.taskDescription = Self.taskDescription(createdAtMs: Self.nowMs())
        send.attempts += 1
        let attempt = Attempt(task: task, transport: transport, number: send.attempts, send: send)
        send.current = attempt
        attempts[ObjectIdentifier(task)] = attempt
        lock.unlock()
        DiagnosticLog.info(.connection, "prompt-send \(attempt.label) reason=\(reason)")
        task.resume()
        previous?.task.cancel()
    }

    /// Caller holds `lock`.
    private func directSessionLocked() -> URLSession {
        if let directSession { return directSession }
        let session = URLSession(configuration: directConfiguration ?? .default, delegate: self, delegateQueue: nil)
        directSession = session
        return session
    }

    /// The user asked again: a new direct attempt on a new pool, so a held connection is not reused.
    private func retry(sendID: Int) {
        lock.lock()
        guard let send = sends[sendID], !send.finished else {
            lock.unlock()
            return
        }
        let stale = directSession
        directSession = nil
        lock.unlock()
        // Tasks still running on the old pool finish there; no new task is created on it.
        stale?.finishTasksAndInvalidate()
        DiagnosticLog.info(.connection, "prompt-send #\(sendID) retry requested")
        startAttempt(send, via: .direct, reason: "retry")
    }

    /// iOS is about to suspend the app: hand an unanswered direct attempt to the background session.
    private func backgroundTimeExpiring(sendID: Int) {
        lock.lock()
        let send = sends[sendID]
        // The expiration handler ends the assertion itself.
        send?.assertion = nil
        let needsHandOff = send.map { !$0.finished && $0.current?.transport == .direct } ?? false
        lock.unlock()
        guard let send, needsHandOff else { return }
        startAttempt(send, via: .background, reason: "suspending")
    }

    private func startPendingLog(_ send: Send) {
        let id = send.id
        let task = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: Self.pendingLogIntervalNanoseconds)
                guard !Task.isCancelled, let self else { return }
                self.logPending(sendID: id)
            }
        }
        lock.withLock {
            if send.finished {
                task.cancel()
            } else {
                send.pendingLog = task
            }
        }
    }

    private func logPending(sendID: Int) {
        lock.lock()
        guard let send = sends[sendID], !send.finished, let attempt = send.current else {
            lock.unlock()
            return
        }
        let task = attempt.task
        let elapsed = Self.elapsedMs(since: send.startedAt)
        let label = attempt.label
        lock.unlock()
        DiagnosticLog.info(
            .connection,
            "prompt-send \(label) pending elapsedMs=\(elapsed) state=\(Self.describe(task.state)) "
                + "sent=\(task.countOfBytesSent)/\(task.countOfBytesExpectedToSend) received=\(task.countOfBytesReceived) "
                + PromptSendEnvironment.shared.summary()
        )
    }

    // MARK: - Leftover background tasks

    /// Background uploads outlive the app. One the app no longer tracks would land minutes later,
    /// out of order, after the user gave up on it (#1002); log them all and cancel the old ones.
    func sweepOrphanedTasks() {
        durableSession.getAllTasks { [weak self] tasks in
            guard let self else { return }
            let now = Self.nowMs()
            let tracked = self.lock.withLock { Set(self.attempts.keys) }
            let orphans = tasks.filter { !tracked.contains(ObjectIdentifier($0)) }
            DiagnosticLog.info(.connection, "prompt-send leftover tasks=\(orphans.count)")
            for task in orphans {
                let age = Self.createdAtMs(taskDescription: task.taskDescription).map { now - $0 }
                let cancel = Self.shouldCancelOrphan(ageMs: age)
                DiagnosticLog.info(
                    .connection,
                    "prompt-send leftover task=\(task.taskIdentifier) ageMs=\(age.map(String.init) ?? "-") "
                        + "state=\(Self.describe(task.state)) sent=\(task.countOfBytesSent)/\(task.countOfBytesExpectedToSend) "
                        + "action=\(cancel ? "cancel" : "keep")"
                )
                if cancel { task.cancel() }
            }
        }
    }

    static func shouldCancelOrphan(ageMs: Int64?) -> Bool {
        // No creation time means an upload from before this check existed: stale by now.
        guard let ageMs else { return true }
        return ageMs > orphanMaxAgeMs
    }

    static func taskDescription(createdAtMs: Int64) -> String {
        "prompt-send createdAtMs=\(createdAtMs)"
    }

    static func createdAtMs(taskDescription: String?) -> Int64? {
        guard let taskDescription,
              let range = taskDescription.range(of: "createdAtMs=")
        else { return nil }
        return Int64(taskDescription[range.upperBound...])
    }

    // MARK: - URLSession delegate

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didSendBodyData bytesSent: Int64,
        totalBytesSent: Int64,
        totalBytesExpectedToSend: Int64
    ) {
        lock.lock()
        let attempt = attempts[ObjectIdentifier(task)]
        let progress = attempt.flatMap { $0.superseded ? nil : $0.send.onProgress }
        lock.unlock()
        progress?(totalBytesSent, totalBytesExpectedToSend)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock()
        attempts[ObjectIdentifier(dataTask)]?.data.append(data)
        lock.unlock()
    }

    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        lock.lock()
        attempts[ObjectIdentifier(dataTask)]?.response = response
        lock.unlock()
        completionHandler(.allow)
    }

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didFinishDownloadingTo location: URL
    ) {
        let data = (try? Data(contentsOf: location)) ?? Data()
        lock.lock()
        attempts[ObjectIdentifier(downloadTask)]?.data = data
        attempts[ObjectIdentifier(downloadTask)]?.response = downloadTask.response
        lock.unlock()
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        RedirectPolicyDelegate.shared.urlSession(
            session,
            task: task,
            willPerformHTTPRedirection: response,
            newRequest: request,
            completionHandler: completionHandler
        )
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didFinishCollecting metrics: URLSessionTaskMetrics) {
        let label = lock.withLock { attempts[ObjectIdentifier(task)]?.label } ?? "leftover task=\(task.taskIdentifier)"
        DiagnosticLog.info(.connection, "prompt-send \(label) metrics \(PromptUploadMetrics.describe(metrics))")
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.lock()
        guard let attempt = attempts.removeValue(forKey: ObjectIdentifier(task)) else {
            lock.unlock()
            DiagnosticLog.info(
                .connection,
                "prompt-send leftover task=\(task.taskIdentifier) ended \(Self.outcome(task.response, error))"
            )
            return
        }
        let send = attempt.send
        let response = attempt.response ?? task.response
        let succeeded = error == nil && (response as? HTTPURLResponse).map { (200 ..< 300).contains($0.statusCode) } == true
        if send.finished || (attempt.superseded && !succeeded) {
            lock.unlock()
            DiagnosticLog.info(.connection, "prompt-send \(attempt.label) ended after replacement \(Self.outcome(response, error))")
            return
        }
        if !attempt.superseded, let error, attempt.transport == .direct, !Self.isCancellation(error) {
            lock.unlock()
            DiagnosticLog.warning(.connection, "prompt-send \(attempt.label) failed \(Self.outcome(response, error))")
            startAttempt(send, via: .background, reason: "direct-failed")
            return
        }
        send.finished = true
        sends.removeValue(forKey: send.id)
        let replacement = send.current === attempt ? nil : send.current
        let resumeBackground = sends.isEmpty ? backgroundEventsResume : nil
        if resumeBackground != nil {
            backgroundEventsResume = nil
        }
        let pendingLog = send.pendingLog
        let assertion = send.assertion
        send.assertion = nil
        lock.unlock()

        replacement?.task.cancel()
        pendingLog?.cancel()
        send.control?.unbind()
        DiagnosticLog.info(
            .connection,
            "prompt-send \(attempt.label) done elapsedMs=\(Self.elapsedMs(since: send.startedAt)) \(Self.outcome(response, error))"
        )
        try? FileManager.default.removeItem(at: send.fileURL)
        if let error {
            send.continuation.resume(throwing: error)
        } else if let response {
            send.continuation.resume(returning: (attempt.data, response))
        } else {
            send.continuation.resume(
                throwing: NSError(
                    domain: NSURLErrorDomain,
                    code: NSURLErrorUnknown,
                    userInfo: [NSLocalizedDescriptionKey: "Missing HTTP response"]
                )
            )
        }
        Task { @MainActor in Self.endBackgroundAssertion(assertion) }
        resumeBackground?()
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        lock.lock()
        let resume = backgroundEventsResume
        backgroundEventsResume = nil
        lock.unlock()
        resume?()
    }

    // MARK: - Helpers

    private static func isCancellation(_ error: Error) -> Bool {
        (error as NSError).domain == NSURLErrorDomain && (error as NSError).code == NSURLErrorCancelled
    }

    /// Status or error domain and code only: an error's description carries the server URL.
    private static func outcome(_ response: URLResponse?, _ error: Error?) -> String {
        if let error {
            let nsError = error as NSError
            return "error=\(nsError.domain):\(nsError.code)"
        }
        guard let http = response as? HTTPURLResponse else { return "status=-" }
        return "status=\(http.statusCode)"
    }

    private static func describe(_ state: URLSessionTask.State) -> String {
        switch state {
        case .running: "running"
        case .suspended: "suspended"
        case .canceling: "canceling"
        case .completed: "completed"
        @unknown default: "unknown"
        }
    }

    private static func nowMs() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1_000)
    }

    private static func elapsedMs(since start: Date) -> Int {
        max(0, Int(Date().timeIntervalSince(start) * 1_000))
    }

    @MainActor
    private static func beginBackgroundAssertion(onExpiration: @escaping @Sendable () -> Void) -> Any? {
        #if canImport(UIKit)
        var identifier: UIBackgroundTaskIdentifier = .invalid
        identifier = UIApplication.shared.beginBackgroundTask(withName: "prompt-send") {
            onExpiration()
            UIApplication.shared.endBackgroundTask(identifier)
            identifier = .invalid
        }
        return identifier
        #else
        return nil
        #endif
    }

    @MainActor
    private static func endBackgroundAssertion(_ token: Any?) {
        #if canImport(UIKit)
        guard let identifier = token as? UIBackgroundTaskIdentifier, identifier != .invalid else { return }
        UIApplication.shared.endBackgroundTask(identifier)
        #endif
    }
}
