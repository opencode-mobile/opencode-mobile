import Foundation
import Observation

enum TerminalViewState: Equatable {
    case connecting
    case live(TerminalSession)
    case reconnecting(TerminalSession)
    case connectFailed
    case exited
}

@MainActor
@Observable
final class TerminalViewModel: TerminalSocketListener {
    private(set) var state: TerminalViewState = .connecting

    private let worktree: String
    private let repository: any TerminalRepository
    private let activeConnectionManager: ActiveConnectionManager
    private let socketClient: any TerminalSocketClient
    private var session: TerminalSession?
    private var cursor: String = "0"
    private var connectTask: Task<Void, Never>?
    private var reconnectTask: Task<Void, Never>?
    private var resizeTask: Task<Void, Never>?
    private var leaving = false
    private var socketOpenedOnce = false
    private var reconnectAttempt = 0
    private var pendingOutput = ""
    private var outputHandler: ((String) -> Void)?

    init(
        worktree: String,
        repository: any TerminalRepository,
        activeConnectionManager: ActiveConnectionManager,
        socketClient: any TerminalSocketClient
    ) {
        self.worktree = worktree
        self.repository = repository
        self.activeConnectionManager = activeConnectionManager
        self.socketClient = socketClient
    }

    func appear() async {
        leaving = false
        let reset = {
            switch self.state {
            case .live, .reconnecting:
                return false
            default:
                return true
            }
        }()
        await connect(resetToConnecting: reset)
    }

    func retry() async {
        leaving = false
        if case .exited = state {
            cursor = "0"
            session = nil
            socketOpenedOnce = false
        }
        await connect(resetToConnecting: true)
    }

    func disappear() {
        leaving = true
        connectTask?.cancel()
        reconnectTask?.cancel()
        resizeTask?.cancel()
        socketClient.close()
    }

    func sendInput(_ text: String) {
        guard !text.isEmpty else { return }
        socketClient.sendText(text)
    }

    func resize(cols: Int, rows: Int) {
        guard cols > 0, rows > 0 else { return }
        resizeTask?.cancel()
        resizeTask = Task {
            try? await Task.sleep(nanoseconds: 100_000_000)
            guard !Task.isCancelled, let session else { return }
            _ = try? await repository.updateSize(
                id: session.id,
                directory: worktree,
                cols: cols,
                rows: rows
            )
        }
    }

    func attachOutput(_ handler: @escaping (String) -> Void) {
        outputHandler = handler
        if !pendingOutput.isEmpty {
            handler(pendingOutput)
            pendingOutput = ""
        }
    }

    func detachOutput() {
        outputHandler = nil
    }

    private func connect(resetToConnecting: Bool) async {
        connectTask?.cancel()
        reconnectTask?.cancel()
        socketClient.close()
        if resetToConnecting {
            state = .connecting
        }
        let task = Task {
            do {
                let ensured = try await repository.ensureRunning(directory: worktree)
                session = ensured
                guard !Task.isCancelled, !leaving else { return }
                if ensured.status != .running {
                    state = .exited
                    return
                }
                openSocket(ensured)
            } catch is CancellationError {
                return
            } catch let error as URLError where error.code == .cancelled {
                return
            } catch {
                guard !Task.isCancelled, !leaving else { return }
                state = .connectFailed
            }
        }
        connectTask = task
        await task.value
    }

    private func openSocket(_ session: TerminalSession) {
        guard let connection = activeConnectionManager.connection else {
            state = .connectFailed
            return
        }
        let request = repository.connectRequest(
            id: session.id,
            directory: worktree,
            serverProtocol: connection.serverProtocol ?? .v1,
            baseURL: connection.baseURL.absoluteString,
            cursor: cursor,
            ticket: nil,
            username: connection.username,
            password: connection.password ?? ""
        )
        socketClient.open(url: request.url, listener: self)
    }

    func didOpen() {
        guard !leaving, let session else { return }
        socketOpenedOnce = true
        reconnectAttempt = 0
        state = .live(session)
    }

    func didReceiveText(_ text: String) {
        if case let .output(output) = TerminalWsFrameParser.parseText(text) {
            deliverOutput(output)
        }
    }

    func didReceiveBinary(_ data: Data) {
        if case let .cursor(value) = TerminalWsFrameParser.parseBinary(data) {
            cursor = value
        }
    }

    func didClose(code: Int) {
        guard !leaving, code != 1000 else { return }
        reconnectTask?.cancel()
        reconnectTask = Task { await handleDrop() }
    }

    private func handleDrop() async {
        guard let session else { return }
        do {
            let latest = try await repository.get(id: session.id, directory: worktree)
            guard !Task.isCancelled, !leaving else { return }
            self.session = latest
            if latest.status == .exited {
                state = .exited
                return
            }
            state = .reconnecting(latest)
            try await Task.sleep(nanoseconds: reconnectDelayNs())
            guard !Task.isCancelled, !leaving else { return }
            openSocket(latest)
        } catch is CancellationError {
            return
        } catch let error as TerminalError {
            guard !Task.isCancelled, !leaving else { return }
            if case let .http(statusCode, _) = error, statusCode == 404 {
                state = .exited
            } else {
                await retryAfterGetFailure(session)
            }
        } catch {
            guard !Task.isCancelled, !leaving else { return }
            await retryAfterGetFailure(session)
        }
    }

    private func retryAfterGetFailure(_ current: TerminalSession) async {
        if !socketOpenedOnce {
            state = .connectFailed
            return
        }
        state = .reconnecting(current)
        try? await Task.sleep(nanoseconds: reconnectDelayNs())
        guard !Task.isCancelled, !leaving else { return }
        openSocket(current)
    }

    private func reconnectDelayNs() -> UInt64 {
        let delayMs = min(250 * (1 << min(reconnectAttempt, 4)), 3000)
        reconnectAttempt += 1
        return UInt64(delayMs) * 1_000_000
    }

    private func deliverOutput(_ text: String) {
        if let outputHandler {
            outputHandler(text)
        } else {
            pendingOutput += text
        }
    }
}
