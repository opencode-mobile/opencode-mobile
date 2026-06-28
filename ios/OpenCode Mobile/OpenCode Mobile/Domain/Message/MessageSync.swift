import Foundation

typealias MessageListFetcher = @Sendable (String, String?) async throws -> [Message]

@MainActor
final class MessageSync {
    private struct ObservationKey: Hashable {
        let sessionID: String
        let directory: String
    }

    private let listMessages: MessageListFetcher
    private let store: InMemoryMessageStore
    private let eventStream: any ForegroundEventStream

    private var observationCache: [ObservationKey: SharedLiveObservation<MessageListUpdate>] = [:]
    private var lastFingerprint: [String: String] = [:]

    init(
        listMessages: @escaping MessageListFetcher,
        store: InMemoryMessageStore,
        eventStream: any ForegroundEventStream
    ) {
        self.listMessages = listMessages
        self.store = store
        self.eventStream = eventStream
    }

    func observeMessages(sessionID: String, directory: String?) -> AsyncStream<MessageListUpdate> {
        let key = ObservationKey(sessionID: sessionID, directory: directory ?? "")
        if observationCache[key] == nil {
            observationCache[key] = SharedLiveObservation { yield in
                Task {
                    await self.runMessageLoop(sessionID: sessionID, directory: directory, yield: yield)
                }
            }
        }
        return observationCache[key]!.stream()
    }

    func refreshMessages(sessionID: String, directory: String?) async throws -> [Message] {
        try await fullReloadOrThrow(sessionID: sessionID, directory: directory, reason: "refresh")
    }

    func clearCache() {
        observationCache.removeAll()
        lastFingerprint.removeAll()
        store.clear()
    }

    private func runMessageLoop(
        sessionID: String,
        directory: String?,
        yield: @escaping (MessageListUpdate) -> Void
    ) async {
        func publishMessages(_ messages: [Message]) {
            yield(.messages(messages))
            lastFingerprint[sessionID] = messageFingerprint(messages)
        }

        switch await fullReloadResult(sessionID: sessionID, directory: directory, reason: "initial-subscribe") {
        case .success(let messages):
            SessionPerformanceProbe.emit("history_request_finished")
            publishMessages(messages)
        case .failure(let error):
            SessionPerformanceProbe.emit("history_request_finished")
            yield(.loadFailed(error))
        }

        async let eventLoop: Void = runEventRefreshLoop(
            sessionID: sessionID,
            directory: directory,
            publishMessages: publishMessages
        )
        async let pollLoop: Void = runTimelinePollLoop(
            sessionID: sessionID,
            directory: directory,
            publishMessages: publishMessages
        )
        _ = await (eventLoop, pollLoop)
    }

    private enum ReloadResult {
        case success([Message])
        case failure(Error)
    }

    private func fullReloadOrThrow(sessionID: String, directory: String?, reason: String) async throws -> [Message] {
        SessionPerformanceProbe.emit("history_request_started")
        let messages = try await listMessages(sessionID, directory)
        SessionPerformanceProbe.emit("history_request_finished")
        store.replaceAll(sessionID: sessionID, messages: messages)
        lastFingerprint[sessionID] = messageFingerprint(messages)
        return messages
    }

    private func fullReloadResult(sessionID: String, directory: String?, reason: String) async -> ReloadResult {
        SessionPerformanceProbe.emit("history_request_started")
        do {
            let messages = try await listMessages(sessionID, directory)
            store.replaceAll(sessionID: sessionID, messages: messages)
            lastFingerprint[sessionID] = messageFingerprint(messages)
            return .success(messages)
        } catch {
            return .failure(error)
        }
    }

    private func fullReloadSilent(
        sessionID: String,
        directory: String?,
        reason: String,
        publishMessages: @escaping ([Message]) -> Void
    ) async {
        switch await fullReloadResult(sessionID: sessionID, directory: directory, reason: reason) {
        case .success(let messages):
            publishMessages(messages)
        case .failure:
            break
        }
    }

    private func runEventRefreshLoop(
        sessionID: String,
        directory: String?,
        publishMessages: @escaping ([Message]) -> Void
    ) async {
        var debounceTask: Task<Void, Never>?
        defer { debounceTask?.cancel() }

        for await event in eventStream.events() {
            if event.type == "stream.reconnected" {
                debounceTask?.cancel()
                debounceTask = nil
                await fullReloadSilent(
                    sessionID: sessionID,
                    directory: directory,
                    reason: event.type,
                    publishMessages: publishMessages
                )
                continue
            }

            if event.type == "server.connected" {
                debounceTask?.cancel()
                debounceTask = Task { [weak self] in
                    try? await Task.sleep(for: .milliseconds(LiveStoreRefreshPolicy.messageDebounceMs))
                    guard !Task.isCancelled, let self else { return }
                    await self.fullReloadSilent(
                        sessionID: sessionID,
                        directory: directory,
                        reason: event.type,
                        publishMessages: publishMessages
                    )
                }
                continue
            }

            guard event.sessionID == sessionID else { continue }

            switch event.type {
            case "message.updated":
                handleMessageUpdated(event: event, sessionID: sessionID, publishMessages: publishMessages)
            case "message.part.updated":
                if requiresFullReloadForBaseOnlyPartUpdate(event: event, sessionID: sessionID) {
                    await fullReloadSilent(
                        sessionID: sessionID,
                        directory: directory,
                        reason: "message.part.updated-base-only",
                        publishMessages: publishMessages
                    )
                    continue
                }
                handleMessagePartUpdated(event: event, sessionID: sessionID, publishMessages: publishMessages)
            case "message.removed":
                handleMessageRemoved(event: event, sessionID: sessionID, publishMessages: publishMessages)
            case "message.part.removed":
                if requiresFullReloadForBaseOnlyPartRemoval(event: event, sessionID: sessionID) {
                    await fullReloadSilent(
                        sessionID: sessionID,
                        directory: directory,
                        reason: "message.part.removed-base-only",
                        publishMessages: publishMessages
                    )
                    continue
                }
                handleMessagePartRemoved(event: event, sessionID: sessionID, publishMessages: publishMessages)
            default:
                continue
            }
        }
    }

    private func handleMessageUpdated(
        event: OpencodeEvent,
        sessionID: String,
        publishMessages: @escaping ([Message]) -> Void
    ) {
        guard let props = event.rawProperties,
              let infoDict = props["info"]?.dictionaryValue
        else { return }

        store.apply(sessionID: sessionID, patch: .infoUpdated(infoDict))
        emitMessages(sessionID: sessionID, publishMessages: publishMessages)
    }

    private func handleMessagePartUpdated(
        event: OpencodeEvent,
        sessionID: String,
        publishMessages: @escaping ([Message]) -> Void
    ) {
        guard let props = event.rawProperties,
              let partDict = props["part"]?.dictionaryValue
        else { return }

        store.apply(sessionID: sessionID, patch: .partUpdated(part: partDict, delta: props["delta"]?.stringValue))
        emitMessages(sessionID: sessionID, publishMessages: publishMessages)
    }

    private func handleMessageRemoved(
        event: OpencodeEvent,
        sessionID: String,
        publishMessages: @escaping ([Message]) -> Void
    ) {
        guard let messageID = event.rawProperties?["messageID"]?.stringValue else { return }
        store.apply(sessionID: sessionID, patch: .removed(messageID: messageID))
        emitMessages(sessionID: sessionID, publishMessages: publishMessages)
    }

    private func handleMessagePartRemoved(
        event: OpencodeEvent,
        sessionID: String,
        publishMessages: @escaping ([Message]) -> Void
    ) {
        guard let props = event.rawProperties,
              let messageID = props["messageID"]?.stringValue,
              let partID = props["partID"]?.stringValue
        else { return }

        store.apply(sessionID: sessionID, patch: .partRemoved(messageID: messageID, partID: partID))
        emitMessages(sessionID: sessionID, publishMessages: publishMessages)
    }

    private func requiresFullReloadForBaseOnlyPartUpdate(event: OpencodeEvent, sessionID: String) -> Bool {
        guard let partDict = event.rawProperties?["part"]?.dictionaryValue,
              let messageID = partDict["messageID"]?.stringValue
        else { return false }
        return store.isBaseOnlyMessage(sessionID: sessionID, messageID: messageID)
    }

    private func requiresFullReloadForBaseOnlyPartRemoval(event: OpencodeEvent, sessionID: String) -> Bool {
        guard let messageID = event.rawProperties?["messageID"]?.stringValue else { return false }
        return store.isBaseOnlyMessage(sessionID: sessionID, messageID: messageID)
    }

    private func emitMessages(
        sessionID: String,
        publishMessages: @escaping ([Message]) -> Void
    ) {
        let messages = store.messages(sessionID: sessionID)
        guard !messages.isEmpty, markChanged(sessionID: sessionID, messages: messages) else { return }
        publishMessages(messages)
    }

    private func runTimelinePollLoop(
        sessionID: String,
        directory: String?,
        publishMessages: @escaping ([Message]) -> Void
    ) async {
        while !Task.isCancelled {
            try? await Task.sleep(for: .milliseconds(LiveStoreRefreshPolicy.workingPollIntervalMs))
            guard !Task.isCancelled else { return }
            guard let messages = try? await listMessages(sessionID, directory) else { continue }
            let fp = messageFingerprint(messages)
            guard fp != lastFingerprint[sessionID] else { continue }
            store.replaceAll(sessionID: sessionID, messages: messages)
            publishMessages(messages)
        }
    }

    private func messageFingerprint(_ messages: [Message]) -> String {
        messages.map { "\($0.id):\($0.text.count):\($0.text.hashValue)" }.joined(separator: "|")
    }

    private func markChanged(sessionID: String, messages: [Message]) -> Bool {
        let fp = messageFingerprint(messages)
        if fp == lastFingerprint[sessionID] { return false }
        lastFingerprint[sessionID] = fp
        return true
    }
}
