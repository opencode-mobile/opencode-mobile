import Foundation

/// Single-flight restore: start SSE, then REST catch-up for registered scopes.
nonisolated final class DefaultForegroundRestore: ForegroundRestore, @unchecked Sendable {
    private let lock = NSLock()
    private let stream: any ForegroundEventStream
    private let messageRepository: any MessageRepository
    private let homeCatchUpRegistry: ForegroundHomeCatchUpRegistry
    private var openSessionID: String?
    private var openDirectory: String?
    private var homeVisible = false
    private var needsCatchUp = true
    private var missedSessionCatchUp = false
    private var pendingHomeCatchUp = false
    private var restoreTask: Task<Void, Never>?
    private var generation: UInt64 = 0
    private(set) var phase: ForegroundRestorePhase = .idle

    init(
        stream: any ForegroundEventStream,
        messageRepository: any MessageRepository,
        homeCatchUpRegistry: ForegroundHomeCatchUpRegistry = ForegroundHomeCatchUpRegistry()
    ) {
        self.stream = stream
        self.messageRepository = messageRepository
        self.homeCatchUpRegistry = homeCatchUpRegistry
    }

    func setOpenSession(sessionID: String?, directory: String?) {
        lock.lock()
        openSessionID = sessionID
        openDirectory = directory
        let shouldRestore = !(sessionID ?? "").isEmpty && missedSessionCatchUp
        if shouldRestore {
            missedSessionCatchUp = false
            needsCatchUp = true
        }
        lock.unlock()
        if shouldRestore {
            restore()
        }
    }

    func setHomeVisible(_ visible: Bool) {
        lock.lock()
        homeVisible = visible
        lock.unlock()
    }

    func noteBackground() {
        lock.lock()
        needsCatchUp = true
        lock.unlock()
    }

    func noteBackgroundStop() {
        lock.lock()
        generation += 1
        restoreTask?.cancel()
        restoreTask = nil
        needsCatchUp = true
        missedSessionCatchUp = false
        pendingHomeCatchUp = false
        phase = .idle
        lock.unlock()
        stream.stop()
    }

    func restore() {
        lock.lock()
        let catchUp = needsCatchUp || stream.connectionState == .idle
        guard catchUp else {
            lock.unlock()
            DiagnosticLog.info(.sse, "foreground restore start-only catchUp=false")
            stream.start()
            return
        }
        generation += 1
        let gen = generation
        restoreTask?.cancel()
        let sessionID = openSessionID
        let directory = openDirectory
        let refreshHome = homeVisible || pendingHomeCatchUp
        needsCatchUp = false
        missedSessionCatchUp = sessionID == nil || sessionID?.isEmpty == true
        pendingHomeCatchUp = refreshHome
        phase = .restoring
        lock.unlock()

        DiagnosticLog.info(.sse, "foreground restore starting generation=\(gen) catchUp=\(catchUp)")
        stream.start()

        restoreTask = Task(priority: .userInitiated) { [weak self] in
            guard let self else { return }
            var failed = false
            if let sessionID, !sessionID.isEmpty {
                do {
                    _ = try await self.messageRepository.refreshDisplayMessages(
                        sessionID: sessionID,
                        directory: directory
                    )
                    DiagnosticLog.info(
                        .sse,
                        "foreground restore messages session=\(sessionID)"
                    )
                } catch {
                    failed = true
                    DiagnosticLog.warning(
                        .sse,
                        "foreground restore messages failed session=\(sessionID) error=\(error)"
                    )
                }
            }
            if refreshHome {
                await self.homeCatchUpRegistry.catchUp()
                if Task.isCancelled { return }
                self.lock.lock()
                if self.generation == gen {
                    self.pendingHomeCatchUp = false
                }
                self.lock.unlock()
                DiagnosticLog.info(.sse, "foreground restore home catch-up")
            }
            let finishedPhase: ForegroundRestorePhase = failed ? .failed : .live
            let stillCurrent = self.finishIfCurrent(generation: gen, phase: finishedPhase)
            if stillCurrent {
                DiagnosticLog.info(
                    .sse,
                    "foreground restore finished generation=\(gen) phase=\(finishedPhase.rawValue)"
                )
            }
        }
    }

    private func finishIfCurrent(generation: UInt64, phase: ForegroundRestorePhase) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard generation == self.generation else { return false }
        self.phase = phase
        return true
    }
}
