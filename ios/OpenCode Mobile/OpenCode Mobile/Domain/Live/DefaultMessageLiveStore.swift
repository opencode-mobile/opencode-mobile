import Foundation

@MainActor
final class DefaultMessageLiveStore: MessageLiveStore {
    private struct ObservationKey: Hashable {
        let sessionID: String
        let directory: String
    }

    private let messageRepository: any MessageRepository
    private let eventStream: any ForegroundEventStream
    private var cache: [ObservationKey: SharedLiveObservation<[Message]>] = [:]

    init(
        messageRepository: any MessageRepository,
        eventStream: any ForegroundEventStream
    ) {
        self.messageRepository = messageRepository
        self.eventStream = eventStream
    }

    func observeMessages(sessionID: String, directory: String) -> AsyncStream<[Message]> {
        let key = ObservationKey(sessionID: sessionID, directory: directory)
        if cache[key] == nil {
            cache[key] = SharedLiveObservation { yield in
                Task {
                    await self.runMessageLoop(sessionID: sessionID, directory: directory, yield: yield)
                }
            }
        }
        return cache[key]!.stream()
    }

    private func runMessageLoop(
        sessionID: String,
        directory: String,
        yield: @escaping ([Message]) -> Void
    ) async {
        func publish(_ messages: [Message]) {
            yield(messages)
        }

        let initialMessages = await loadMessages(sessionID: sessionID, directory: directory)
        let initialFingerprint = initialMessages.map(messageFingerprint)
        if let initialMessages {
            publish(initialMessages)
        }

        async let eventLoop: Void = runEventRefreshLoop(sessionID: sessionID, directory: directory, publish: publish)
        async let pollLoop: Void = runTimelinePollLoop(
            sessionID: sessionID,
            directory: directory,
            initialFingerprint: initialFingerprint,
            publish: publish
        )
        _ = await (eventLoop, pollLoop)
    }

    private func messageFingerprint(_ messages: [Message]) -> String {
        messages.map { "\($0.id):\($0.text.count):\($0.text.hashValue)" }.joined(separator: "|")
    }

    private func runEventRefreshLoop(
        sessionID: String,
        directory: String,
        publish: @escaping ([Message]) -> Void
    ) async {
        var refreshTask: Task<Void, Never>?
        defer { refreshTask?.cancel() }
        for await event in eventStream.events() {
            let shouldRefresh =
                event.type == "stream.reconnected" ||
                (event.sessionID == sessionID && event.triggersMessageRefresh)
            guard shouldRefresh else { continue }
            refreshTask?.cancel()
            refreshTask = Task {
                try? await Task.sleep(for: .milliseconds(LiveStoreRefreshPolicy.messageDebounceMs))
                guard !Task.isCancelled else { return }
                if let messages = await loadMessages(sessionID: sessionID, directory: directory) {
                    publish(messages)
                }
            }
        }
    }

    private func runTimelinePollLoop(
        sessionID: String,
        directory: String,
        initialFingerprint: String?,
        publish: @escaping ([Message]) -> Void
    ) async {
        var lastFingerprint = initialFingerprint
        while !Task.isCancelled {
            try? await Task.sleep(for: .milliseconds(LiveStoreRefreshPolicy.workingPollIntervalMs))
            guard !Task.isCancelled else { return }
            guard let messages = await loadMessages(sessionID: sessionID, directory: directory) else { continue }
            let fingerprint = messageFingerprint(messages)
            guard fingerprint != lastFingerprint else { continue }
            lastFingerprint = fingerprint
            publish(messages)
        }
    }

    func clearCache() {
        cache.removeAll()
    }

    private func loadMessages(sessionID: String, directory: String) async -> [Message]? {
        do {
            return try await messageRepository.listMessages(sessionID: sessionID, directory: directory)
        } catch {
            return nil
        }
    }
}
