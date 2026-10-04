import Foundation

nonisolated enum T3SocketState: Equatable, Sendable {
    case connecting
    case connected
    case disconnected
}

/// Callbacks a socket connection reports back to `T3RpcSession`.
nonisolated struct T3SocketEvents: Sendable {
    let onOpen: @Sendable () -> Void
    let onText: @Sendable (String) -> Void
    let onClose: @Sendable (Error?) -> Void
}

/// One open websocket. `send` fails once the socket is closed.
nonisolated protocol T3SocketConnection: AnyObject, Sendable {
    func send(_ text: String, completion: @escaping @Sendable (Error?) -> Void)
    func close()
}

/// Opens websocket connections. The default wraps `URLSessionWebSocketTask`;
/// tests inject a fake so reconnect, timeout, and frame handling run offline.
nonisolated protocol T3SocketConnector: Sendable {
    func open(url: URL, events: T3SocketEvents) -> any T3SocketConnection
}

nonisolated struct URLSessionT3SocketConnector: T3SocketConnector {
    func open(url: URL, events: T3SocketEvents) -> any T3SocketConnection {
        let connection = URLSessionT3SocketConnection(events: events)
        connection.start(url: url)
        return connection
    }
}

nonisolated final class URLSessionT3SocketConnection: NSObject, T3SocketConnection, URLSessionWebSocketDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private let events: T3SocketEvents
    private var urlSession: URLSession?
    private var task: URLSessionWebSocketTask?
    private var closed = false

    init(events: T3SocketEvents) {
        self.events = events
    }

    func start(url: URL) {
        let session = URLSession(configuration: .default, delegate: self, delegateQueue: nil)
        let socket = session.webSocketTask(with: url)
        lock.lock()
        urlSession = session
        task = socket
        lock.unlock()
        socket.resume()
        receive(socket)
    }

    func send(_ text: String, completion: @escaping @Sendable (Error?) -> Void) {
        lock.lock()
        let current = closed ? nil : task
        lock.unlock()
        guard let current else {
            completion(T3ClientError.rpc("T3 websocket is closed."))
            return
        }
        current.send(.string(text), completionHandler: completion)
    }

    func close() {
        guard let snapshot = finishOnce() else { return }
        snapshot.task?.cancel(with: .normalClosure, reason: nil)
        snapshot.session?.invalidateAndCancel()
    }

    func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didOpenWithProtocol protocol: String?
    ) {
        lock.lock()
        let matches = webSocketTask === task && !closed
        lock.unlock()
        guard matches else { return }
        events.onOpen()
    }

    func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didCloseWith closeCode: URLSessionWebSocketTask.CloseCode,
        reason: Data?
    ) {
        closeFromTransport(error: nil)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        closeFromTransport(error: error)
    }

    private func receive(_ socket: URLSessionWebSocketTask) {
        socket.receive { [weak self] result in
            guard let self else { return }
            switch result {
            case let .success(message):
                switch message {
                case let .string(text):
                    self.events.onText(text)
                case let .data(payload):
                    self.events.onText(String(decoding: payload, as: UTF8.self))
                @unknown default:
                    break
                }
                self.receive(socket)
            case let .failure(error):
                self.closeFromTransport(error: error)
            }
        }
    }

    private func closeFromTransport(error: Error?) {
        guard let snapshot = finishOnce() else { return }
        snapshot.task?.cancel(with: .normalClosure, reason: nil)
        snapshot.session?.invalidateAndCancel()
        events.onClose(error)
    }

    private func finishOnce() -> (task: URLSessionWebSocketTask?, session: URLSession?)? {
        lock.lock()
        defer { lock.unlock() }
        guard !closed else { return nil }
        closed = true
        let snapshot = (task, urlSession)
        task = nil
        urlSession = nil
        return snapshot
    }
}
