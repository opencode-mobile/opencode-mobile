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
            cache[directory] = SharedLiveObservation { yield in
                Task { await self.runLoop(directory: directory, yield: yield) }
            }
        }
        return cache[directory]!.stream()
    }

    private func runLoop(directory: String, yield: @escaping ([PermissionRequest]) -> Void) async {
        await refresh(directory: directory, trigger: "initial", yield: yield)
        for await event in eventStream.events() {
            guard event.type == "permission.asked"
                || event.type == "permission.replied"
                || event.type == "stream.reconnected"
                || event.type == "server.connected"
            else { continue }
            await refresh(directory: directory, trigger: event.type, yield: yield)
        }
    }

    private func refresh(
        directory: String,
        trigger: String,
        yield: @escaping ([PermissionRequest]) -> Void
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
