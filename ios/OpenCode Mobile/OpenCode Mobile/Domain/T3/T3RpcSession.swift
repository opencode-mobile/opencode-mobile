import Foundation

/// One websocket RPC session against a T3 environment.
///
/// Owns pending request continuations, streaming subscriptions, the connect
/// and per-request timeouts, and a client keepalive ping. Once the socket
/// closes the session is dead: every pending request fails, every stream
/// finishes with an error, and `isClosed` tells `T3EnvironmentClient` to open
/// a fresh session instead of reusing this one.
nonisolated final class T3RpcSession: @unchecked Sendable {
    static let defaultConnectTimeout: Duration = .seconds(15)
    static let defaultRequestTimeout: Duration = .seconds(30)
    static let defaultKeepaliveInterval: Duration = .seconds(15)

    private let lock = NSLock()
    private let connector: any T3SocketConnector
    private let connectTimeout: Duration
    private let requestTimeout: Duration
    private let keepaliveInterval: Duration
    private let onStateChange: (@Sendable (T3SocketState) -> Void)?

    private var connection: (any T3SocketConnection)?
    private var _state: T3SocketState = .disconnected
    private var everConnected = false
    private var closedForGood = false
    private var pending: [String: CheckedContinuation<Any?, Error>] = [:]
    private var pendingTimeouts: [String: Task<Void, Never>] = [:]
    private var streams: [String: AsyncThrowingStream<Any, Error>.Continuation] = [:]
    private var openWaiters: [CheckedContinuation<Void, Error>] = []
    private var connectTimeoutTask: Task<Void, Never>?
    private var keepaliveTask: Task<Void, Never>?

    init(
        connector: any T3SocketConnector = URLSessionT3SocketConnector(),
        connectTimeout: Duration = T3RpcSession.defaultConnectTimeout,
        requestTimeout: Duration = T3RpcSession.defaultRequestTimeout,
        keepaliveInterval: Duration = T3RpcSession.defaultKeepaliveInterval,
        onStateChange: (@Sendable (T3SocketState) -> Void)? = nil
    ) {
        self.connector = connector
        self.connectTimeout = connectTimeout
        self.requestTimeout = requestTimeout
        self.keepaliveInterval = keepaliveInterval
        self.onStateChange = onStateChange
    }

    var state: T3SocketState {
        lock.lock()
        defer { lock.unlock() }
        return _state
    }

    /// True once the socket is gone for any reason; the session must not be reused.
    var isClosed: Bool {
        lock.lock()
        defer { lock.unlock() }
        return closedForGood || (_state == .disconnected && everConnected)
    }

    var isConnected: Bool { state == .connected }

    func connect(url: URL) async throws {
        lock.lock()
        guard connection == nil, !closedForGood else {
            lock.unlock()
            throw T3ClientError.rpc("T3 websocket session cannot be reused.")
        }
        _state = .connecting
        lock.unlock()
        onStateChange?(.connecting)

        let events = T3SocketEvents(
            onOpen: { [weak self] in self?.handleOpen() },
            onText: { [weak self] text in self?.handle(text: text) },
            onClose: { [weak self] error in self?.close(reason: error) }
        )
        let opened = connector.open(url: url, events: events)
        let timeout = connectTimeout
        lock.lock()
        connection = opened
        connectTimeoutTask = Task { [weak self] in
            try? await Task.sleep(for: timeout)
            guard !Task.isCancelled else { return }
            self?.close(reason: T3ClientError.transport("Timed out connecting to the T3 websocket."))
        }
        lock.unlock()

        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                lock.lock()
                switch _state {
                case .connected:
                    lock.unlock()
                    continuation.resume()
                case .connecting:
                    openWaiters.append(continuation)
                    lock.unlock()
                case .disconnected:
                    lock.unlock()
                    continuation.resume(throwing: T3ClientError.rpc("T3 websocket closed."))
                }
            }
        } onCancel: {
            close(reason: CancellationError())
        }
    }

    func request(tag: String, payload: [String: Any]) async throws -> Any? {
        let id = UUID().uuidString
        let data = try T3RpcCodec.encodeRequest(id: id, tag: tag, payload: payload)
        let timeout = requestTimeout
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Any?, Error>) in
                lock.lock()
                guard _state == .connected, let current = connection else {
                    lock.unlock()
                    continuation.resume(throwing: T3ClientError.rpc("T3 websocket is closed."))
                    return
                }
                pending[id] = continuation
                pendingTimeouts[id] = Task { [weak self] in
                    try? await Task.sleep(for: timeout)
                    guard !Task.isCancelled else { return }
                    self?.sendInterrupt(requestID: id)
                    self?.finish(id: id, result: .failure(T3ClientError.rpc("T3 request \(tag) timed out.")))
                }
                lock.unlock()
                current.send(String(decoding: data, as: UTF8.self)) { [weak self] error in
                    if let error {
                        self?.finish(id: id, result: .failure(T3ClientError.transport(error.localizedDescription)))
                    }
                }
            }
        } onCancel: {
            sendInterrupt(requestID: id)
            finish(id: id, result: .failure(CancellationError()))
        }
    }

    /// Streaming request. The stream throws when the socket dies or the server
    /// reports a failure, and finishes cleanly on a server `Exit` success.
    /// Terminating the stream sends an `Interrupt` so the server stops producing.
    func subscribe(tag: String, payload: [String: Any]) -> AsyncThrowingStream<Any, Error> {
        let id = UUID().uuidString
        let (stream, continuation) = AsyncThrowingStream.makeStream(of: Any.self)
        let data: Data
        do {
            data = try T3RpcCodec.encodeRequest(id: id, tag: tag, payload: payload)
        } catch {
            continuation.finish(throwing: error)
            return stream
        }
        lock.lock()
        guard _state == .connected, let current = connection else {
            lock.unlock()
            continuation.finish(throwing: T3ClientError.rpc("T3 websocket is closed."))
            return stream
        }
        streams[id] = continuation
        lock.unlock()
        continuation.onTermination = { [weak self] termination in
            guard let self else { return }
            let wasTracked = self.locked { self.streams.removeValue(forKey: id) != nil }
            if case .cancelled = termination, wasTracked {
                self.sendInterrupt(requestID: id)
            }
        }
        current.send(String(decoding: data, as: UTF8.self)) { [weak self] error in
            guard let error, let self else { return }
            let continuation = self.locked { self.streams.removeValue(forKey: id) }
            continuation?.finish(throwing: T3ClientError.transport(error.localizedDescription))
        }
        return stream
    }

    func close() async {
        close(reason: nil)
    }

    private func close(reason: Error?) {
        lock.lock()
        guard !closedForGood else {
            lock.unlock()
            return
        }
        closedForGood = true
        let wasDisconnected = _state == .disconnected
        _state = .disconnected
        let current = connection
        connection = nil
        let waiters = openWaiters
        openWaiters = []
        let pendingNow = Array(pending.values)
        pending = [:]
        let timeouts = Array(pendingTimeouts.values)
        pendingTimeouts = [:]
        let streamsNow = Array(streams.values)
        streams = [:]
        let connectTimeout = connectTimeoutTask
        connectTimeoutTask = nil
        let keepalive = keepaliveTask
        keepaliveTask = nil
        lock.unlock()

        connectTimeout?.cancel()
        keepalive?.cancel()
        timeouts.forEach { $0.cancel() }
        let error = Self.closeError(reason)
        waiters.forEach { $0.resume(throwing: error) }
        pendingNow.forEach { $0.resume(throwing: error) }
        streamsNow.forEach { $0.finish(throwing: error) }
        current?.close()
        if !wasDisconnected {
            onStateChange?(.disconnected)
        }
    }

    private static func closeError(_ reason: Error?) -> Error {
        switch reason {
        case nil:
            T3ClientError.rpc("T3 websocket closed.")
        case let t3 as T3ClientError:
            t3
        case is CancellationError:
            CancellationError()
        case let other?:
            T3ClientError.transport(other.localizedDescription)
        }
    }

    private func handleOpen() {
        lock.lock()
        guard _state == .connecting, !closedForGood else {
            lock.unlock()
            return
        }
        _state = .connected
        everConnected = true
        let waiters = openWaiters
        openWaiters = []
        let connectTimeout = connectTimeoutTask
        connectTimeoutTask = nil
        keepaliveTask?.cancel()
        let interval = keepaliveInterval
        keepaliveTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: interval)
                guard !Task.isCancelled, let self else { return }
                self.sendRaw(try? T3RpcCodec.encodePing())
            }
        }
        lock.unlock()
        connectTimeout?.cancel()
        onStateChange?(.connected)
        waiters.forEach { $0.resume() }
    }

    private func handle(text: String) {
        guard let frame = try? T3RpcCodec.parseFrame(Data(text.utf8)) else { return }
        switch frame {
        case .ping:
            sendRaw(try? T3RpcCodec.encodePong())
        case .pong:
            break
        case let .chunk(requestID, values):
            let continuation = locked { streams[requestID] }
            // Chunks for a request we no longer track (interrupted or finished)
            // are dropped without an Ack; the server stops after the Interrupt.
            guard let continuation else { return }
            values.forEach { continuation.yield($0) }
            sendRaw(try? T3RpcCodec.encodeAck(requestID: requestID))
        case let .exitSuccess(requestID, value):
            if finish(id: requestID, result: .success(value)) { return }
            let continuation = locked { streams.removeValue(forKey: requestID) }
            continuation?.finish()
        case let .exitFailure(requestID, message):
            if finish(id: requestID, result: .failure(T3ClientError.rpc(message))) { return }
            let continuation = locked { streams.removeValue(forKey: requestID) }
            continuation?.finish(throwing: T3ClientError.rpc(message))
        case .request, .ack, .unknown:
            break
        }
    }

    private func sendInterrupt(requestID: String) {
        sendRaw(try? T3RpcCodec.encodeInterrupt(requestID: requestID))
    }

    private func sendRaw(_ data: Data?) {
        guard let data else { return }
        let current = locked { _state == .connected ? connection : nil }
        current?.send(String(decoding: data, as: UTF8.self)) { _ in }
    }

    @discardableResult
    private func finish(id: String, result: Result<Any?, Error>) -> Bool {
        let (continuation, timeout) = locked {
            (pending.removeValue(forKey: id), pendingTimeouts.removeValue(forKey: id))
        }
        timeout?.cancel()
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
