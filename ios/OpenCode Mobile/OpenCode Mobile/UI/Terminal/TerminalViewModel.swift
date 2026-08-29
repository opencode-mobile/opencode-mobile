import Foundation
import Observation

enum TerminalViewState: Equatable {
    case connecting
    case live
    case connectFailed
    case exited
}

@MainActor
@Observable
final class TerminalViewModel {
    private(set) var state: TerminalViewState = .connecting

    private let worktree: String
    private let repository: any TerminalRepository
    private var connectTask: Task<Void, Never>?

    init(worktree: String, repository: any TerminalRepository) {
        self.worktree = worktree
        self.repository = repository
    }

    func appear() async {
        await connect()
    }

    func retry() async {
        await connect()
    }

    func disappear() {
        connectTask?.cancel()
    }

    private func connect() async {
        connectTask?.cancel()
        state = .connecting
        let task = Task {
            do {
                let session = try await repository.ensureRunning(directory: worktree)
                guard !Task.isCancelled else { return }
                state = session.status == .running ? .live : .exited
            } catch is CancellationError {
                return
            } catch let error as URLError where error.code == .cancelled {
                return
            } catch {
                guard !Task.isCancelled else { return }
                state = .connectFailed
            }
        }
        connectTask = task
        await task.value
    }
}
