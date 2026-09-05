import Foundation
#if canImport(UIKit)
import UIKit
#endif

final class DurableJSONUploader: NSObject, URLSessionTaskDelegate, URLSessionDataDelegate, URLSessionDownloadDelegate, @unchecked Sendable {
    static let sessionIdentifier = "dev.opencode.mobile.prompt-send"

    static let shared: DurableJSONUploader = {
        let configuration = URLSessionConfiguration.background(withIdentifier: sessionIdentifier)
        configuration.isDiscretionary = false
        configuration.sessionSendsLaunchEvents = true
        configuration.waitsForConnectivity = true
        configuration.timeoutIntervalForRequest = 60 * 15
        configuration.timeoutIntervalForResource = 60 * 30
        configuration.sharedContainerIdentifier = nil
        return DurableJSONUploader(configuration: configuration)
    }()

    private var session: URLSession!
    private let usesBackgroundSession: Bool
    private let lock = NSLock()
    private var inflight: [Int: Inflight] = [:]
    private var backgroundEventsResume: (() -> Void)?

    private struct Inflight {
        let continuation: CheckedContinuation<(Data, URLResponse), Error>
        var data = Data()
        let onProgress: (@Sendable (Int64, Int64) -> Void)?
        let fileURL: URL
        var response: URLResponse?
    }

    init(configuration: URLSessionConfiguration) {
        usesBackgroundSession = configuration.identifier == Self.sessionIdentifier
        super.init()
        session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
    }

    func upload(
        _ request: URLRequest,
        body: Data,
        onProgress: (@Sendable (Int64, Int64) -> Void)?
    ) async throws -> (Data, URLResponse) {
        if !usesBackgroundSession {
            onProgress?(0, Int64(body.count))
            var uploadRequest = request
            uploadRequest.httpBody = nil
            return try await session.upload(
                for: uploadRequest,
                from: body,
                delegate: ProgressRelay(onProgress: onProgress)
            )
        }
        let fileURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("prompt-send-\(UUID().uuidString).json")
        try body.write(to: fileURL, options: .atomic)
        return try await upload(request, fileURL: fileURL, onProgress: onProgress)
    }

    func upload(
        _ request: URLRequest,
        fileURL: URL,
        onProgress: (@Sendable (Int64, Int64) -> Void)?
    ) async throws -> (Data, URLResponse) {
        let size = (try? FileManager.default.attributesOfItem(atPath: fileURL.path)[.size] as? NSNumber)?.int64Value ?? 0
        onProgress?(0, size)
        if !usesBackgroundSession {
            var uploadRequest = request
            uploadRequest.httpBody = nil
            return try await session.upload(
                for: uploadRequest,
                fromFile: fileURL,
                delegate: ProgressRelay(onProgress: onProgress)
            )
        }
        let backgroundTask = await beginBackgroundAssertion()
        do {
            let result = try await performUpload(request, fileURL: fileURL, onProgress: onProgress)
            await endBackgroundAssertion(backgroundTask)
            return result
        } catch {
            await endBackgroundAssertion(backgroundTask)
            throw error
        }
    }

    func waitForBackgroundEvents() async {
        await withCheckedContinuation { continuation in
            lock.lock()
            if inflight.isEmpty {
                lock.unlock()
                continuation.resume()
                return
            }
            backgroundEventsResume = { continuation.resume() }
            lock.unlock()
        }
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didSendBodyData bytesSent: Int64,
        totalBytesSent: Int64,
        totalBytesExpectedToSend: Int64
    ) {
        lock.lock()
        let progress = inflight[task.taskIdentifier]?.onProgress
        lock.unlock()
        progress?(totalBytesSent, totalBytesExpectedToSend)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock()
        inflight[dataTask.taskIdentifier]?.data.append(data)
        lock.unlock()
    }

    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        lock.lock()
        inflight[dataTask.taskIdentifier]?.response = response
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
        inflight[downloadTask.taskIdentifier]?.data = data
        inflight[downloadTask.taskIdentifier]?.response = downloadTask.response
        lock.unlock()
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.lock()
        guard let current = inflight.removeValue(forKey: task.taskIdentifier) else {
            lock.unlock()
            return
        }
        let shouldResumeBackground = inflight.isEmpty
        let resumeBackground = backgroundEventsResume
        if shouldResumeBackground {
            backgroundEventsResume = nil
        }
        lock.unlock()
        try? FileManager.default.removeItem(at: current.fileURL)
        if let error {
            current.continuation.resume(throwing: error)
        } else if let response = current.response ?? task.response {
            current.continuation.resume(returning: (current.data, response))
        } else {
            current.continuation.resume(
                throwing: NSError(
                    domain: NSURLErrorDomain,
                    code: NSURLErrorUnknown,
                    userInfo: [NSLocalizedDescriptionKey: "Missing HTTP response"]
                )
            )
        }
        if shouldResumeBackground {
            resumeBackground?()
        }
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        lock.lock()
        let resume = backgroundEventsResume
        backgroundEventsResume = nil
        lock.unlock()
        resume?()
    }

    private func performUpload(
        _ request: URLRequest,
        fileURL: URL,
        onProgress: (@Sendable (Int64, Int64) -> Void)?
    ) async throws -> (Data, URLResponse) {
        var uploadRequest = request
        uploadRequest.httpBody = nil
        return try await withCheckedThrowingContinuation { continuation in
            let task = session.uploadTask(with: uploadRequest, fromFile: fileURL)
            lock.lock()
            inflight[task.taskIdentifier] = Inflight(
                continuation: continuation,
                onProgress: onProgress,
                fileURL: fileURL
            )
            lock.unlock()
            task.resume()
        }
    }

    private final class ProgressRelay: NSObject, URLSessionTaskDelegate, Sendable {
        let onProgress: (@Sendable (Int64, Int64) -> Void)?

        init(onProgress: (@Sendable (Int64, Int64) -> Void)?) {
            self.onProgress = onProgress
        }

        func urlSession(
            _ session: URLSession,
            task: URLSessionTask,
            didSendBodyData bytesSent: Int64,
            totalBytesSent: Int64,
            totalBytesExpectedToSend: Int64
        ) {
            onProgress?(totalBytesSent, totalBytesExpectedToSend)
        }
    }

    @MainActor
    private func beginBackgroundAssertion() -> Any? {
        #if canImport(UIKit)
        var identifier: UIBackgroundTaskIdentifier = .invalid
        identifier = UIApplication.shared.beginBackgroundTask(withName: "prompt-send") {
            UIApplication.shared.endBackgroundTask(identifier)
        }
        return identifier
        #else
        return nil
        #endif
    }

    @MainActor
    private func endBackgroundAssertion(_ token: Any?) {
        #if canImport(UIKit)
        guard let identifier = token as? UIBackgroundTaskIdentifier, identifier != .invalid else { return }
        UIApplication.shared.endBackgroundTask(identifier)
        #endif
    }
}
