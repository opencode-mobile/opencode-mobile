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
        if let requests = try? await sessionRepository.listPendingPermissions(directory: directory) { yield(requests) }
        for await event in eventStream.events() {
            guard event.type == "permission.asked"
                || event.type == "permission.replied"
                || event.type == "stream.reconnected"
                || event.type == "server.connected"
            else { continue }
            if let requests = try? await sessionRepository.listPendingPermissions(directory: directory) { yield(requests) }
        }
    }

    func clearCache() { cache.removeAll() }
}
