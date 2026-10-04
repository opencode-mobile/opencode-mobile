import Foundation

final class T3RpcSession: NSObject, URLSessionWebSocketDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var urlSession: URLSession?
    private var task: URLSessionWebSocketTask?
    private var pending: [String: CheckedContinuation<Any?, Error>] = [:]
    private var streams: [String: AsyncStream<Any>.Continuation] = [:]
    private var opened = false
    private var openWaiters: [CheckedContinuation<Void, Error>] = []

    func connect(url: URL) async throws {
        await close()
        let session = URLSession(configuration: .default, delegate: self, delegateQueue: nil)
        let socket = session.webSocketTask(with: url)
        locked {
            urlSession = session
            task = socket
            opened = false
        }
        socket.resume()
        receive(socket)
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let alreadyOpen = locked { () -> Bool in
                if opened {
                    return true
                }
                openWaiters.append(continuation)
                return false
            }
            if alreadyOpen {
                continuation.resume()
            }
        }
    }

    func request(tag: String, payload: [String: Any]) async throws -> Any? {
        let id = UUID().uuidString
        let data = try T3RpcCodec.encodeRequest(id: id, tag: tag, payload: payload)
        return try await withCheckedThrowingContinuation { continuation in
            let current = locked { () -> URLSessionWebSocketTask? in
                pending[id] = continuation
                return task
            }
            guard let current else {
                finish(id: id, result: .failure(T3ClientError.rpc("T3 websocket is closed.")))
                return
            }
            current.send(.string(String(decoding: data, as: UTF8.self))) { [weak self] error in
                if let error {
                    self?.finish(id: id, result: .failure(T3ClientError.transport(error.localizedDescription)))
                }
            }
        }
    }

    func subscribe(tag: String, payload: [String: Any]) -> AsyncStream<Any> {
        let id = UUID().uuidString
        let (stream, continuation) = AsyncStream.makeStream(of: Any.self)
        locked { streams[id] = continuation }
        continuation.onTermination = { [weak self] _ in
            self?.locked { _ = self?.streams.removeValue(forKey: id) }
        }
        Task {
            do {
                let data = try T3RpcCodec.encodeRequest(id: id, tag: tag, payload: payload)
                let current = self.locked { self.task }
                current?.send(.string(String(decoding: data, as: UTF8.self))) { _ in }
            } catch {
                continuation.finish()
            }
        }
        return stream
    }

    func close() async {
        let snapshot = locked { () -> (
            URLSessionWebSocketTask?,
            URLSession?,
            [CheckedContinuation<Void, Error>],
            [CheckedContinuation<Any?, Error>],
            [AsyncStream<Any>.Continuation]
        ) in
            let current = task
            let currentSession = urlSession
            task = nil
            urlSession = nil
            opened = false
            let waiters = openWaiters
            openWaiters = []
            let pending = Array(pending.values)
            self.pending = [:]
            let streams = Array(streams.values)
            self.streams = [:]
            return (current, currentSession, waiters, pending, streams)
        }
        snapshot.2.forEach { $0.resume(throwing: T3ClientError.rpc("T3 websocket closed.")) }
        snapshot.3.forEach { $0.resume(throwing: T3ClientError.rpc("T3 websocket closed.")) }
        snapshot.4.forEach { $0.finish() }
        snapshot.0?.cancel(with: .normalClosure, reason: nil)
        snapshot.1?.invalidateAndCancel()
    }

    func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didOpenWithProtocol protocol: String?
    ) {
        lock.lock()
        let matches = webSocketTask === task
        opened = matches
        let waiters = openWaiters
        openWaiters = []
        lock.unlock()
        guard matches else { return }
        waiters.forEach { $0.resume() }
    }

    func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didCloseWith closeCode: URLSessionWebSocketTask.CloseCode,
        reason: Data?
    ) {
        Task { await close() }
    }

    private func receive(_ socket: URLSessionWebSocketTask) {
        socket.receive { [weak self] result in
            guard let self else { return }
            switch result {
            case let .success(message):
                self.handle(message)
                self.receive(socket)
            case .failure:
                Task { await self.close() }
            }
        }
    }

    private func handle(_ message: URLSessionWebSocketTask.Message) {
        let data: Data
        switch message {
        case let .string(text):
            data = Data(text.utf8)
        case let .data(payload):
            data = payload
        @unknown default:
            return
        }
        guard let frame = try? T3RpcCodec.parseFrame(data) else { return }
        switch frame {
        case .ping:
            if let pong = try? T3RpcCodec.encodePong() {
                lock.lock()
                let current = task
                lock.unlock()
                current?.send(.string(String(decoding: pong, as: UTF8.self))) { _ in }
            }
        case let .chunk(requestID, values):
            lock.lock()
            let continuation = streams[requestID]
            lock.unlock()
            values.forEach { continuation?.yield($0) }
            if let ack = try? T3RpcCodec.encodeAck(requestID: requestID) {
                lock.lock()
                let current = task
                lock.unlock()
                current?.send(.string(String(decoding: ack, as: UTF8.self))) { _ in }
            }
        case let .exitSuccess(requestID, value):
            if finish(id: requestID, result: .success(value)) { return }
            lock.lock()
            let continuation = streams.removeValue(forKey: requestID)
            lock.unlock()
            continuation?.finish()
        case let .exitFailure(requestID, message):
            if finish(id: requestID, result: .failure(T3ClientError.rpc(message))) { return }
            lock.lock()
            let continuation = streams.removeValue(forKey: requestID)
            lock.unlock()
            continuation?.finish()
        default:
            break
        }
    }

    @discardableResult
    private func finish(id: String, result: Result<Any?, Error>) -> Bool {
        let continuation = locked { pending.removeValue(forKey: id) }
        guard let continuation else { return false }
        switch result {
        case let .success(value):
            continuation.resume(returning: value)
        case let .failure(error):
            continuation.resume(throwing: error)
        }
        return true
    }

    @discardableResult
    private func locked<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }
}
