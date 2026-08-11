import Foundation

@MainActor
final class DefaultPermissionLiveStore: PermissionLiveStore {
    private let sessionRepository: any SessionRepository
    private let eventStream: any ForegroundEventStream
    private var cache: [String: SharedLiveObservation<[PermissionRequest]>] = [:]

    init(sessionRepository: any SessionRepository, eventStream: any ForegroundEventStream) {
        self.sessionRepository = sessionRepository
        self.eventStream = eventStream
    }

    func observePermissions(directory: String) -> AsyncStream<[PermissionRequest]> {
        if cache[directory] == nil {
            let sessionRepository = sessionRepository
            let eventStream = eventStream
            cache[directory] = SharedLiveObservation { yield in
                // Detach so dense message deltas do not resume this loop on MainActor.
                Task.detached {
                    await Self.runLoop(
                        directory: directory,
                        sessionRepository: sessionRepository,
                        eventStream: eventStream,
                        yield: yield
                    )
                }
            }
        }
        return cache[directory]!.stream()
    }

    private nonisolated static func runLoop(
        directory: String,
        sessionRepository: any SessionRepository,
        eventStream: any ForegroundEventStream,
        yield: @escaping @Sendable ([PermissionRequest]) -> Void
    ) async {
        await refresh(directory: directory, trigger: "initial", sessionRepository: sessionRepository, yield: yield)
        for await event in eventStream.events() {
            guard event.type == "permission.asked"
                || event.type == "permission.replied"
                || event.type == "stream.reconnected"
                || event.type == "server.connected"
            else { continue }
            await refresh(
                directory: directory,
                trigger: event.type,
                sessionRepository: sessionRepository,
                yield: yield
            )
        }
    }

    private nonisolated static func refresh(
        directory: String,
        trigger: String,
        sessionRepository: any SessionRepository,
        yield: @escaping @Sendable ([PermissionRequest]) -> Void
    ) async {
        do {
            let requests = try await sessionRepository.listPendingPermissions(directory: directory)
            DiagnosticLog.info(.permission, "permission refresh trigger=\(trigger) count=\(requests.count)")
            yield(requests)
        } catch {
            DiagnosticLog.warning(.permission, "permission refresh failed trigger=\(trigger)")
        }
    }

    func clearCache() { cache.removeAll() }
}
