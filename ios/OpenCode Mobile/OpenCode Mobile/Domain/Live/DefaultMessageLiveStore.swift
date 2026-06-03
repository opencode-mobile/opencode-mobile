import Foundation

@MainActor
final class DefaultMessageLiveStore: MessageLiveStore {
    private struct ObservationKey: Hashable {
        let sessionID: String
        let directory: String
    }

    private let messageRepository: any MessageRepository
    private let eventStream: any ForegroundEventStream
    private let adapter = MessageDomainAdapter()
    private var cache: [ObservationKey: SharedLiveObservation<[Message]>] = [:]

    /// Per-session incremental cache: messageID → raw info properties (set from SSE).
    private var infoCache: [String: [String: AnySendable]] = [:]
    /// Per-session incremental cache: (messageID, partID) → domain part.
    private var partsCache: [String: [String: [String: MessagePart]]] = [:]
    /// Per-session ordered part IDs for each message.
    private var partOrder: [String: [String: [String]]] = [:]
    /// Per-session server-ordered list of message IDs.
    private var messageOrder: [String: [String]] = [:]
    /// Per-session fingerprint for heartbeat polling.
    private var lastFingerprint: [String: String] = [:]
    /// Base messages from the last full REST reload.
    private var baseMessages: [String: [Message]] = [:]
    /// JSON decoder for parsing event payloads.
    private let decoder = JSONDecoder()

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

    func clearCache() {
        cache.removeAll()
        infoCache.removeAll()
        partsCache.removeAll()
        partOrder.removeAll()
        messageOrder.removeAll()
        lastFingerprint.removeAll()
    }

    // MARK: - Message Loop

    private func runMessageLoop(
        sessionID: String,
        directory: String,
        yield: @escaping ([Message]) -> Void
    ) async {
        func publish(_ messages: [Message]) {
            yield(messages)
            let fp = messageFingerprint(messages)
            lastFingerprint[sessionID] = fp
        }

        // Initial full load: populate caches and emit.
        await fullReload(sessionID: sessionID, directory: directory, publish: publish)

        async let eventLoop: Void = runEventRefreshLoop(sessionID: sessionID, directory: directory, publish: publish)
        async let pollLoop: Void = runTimelinePollLoop(sessionID: sessionID, directory: directory, publish: publish)
        _ = await (eventLoop, pollLoop)
    }

    // MARK: - Full Reload

    private func fullReload(
        sessionID: String,
        directory: String,
        publish: @escaping ([Message]) -> Void
    ) async {
        guard let messages = await loadMessages(sessionID: sessionID, directory: directory) else { return }
        rebuildCache(sessionID: sessionID, messages: messages)
        publish(messages)
    }

    private func rebuildCache(sessionID: String, messages: [Message]) {
        infoCache[sessionID] = [:]
        partsCache[sessionID] = [:]
        partOrder[sessionID] = [:]
        messageOrder[sessionID] = messages.map(\.id)
        baseMessages[sessionID] = messages
    }

    // MARK: - Event Refresh Loop (Incremental)

    private func runEventRefreshLoop(
        sessionID: String,
        directory: String,
        publish: @escaping ([Message]) -> Void
    ) async {
        var debounceTask: Task<Void, Never>?
        defer { debounceTask?.cancel() }

        for await event in eventStream.events() {
            if event.type == "stream.reconnected" || event.type == "server.connected" {
                // Connection reset — full reload.
                debounceTask?.cancel()
                debounceTask = Task { [weak self] in
                    try? await Task.sleep(for: .milliseconds(LiveStoreRefreshPolicy.messageDebounceMs))
                    guard !Task.isCancelled, let self else { return }
                    await self.fullReload(sessionID: sessionID, directory: directory, publish: publish)
                }
                continue
            }

            guard event.sessionID == sessionID else { continue }

            switch event.type {
            case "message.updated":
                handleMessageUpdated(event: event, sessionID: sessionID, publish: publish)
            case "message.part.updated":
                handleMessagePartUpdated(event: event, sessionID: sessionID, publish: publish)
            case "message.removed":
                handleMessageRemoved(event: event, sessionID: sessionID, publish: publish)
            case "message.part.removed":
                handleMessagePartRemoved(event: event, sessionID: sessionID, publish: publish)
            default:
                continue
            }
        }
    }

    private func handleMessageUpdated(
        event: OpencodeEvent,
        sessionID: String,
        publish: @escaping ([Message]) -> Void
    ) {
        guard let props = event.rawProperties,
              let infoDict = props["info"]?.dictionaryValue
        else { return }

        // Decode the info from raw properties.
        guard let info = decodeInfo(from: infoDict) else { return }
        let messageID = info.id

        // Ensure slot exists in caches.
        if infoCache[sessionID] == nil { infoCache[sessionID] = [:] }
        if partsCache[sessionID] == nil { partsCache[sessionID] = [:] }
        infoCache[sessionID]?[messageID] = .dictionary(infoDict)

        if partsCache[sessionID]?[messageID] == nil {
            partsCache[sessionID]?[messageID] = [:]
        }
        if partOrder[sessionID] == nil { partOrder[sessionID] = [:] }
        if partOrder[sessionID]?[messageID] == nil {
            partOrder[sessionID]?[messageID] = []
        }

        // Ensure it appears in the ordering.
        if messageOrder[sessionID] == nil { messageOrder[sessionID] = [] }
        if !messageOrder[sessionID]!.contains(messageID) {
            // Append; a full reload will fix ordering if needed.
            messageOrder[sessionID]?.append(messageID)
        }

        emitMessages(sessionID: sessionID, publish: publish)
    }

    private func handleMessagePartUpdated(
        event: OpencodeEvent,
        sessionID: String,
        publish: @escaping ([Message]) -> Void
    ) {
        guard let props = event.rawProperties,
              let partDict = props["part"]?.dictionaryValue
        else { return }

        // Parse the part DTO from the raw dictionary.
        guard let partDTO = decodePart(from: partDict),
              let messageID = partDTO.messageID,
              let partID = partDTO.id
        else { return }

        if partsCache[sessionID] == nil { partsCache[sessionID] = [:] }
        if partsCache[sessionID]?[messageID] == nil { partsCache[sessionID]?[messageID] = [:] }
        if partOrder[sessionID] == nil { partOrder[sessionID] = [:] }
        if partOrder[sessionID]?[messageID] == nil { partOrder[sessionID]?[messageID] = [] }

        let existingPart = partsCache[sessionID]?[messageID]?[partID]
        guard let resolvedPart = resolvePart(partDTO: partDTO, existingPart: existingPart, delta: props["delta"]?.stringValue) else {
            return
        }

        if partOrder[sessionID]?[messageID]?.contains(partID) != true {
            partOrder[sessionID]?[messageID]?.append(partID)
        }
        partsCache[sessionID]?[messageID]?[partID] = resolvedPart

        emitMessages(sessionID: sessionID, publish: publish)
    }

    private func handleMessageRemoved(
        event: OpencodeEvent,
        sessionID: String,
        publish: @escaping ([Message]) -> Void
    ) {
        guard let props = event.rawProperties,
              let messageID = props["messageID"]?.stringValue
        else { return }

        infoCache[sessionID]?.removeValue(forKey: messageID)
        partsCache[sessionID]?.removeValue(forKey: messageID)
        partOrder[sessionID]?.removeValue(forKey: messageID)
        messageOrder[sessionID]?.removeAll { $0 == messageID }

        emitMessages(sessionID: sessionID, publish: publish)
    }

    private func handleMessagePartRemoved(
        event: OpencodeEvent,
        sessionID: String,
        publish: @escaping ([Message]) -> Void
    ) {
        guard let props = event.rawProperties,
              let messageID = props["messageID"]?.stringValue,
              let partID = props["partID"]?.stringValue
        else { return }

        partsCache[sessionID]?[messageID]?.removeValue(forKey: partID)
        partOrder[sessionID]?[messageID]?.removeAll { $0 == partID }

        emitMessages(sessionID: sessionID, publish: publish)
    }

    // MARK: - Emit

    private func emitMessages(
        sessionID: String,
        publish: @escaping ([Message]) -> Void
    ) {
        let infos = infoCache[sessionID] ?? [:]
        let parts = partsCache[sessionID] ?? [:]
        let orders = partOrder[sessionID] ?? [:]
        let order = messageOrder[sessionID] ?? []
        let base = baseMessages[sessionID] ?? []
        let baseByID = Dictionary(uniqueKeysWithValues: base.map { ($0.id, $0) })

        let messages: [Message] = order.compactMap { messageID -> Message? in
            // If a cached info dict exists, build from cache (incremental path).
            if let infoValue = infos[messageID], case .dictionary(let infoDict) = infoValue,
               let info = decodeInfo(from: infoDict) {
                let sortedParts = orderedParts(for: messageID, parts: parts, orders: orders)
                let message = adapter.message(info: info, domainParts: sortedParts)
                return hasRenderableContent(message) ? message : baseByID[messageID]
            }
            // Fall back to the base message from the last full reload.
            return baseByID[messageID]
        }

        publish(messages)
    }

    // MARK: - Poll Loop (heartbeat, 30s)

    private func runTimelinePollLoop(
        sessionID: String,
        directory: String,
        publish: @escaping ([Message]) -> Void
    ) async {
        while !Task.isCancelled {
            try? await Task.sleep(for: .milliseconds(LiveStoreRefreshPolicy.workingPollIntervalMs))
            guard !Task.isCancelled else { return }
            guard let messages = await loadMessages(sessionID: sessionID, directory: directory) else { continue }
            let fp = messageFingerprint(messages)
            guard fp != lastFingerprint[sessionID] else { continue }
            rebuildCache(sessionID: sessionID, messages: messages)
            publish(messages)
        }
    }

    // MARK: - Helpers

    private func loadMessages(sessionID: String, directory: String) async -> [Message]? {
        do {
            return try await messageRepository.listMessages(sessionID: sessionID, directory: directory)
        } catch {
            return nil
        }
    }

    private func messageFingerprint(_ messages: [Message]) -> String {
        messages.map { "\($0.id):\($0.text.count):\($0.text.hashValue)" }.joined(separator: "|")
    }

    /// Decode a `MessageInfoDTO` from an `AnySendable` dictionary by round-tripping through JSON.
    private func decodeInfo(from dict: [String: AnySendable]) -> MessageInfoDTO? {
        guard let data = try? JSONSerialization.data(withJSONObject: anySendableToJSON(dict)) else { return nil }
        return try? decoder.decode(MessageInfoDTO.self, from: data)
    }

    /// Decode a `MessagePartDTO` from an `AnySendable` dictionary.
    private func decodePart(from dict: [String: AnySendable]) -> MessagePartDTO? {
        guard let data = try? JSONSerialization.data(withJSONObject: anySendableToJSON(dict)) else { return nil }
        let dto = try? decoder.decode(MessagePartDTO.self, from: data)
        return dto
    }

    private func resolvePart(partDTO: MessagePartDTO, existingPart: MessagePart?, delta: String?) -> MessagePart? {
        if let delta, partDTO.type == "text" {
            let existingText: String
            if case .text(let textPart) = existingPart {
                existingText = textPart.text
            } else {
                existingText = partDTO.text ?? ""
            }
            return .text(TextPart(text: existingText + delta, isStreaming: true))
        }
        return adapter.messagePart(from: partDTO)
    }

    private func orderedParts(
        for messageID: String,
        parts: [String: [String: MessagePart]],
        orders: [String: [String]]
    ) -> [MessagePart] {
        guard let messageParts = parts[messageID] else { return [] }
        let orderedIDs = orders[messageID] ?? []
        let ordered = orderedIDs.compactMap { messageParts[$0] }
        let leftovers = messageParts
            .filter { partID, _ in !orderedIDs.contains(partID) }
            .sorted { $0.key < $1.key }
            .map(\.value)
        return ordered + leftovers
    }

    private func hasRenderableContent(_ message: Message) -> Bool {
        !message.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !message.parts.isEmpty
    }

    /// Convert `AnySendable` tree to JSON-compatible Foundation types.
    private func anySendableToJSON(_ value: AnySendable) -> Any {
        switch value {
        case .string(let v): v
        case .int(let v): v
        case .int64(let v): v
        case .double(let v): v
        case .bool(let v): v
        case .dictionary(let dict): dict.mapValues { anySendableToJSON($0) }
        case .array(let arr): arr.map { anySendableToJSON($0) }
        case .null: NSNull()
        }
    }

    private func anySendableToJSON(_ dict: [String: AnySendable]) -> [String: Any] {
        dict.mapValues { anySendableToJSON($0) }
    }
}
