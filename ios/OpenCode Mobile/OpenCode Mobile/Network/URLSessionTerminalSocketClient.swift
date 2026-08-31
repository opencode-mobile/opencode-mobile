import Foundation

final class URLSessionTerminalSocketClient: NSObject, TerminalSocketClient, URLSessionWebSocketDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var generation = 0
    private var session: URLSession?
    private var task: URLSessionWebSocketTask?
    private weak var listener: TerminalSocketListener?

    func open(url: String, listener: TerminalSocketListener) {
        let id: Int
        let previousTask: URLSessionWebSocketTask?
        let previousSession: URLSession?
        lock.lock()
        generation += 1
        id = generation
        previousTask = task
        previousSession = session
        task = nil
        session = nil
        self.listener = listener
        lock.unlock()
        previousTask?.cancel(with: .goingAway, reason: nil)
        previousSession?.invalidateAndCancel()

        guard let wsURL = URL(string: url) else {
            notifyClose(id: id, code: 1006)
            return
        }
        let session = URLSession(configuration: .default, delegate: self, delegateQueue: nil)
        let task = session.webSocketTask(with: wsURL)
        lock.lock()
        if generation == id {
            self.session = session
            self.task = task
            lock.unlock()
        } else {
            lock.unlock()
            session.invalidateAndCancel()
            return
        }
        task.resume()
        receive(id: id, task: task)
    }

    func sendText(_ text: String) {
        lock.lock()
        let current = task
        lock.unlock()
        current?.send(.string(text)) { _ in }
    }

    func close() {
        lock.lock()
        generation += 1
        let current = task
        let currentSession = session
        task = nil
        session = nil
        lock.unlock()
        current?.cancel(with: .normalClosure, reason: nil)
        currentSession?.invalidateAndCancel()
    }

    func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didOpenWithProtocol protocol: String?
    ) {
        lock.lock()
        let matches = webSocketTask === task
        let id = generation
        lock.unlock()
        guard matches else { return }
        notifyOpen(id: id)
    }

    func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didCloseWith closeCode: URLSessionWebSocketTask.CloseCode,
        reason: Data?
    ) {
        lock.lock()
        let matches = webSocketTask === task
        let id = generation
        lock.unlock()
        guard matches else { return }
        notifyClose(id: id, code: Int(closeCode.rawValue))
    }

    private func receive(id: Int, task: URLSessionWebSocketTask) {
        task.receive { [weak self] result in
            guard let self else { return }
            guard id == self.currentGeneration() else { return }
            switch result {
            case let .success(.string(text)):
                self.notifyText(id: id, text: text)
                self.receive(id: id, task: task)
            case let .success(.data(data)):
                self.notifyBinary(id: id, data: data)
                self.receive(id: id, task: task)
            case .success:
                self.receive(id: id, task: task)
            case .failure:
                self.notifyClose(id: id, code: 1006)
            }
        }
    }

    private func currentGeneration() -> Int {
        lock.lock()
        defer { lock.unlock() }
        return generation
    }

    private func notifyOpen(id: Int) {
        Task { @MainActor in
            guard id == self.currentGeneration() else { return }
            self.listener?.didOpen()
        }
    }

    private func notifyText(id: Int, text: String) {
        Task { @MainActor in
            guard id == self.currentGeneration() else { return }
            self.listener?.didReceiveText(text)
        }
    }

    private func notifyBinary(id: Int, data: Data) {
        Task { @MainActor in
            guard id == self.currentGeneration() else { return }
            self.listener?.didReceiveBinary(data)
        }
    }

    private func notifyClose(id: Int, code: Int) {
        Task { @MainActor in
            guard id == self.currentGeneration() else { return }
            self.listener?.didClose(code: code)
        }
    }
}
