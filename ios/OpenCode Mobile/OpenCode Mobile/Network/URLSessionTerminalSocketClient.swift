import Foundation

final class URLSessionTerminalSocketClient: NSObject, TerminalSocketClient, URLSessionWebSocketDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var generation = 0
    private var session: URLSession?
    private var task: URLSessionWebSocketTask?
    private weak var listener: TerminalSocketListener?

    func open(url: String, listener: TerminalSocketListener) {
        let id: Int
        lock.lock()
        generation += 1
        id = generation
        task?.cancel(with: .goingAway, reason: nil)
        self.listener = listener
        lock.unlock()

        guard let wsURL = URL(string: url) else {
            notifyClose(id: id, code: 1006)
            return
        }
        let session = URLSession(configuration: .default, delegate: self, delegateQueue: nil)
        let task = session.webSocketTask(with: wsURL)
        lock.lock()
        self.session = session
        self.task = task
        lock.unlock()
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
        task = nil
        session = nil
        lock.unlock()
        current?.cancel(with: .normalClosure, reason: nil)
    }

    func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didOpenWithProtocol protocol: String?
    ) {
        let id = currentGeneration()
        notifyOpen(id: id)
    }

    func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didCloseWith closeCode: URLSessionWebSocketTask.CloseCode,
        reason: Data?
    ) {
        let id = currentGeneration()
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
