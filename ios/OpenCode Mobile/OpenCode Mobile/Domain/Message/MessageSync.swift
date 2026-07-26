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
    private let pollIntervalMs: Int

    private var observationCache: [ObservationKey: SharedLiveObservation<MessageListUpdate>] = [:]
    private var lastFingerprint: [String: String] = [:]

    init(
        listMessages: @escaping MessageListFetcher,
        store: InMemoryMessageStore,
        eventStream: any ForegroundEventStream,
        pollIntervalMs: Int = LiveStoreRefreshPolicy.workingPollIntervalMs
    ) {
        self.listMessages = listMessages
        self.store = store
        self.eventStream = eventStream
        self.pollIntervalMs = pollIntervalMs
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
        // Register before the initial REST request. AsyncStream buffers events that
        // arrive while history is loading, closing the part-shell/delta loss window.
        let eventUpdates = eventStream.events()

        let publishMessages: @MainActor @Sendable ([Message]) -> Void = { [weak self] messages in
            guard let self else { return }
            yield(.messages(messages))
            self.lastFingerprint[sessionID] = self.messageFingerprint(messages)
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
            eventUpdates: eventUpdates,
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
        let expectedRevision = store.revision(sessionID: sessionID)
        let messages = try await listMessages(sessionID, directory)
        SessionPerformanceProbe.emit("history_request_finished")
        _ = store.replaceAllIfRevision(
            sessionID: sessionID,
            messages: messages,
            expectedRevision: expectedRevision
        )
        let merged = store.messages(sessionID: sessionID)
        lastFingerprint[sessionID] = messageFingerprint(merged)
        return merged
    }

    private func fullReloadResult(sessionID: String, directory: String?, reason: String) async -> ReloadResult {
        SessionPerformanceProbe.emit("history_request_started")
        let expectedRevision = store.revision(sessionID: sessionID)
        do {
            let messages = try await listMessages(sessionID, directory)
            _ = store.replaceAllIfRevision(
                sessionID: sessionID,
                messages: messages,
                expectedRevision: expectedRevision
            )
            let merged = store.messages(sessionID: sessionID)
            lastFingerprint[sessionID] = messageFingerprint(merged)
            return .success(merged)
        } catch {
            return .failure(error)
        }
    }

    private func fullReloadSilent(
        sessionID: String,
        directory: String?,
        reason: String,
        publishMessages: @escaping @MainActor @Sendable ([Message]) -> Void
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
        eventUpdates: AsyncStream<OpencodeEvent>,
        publishMessages: @escaping @MainActor @Sendable ([Message]) -> Void
    ) async {
        var debounceTask: Task<Void, Never>?
        defer { debounceTask?.cancel() }

        for await event in eventUpdates {
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
                if requiresFullReloadForRestSeededPartUpdate(event: event, sessionID: sessionID) {
                    await fullReloadSilent(
                        sessionID: sessionID,
                        directory: directory,
                        reason: "message.part.updated-rest-seeded",
                        publishMessages: publishMessages
                    )
                    continue
                }
                handleMessagePartUpdated(event: event, sessionID: sessionID, publishMessages: publishMessages)
            case "message.part.delta":
                handleMessagePartDelta(event: event, sessionID: sessionID, publishMessages: publishMessages)
            case "message.removed":
                handleMessageRemoved(event: event, sessionID: sessionID, publishMessages: publishMessages)
            case "message.part.removed":
                if requiresFullReloadForRestSeededPartRemoval(event: event, sessionID: sessionID) {
                    await fullReloadSilent(
                        sessionID: sessionID,
                        directory: directory,
                        reason: "message.part.removed-rest-seeded",
                        publishMessages: publishMessages
                    )
                    continue
                }
                handleMessagePartRemoved(event: event, sessionID: sessionID, publishMessages: publishMessages)
            case "session.idle":
                switch await fullReloadResult(sessionID: sessionID, directory: directory, reason: "session.idle") {
                case .success(let messages):
                    publishMessages(messages)
                case .failure:
                    store.finishStreamingText(sessionID: sessionID)
                    emitMessages(sessionID: sessionID, publishMessages: publishMessages)
                }
            default:
                continue
            }
        }
    }

    private func handleMessageUpdated(
        event: OpencodeEvent,
        sessionID: String,
        publishMessages: @escaping @MainActor @Sendable ([Message]) -> Void
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
        publishMessages: @escaping @MainActor @Sendable ([Message]) -> Void
    ) {
        guard let props = event.rawProperties,
              let partDict = props["part"]?.dictionaryValue
        else { return }

        store.apply(sessionID: sessionID, patch: .partUpdated(part: partDict, delta: props["delta"]?.stringValue))
        emitMessages(sessionID: sessionID, publishMessages: publishMessages)
    }

    private func handleMessagePartDelta(
        event: OpencodeEvent,
        sessionID: String,
        publishMessages: @escaping @MainActor @Sendable ([Message]) -> Void
    ) {
        guard let props = event.rawProperties else { return }
        guard props["field"]?.stringValue == "text" else { return }
        guard let messageID = props["messageID"]?.stringValue,
              let partID = props["partID"]?.stringValue,
              let delta = props["delta"]?.stringValue
        else { return }

        store.apply(sessionID: sessionID, patch: .partDelta(messageID: messageID, partID: partID, delta: delta))
        emitMessages(sessionID: sessionID, publishMessages: publishMessages)
    }

    private func handleMessageRemoved(
        event: OpencodeEvent,
        sessionID: String,
        publishMessages: @escaping @MainActor @Sendable ([Message]) -> Void
    ) {
        guard let messageID = event.rawProperties?["messageID"]?.stringValue else { return }
        store.apply(sessionID: sessionID, patch: .removed(messageID: messageID))
        emitMessages(sessionID: sessionID, publishMessages: publishMessages)
    }

    private func handleMessagePartRemoved(
        event: OpencodeEvent,
        sessionID: String,
        publishMessages: @escaping @MainActor @Sendable ([Message]) -> Void
    ) {
        guard let props = event.rawProperties,
              let messageID = props["messageID"]?.stringValue,
              let partID = props["partID"]?.stringValue
        else { return }

        store.apply(sessionID: sessionID, patch: .partRemoved(messageID: messageID, partID: partID))
        emitMessages(sessionID: sessionID, publishMessages: publishMessages)
    }

    private func requiresFullReloadForRestSeededPartUpdate(event: OpencodeEvent, sessionID: String) -> Bool {
        guard let partDict = event.rawProperties?["part"]?.dictionaryValue,
              let messageID = partDict["messageID"]?.stringValue
        else { return false }
        return store.isRestSeededMessage(sessionID: sessionID, messageID: messageID)
    }

    private func requiresFullReloadForRestSeededPartRemoval(event: OpencodeEvent, sessionID: String) -> Bool {
        guard let messageID = event.rawProperties?["messageID"]?.stringValue else { return false }
        return store.isRestSeededMessage(sessionID: sessionID, messageID: messageID)
    }

    private func emitMessages(
        sessionID: String,
        publishMessages: @escaping @MainActor @Sendable ([Message]) -> Void
    ) {
        let messages = store.messages(sessionID: sessionID)
        guard markChanged(sessionID: sessionID, messages: messages) else { return }
        publishMessages(messages)
    }

    private func runTimelinePollLoop(
        sessionID: String,
        directory: String?,
        publishMessages: @escaping @MainActor @Sendable ([Message]) -> Void
    ) async {
        while !Task.isCancelled {
            try? await Task.sleep(for: .milliseconds(pollIntervalMs))
            guard !Task.isCancelled else { return }
            // Polling REST mid-stream publishes a behind snapshot and looks like the
            // assistant message clearing then reprinting from the start.
            if store.hasStreamingText(sessionID: sessionID) { continue }
            let expectedRevision = store.revision(sessionID: sessionID)
            guard let messages = try? await listMessages(sessionID, directory) else { continue }
            let fp = messageFingerprint(messages)
            guard fp != lastFingerprint[sessionID] else { continue }
            guard store.replaceAllIfRevision(
                sessionID: sessionID,
                messages: messages,
                expectedRevision: expectedRevision
            ) else { continue }
            let merged = store.messages(sessionID: sessionID)
            publishMessages(merged)
        }
    }

    private func messageFingerprint(_ messages: [Message]) -> String {
        messages.map { message in
            let parts = message.parts.map(partFingerprint).joined(separator: ",")
            return "\(message.id):\(message.text.count):\(message.text.hashValue):\(parts)"
        }.joined(separator: "|")
    }

    private func partFingerprint(_ part: MessagePart) -> String {
        switch part {
        case .text(let text):
            "text:\(text.text.count):\(text.text.hashValue):\(text.isStreaming)"
        case .file(let file):
            "file:\(file.id):\(file.mime):\(file.filename):\(file.dataURL.count):\(file.dataURL.hashValue)"
        case .tool(let tool):
            "tool:\(tool.toolName):\(tool.status.rawValue):\(tool.title ?? ""):\(tool.error ?? "")"
        }
    }

    private func markChanged(sessionID: String, messages: [Message]) -> Bool {
        let fp = messageFingerprint(messages)
        if fp == lastFingerprint[sessionID] { return false }
        lastFingerprint[sessionID] = fp
        return true
    }
}
