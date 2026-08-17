import Foundation

nonisolated final class InMemoryMessageStore: MessageStore, @unchecked Sendable {
    private let lock = NSLock()
    private let adapter = MessageDomainAdapter()
    private let decoder = JSONDecoder()

    private var infoCache: [String: [String: AnySendable]] = [:]
    private var partsCache: [String: [String: [String: MessagePart]]] = [:]
    private var partOrder: [String: [String: [String]]] = [:]
    private var ignoredPartIDs: [String: [String: Set<String>]] = [:]
    private var messageOrder: [String: [String]] = [:]
    private var baseMessages: [String: [Message]] = [:]
    private var revisions: [String: UInt64] = [:]

    /// Pending text delta pieces keyed by session → "messageID\0partID".
    private var deltaChunks: [String: [String: [String]]] = [:]

    /// How many characters of the current base text have been matched by late
    /// replay deltas after a snapshot, keyed like `deltaChunks`.
    private var absorbedReplayChars: [String: [String: Int]] = [:]

    func replaceAll(sessionID: String, messages: [Message]) {
        lock.lock()
        defer { lock.unlock() }
        replaceAllLocked(sessionID: sessionID, messages: messages)
    }

    private func replaceAllLocked(sessionID: String, messages: [Message]) {
        ensureCaches(sessionID: sessionID)
        let previousStarts = toolStartTimesLocked(sessionID: sessionID)
        infoCache[sessionID] = [:]
        partsCache[sessionID] = [:]
        partOrder[sessionID] = [:]
        ignoredPartIDs[sessionID] = [:]
        deltaChunks[sessionID] = [:]
        absorbedReplayChars[sessionID] = [:]
        messageOrder[sessionID] = messages.map(\.id)
        baseMessages[sessionID] = messages
        seedPartIdentities(sessionID: sessionID, messages: messages, previousStarts: previousStarts)
        bumpRevision(sessionID: sessionID)
    }

    func apply(sessionID: String, patch: MessagePatch) -> MessageStoreApplyResult {
        lock.lock()
        defer { lock.unlock() }
        guard let mutation: SessionChangeSet = {
            switch patch {
            case .infoUpdated(let infoDict):
                return applyInfoUpdated(sessionID: sessionID, infoDict: infoDict)
            case .partUpdated(let partDict, let delta):
                return applyPartUpdated(sessionID: sessionID, partDict: partDict, delta: delta)
            case .partDelta(let messageID, let partID, let delta):
                return applyPartDelta(sessionID: sessionID, messageID: messageID, partID: partID, delta: delta)
            case .removed(let messageID):
                return applyMessageRemoved(sessionID: sessionID, messageID: messageID)
            case .partRemoved(let messageID, let partID):
                return applyPartRemoved(sessionID: sessionID, messageID: messageID, partID: partID)
            }
        }() else {
            return .noChange
        }
        bumpRevision(sessionID: sessionID)
        var result = mutation
        result.sessionRevision = revisions[sessionID] ?? 0
        return .changed(result)
    }

    func messages(sessionID: String) -> [Message] {
        lock.lock()
        defer { lock.unlock() }
        let order = messageOrder[sessionID] ?? []
        return order.compactMap { rebuildMessageLocked(sessionID: sessionID, messageID: $0) }
    }

    /// Rebuild one message with the same base+live parts overlay as `messages()`.
    func message(sessionID: String, messageID: String) -> Message? {
        lock.lock()
        defer { lock.unlock() }
        return rebuildMessageLocked(sessionID: sessionID, messageID: messageID)
    }

    private func rebuildMessageLocked(sessionID: String, messageID: String) -> Message? {
        let order = messageOrder[sessionID] ?? []
        guard order.contains(messageID) else { return nil }

        let infos = infoCache[sessionID] ?? [:]
        let parts = partsCache[sessionID] ?? [:]
        let orders = partOrder[sessionID] ?? [:]
        let base = baseMessages[sessionID] ?? []
        let baseByID = Dictionary(uniqueKeysWithValues: base.map { ($0.id, $0) })

        if let infoValue = infos[messageID], case .dictionary(let infoDict) = infoValue,
           let info = decodeInfo(from: infoDict) {
            let sortedParts = orderedParts(
                sessionID: sessionID,
                messageID: messageID,
                parts: parts,
                orders: orders
            )
            let message = adapter.message(info: info, domainParts: sortedParts)
            return hasRenderableContent(message) ? message : baseByID[messageID]
        }
        guard let baseMessage = baseByID[messageID] else { return nil }
        let liveParts = orderedParts(
            sessionID: sessionID,
            messageID: messageID,
            parts: parts,
            orders: orders
        )
        guard !liveParts.isEmpty else { return baseMessage }
        let flat = adapter.flattenedText(from: liveParts)
        return Message(
            id: baseMessage.id,
            role: baseMessage.role,
            text: flat.isEmpty ? baseMessage.text : flat,
            parts: liveParts,
            cost: baseMessage.cost,
            tokens: baseMessage.tokens,
            model: baseMessage.model,
            modelProviderID: baseMessage.modelProviderID,
            modelID: baseMessage.modelID,
            variant: baseMessage.variant,
            mode: baseMessage.mode,
            sentAtEpochMs: baseMessage.sentAtEpochMs,
            contextInputTokens: baseMessage.contextInputTokens,
            contextEstimate: baseMessage.contextEstimate
        )
    }

    func isRestSeededMessage(sessionID: String, messageID: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return baseMessages[sessionID]?
            .first(where: { $0.id == messageID })?
            .parts
            .contains(where: { stablePartID($0) == nil }) == true
    }

    func hasStreamingText(sessionID: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard let sessionParts = partsCache[sessionID] else { return false }
        for (messageID, messageParts) in sessionParts {
            for (partID, part) in messageParts {
                guard case .text(let textPart) = part else { continue }
                if textPart.isStreaming || hasChunks(sessionID: sessionID, messageID: messageID, partID: partID) {
                    return true
                }
            }
        }
        return false
    }

    func revision(sessionID: String) -> UInt64 {
        lock.lock()
        defer { lock.unlock() }
        return revisions[sessionID] ?? 0
    }

    func replaceAllIfRevision(
        sessionID: String,
        messages: [Message],
        expectedRevision: UInt64
    ) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard (revisions[sessionID] ?? 0) == expectedRevision else { return false }
        replaceAllLocked(sessionID: sessionID, messages: messages)
        return true
    }

    func finishStreamingText(sessionID: String) {
        lock.lock()
        defer { lock.unlock() }
        var changed = false
        guard var sessionParts = partsCache[sessionID] else { return }
        for (messageID, var messageParts) in sessionParts {
            for (partID, part) in messageParts {
                guard case .text(let text) = part else { continue }
                let flattened = flattenedText(sessionID: sessionID, messageID: messageID, part: text)
                let wasStreaming = text.isStreaming || hasChunks(sessionID: sessionID, messageID: messageID, partID: text.id)
                guard wasStreaming || flattened != text.text else { continue }
                messageParts[partID] = .text(
                    TextPart(
                        id: text.id,
                        text: flattened,
                        isStreaming: false,
                        isSynthetic: text.isSynthetic,
                        isIgnored: text.isIgnored
                    )
                )
                clearChunks(sessionID: sessionID, messageID: messageID, partID: text.id)
                changed = true
            }
            sessionParts[messageID] = messageParts
        }
        guard changed else { return }
        partsCache[sessionID] = sessionParts
        bumpRevision(sessionID: sessionID)
    }

    func clear() {
        lock.lock()
        defer { lock.unlock() }
        infoCache.removeAll()
        partsCache.removeAll()
        partOrder.removeAll()
        ignoredPartIDs.removeAll()
        messageOrder.removeAll()
        baseMessages.removeAll()
        revisions.removeAll()
        deltaChunks.removeAll()
        absorbedReplayChars.removeAll()
    }

    private func applyInfoUpdated(sessionID: String, infoDict: [String: AnySendable]) -> SessionChangeSet? {
        guard let info = decodeInfo(from: infoDict) else { return nil }
        let messageID = info.id

        ensureCaches(sessionID: sessionID)
        let inserted = !(messageOrder[sessionID]?.contains(messageID) ?? false)
        infoCache[sessionID]?[messageID] = .dictionary(infoDict)

        if partsCache[sessionID]?[messageID] == nil {
            partsCache[sessionID]?[messageID] = [:]
        }
        if partOrder[sessionID]?[messageID] == nil {
            partOrder[sessionID]?[messageID] = []
        }
        if inserted {
            insertMessage(sessionID: sessionID, messageID: messageID, role: info.role)
        }
        return SessionChangeSet(
            sessionRevision: 0,
            insertedMessageIDs: inserted ? [messageID] : [],
            changedMessageIDs: inserted ? [] : [messageID],
            structuralChange: inserted
        )
    }

    private func insertMessage(sessionID: String, messageID: String, role: String) {
        guard var order = messageOrder[sessionID] else { return }
        if role.lowercased() == "user",
           let index = order.firstIndex(where: { isTrailingLiveAssistant(sessionID: sessionID, messageID: $0) })
        {
            let precededByUser = index > 0 && isUser(sessionID: sessionID, messageID: order[index - 1])
            if !precededByUser {
                order.insert(messageID, at: index)
                messageOrder[sessionID] = order
                return
            }
        }
        order.append(messageID)
        messageOrder[sessionID] = order
    }

    private func isUser(sessionID: String, messageID: String) -> Bool {
        if infoCache[sessionID]?[messageID]?.dictionaryValue?["role"]?.stringValue?.lowercased() == "user" {
            return true
        }
        return baseMessages[sessionID]?.first(where: { $0.id == messageID })?.role == .user
    }

    private func isTrailingLiveAssistant(sessionID: String, messageID: String) -> Bool {
        let base = baseMessages[sessionID]?.first(where: { $0.id == messageID })
        let infoRole = infoCache[sessionID]?[messageID]?.dictionaryValue?["role"]?.stringValue
        let isAssistant = infoRole?.lowercased() == "assistant" || base?.role == .assistant
        guard isAssistant else { return false }
        let liveParts = partsCache[sessionID]?[messageID]?.values.map { $0 } ?? []
        let parts = liveParts.isEmpty ? (base?.parts ?? []) : liveParts
        if parts.contains(where: { part in
            if case .text(let text) = part {
                return text.isStreaming || hasChunks(sessionID: sessionID, messageID: messageID, partID: text.id)
            }
            return false
        }) {
            return true
        }
        return parts.isEmpty || parts.allSatisfy { part in
            if case .text(let text) = part { return text.text.isEmpty }
            return false
        }
    }

    private func applyPartUpdated(
        sessionID: String,
        partDict: [String: AnySendable],
        delta: String?
    ) -> SessionChangeSet? {
        guard let partDTO = decodePart(from: partDict),
              let messageID = partDTO.messageID,
              let partID = partDTO.id
        else { return nil }

        ensureCaches(sessionID: sessionID)
        if partsCache[sessionID]?[messageID] == nil { partsCache[sessionID]?[messageID] = [:] }
        if partOrder[sessionID]?[messageID] == nil { partOrder[sessionID]?[messageID] = [] }

        let existingPart = partsCache[sessionID]?[messageID]?[partID]
        guard let resolvedPart = resolvePart(
            sessionID: sessionID,
            messageID: messageID,
            partDTO: partDTO,
            existingPart: existingPart,
            delta: delta
        ) else {
            // Reasoning/step/etc. — never promote their deltas into assistant text.
            let hadPart = partsCache[sessionID]?[messageID]?[partID] != nil
            var ignored = ignoredPartIDs[sessionID]?[messageID] ?? []
            ignored.insert(partID)
            ignoredPartIDs[sessionID]?[messageID] = ignored
            partsCache[sessionID]?[messageID]?.removeValue(forKey: partID)
            partOrder[sessionID]?[messageID]?.removeAll { $0 == partID }
            clearChunks(sessionID: sessionID, messageID: messageID, partID: partID)
            guard hadPart else { return nil }
            return SessionChangeSet(
                sessionRevision: 0,
                changedMessageIDs: [messageID],
                changedPartIDsByMessage: [messageID: [partID]],
                structuralChange: true
            )
        }

        ignoredPartIDs[sessionID]?[messageID]?.remove(partID)
        let insertedPart = partOrder[sessionID]?[messageID]?.contains(partID) != true
        if insertedPart {
            partOrder[sessionID]?[messageID]?.append(partID)
        }
        let previous = partsCache[sessionID]?[messageID]?[partID]
        partsCache[sessionID]?[messageID]?[partID] = resolvedPart
        if delta == nil {
            clearChunks(sessionID: sessionID, messageID: messageID, partID: partID)
        }
        let previousStreaming: Bool? = {
            if case .text(let text) = previous { return text.isStreaming }
            return nil
        }()
        let resolvedStreaming: Bool? = {
            if case .text(let text) = resolvedPart { return text.isStreaming }
            return nil
        }()
        let streamingChanged = previousStreaming != resolvedStreaming
        return SessionChangeSet(
            sessionRevision: 0,
            changedMessageIDs: [messageID],
            changedPartIDsByMessage: [messageID: [partID]],
            streamingStateChangedIDs: streamingChanged ? [messageID] : [],
            structuralChange: insertedPart
        )
    }

    private func applyPartDelta(
        sessionID: String,
        messageID: String,
        partID: String,
        delta: String
    ) -> SessionChangeSet? {
        if ignoredPartIDs[sessionID]?[messageID]?.contains(partID) == true { return nil }
        guard case .text(let existing)? = partsCache[sessionID]?[messageID]?[partID] else { return nil }
        let appended = appendChunk(
            sessionID: sessionID,
            messageID: messageID,
            partID: partID,
            baseText: existing.text,
            delta: delta
        )
        guard appended else { return nil }
        let wasStreaming = existing.isStreaming
        if !wasStreaming {
            partsCache[sessionID]?[messageID]?[partID] =
                .text(
                    TextPart(
                        id: existing.id,
                        text: existing.text,
                        isStreaming: true,
                        isSynthetic: existing.isSynthetic,
                        isIgnored: existing.isIgnored
                    )
                )
        }
        return SessionChangeSet(
            sessionRevision: 0,
            changedMessageIDs: [messageID],
            changedPartIDsByMessage: [messageID: [partID]],
            streamingStateChangedIDs: wasStreaming ? [] : [messageID]
        )
    }

    private func applyMessageRemoved(sessionID: String, messageID: String) -> SessionChangeSet? {
        let existed =
            infoCache[sessionID]?[messageID] != nil
            || (messageOrder[sessionID]?.contains(messageID) ?? false)
        guard existed else { return nil }
        infoCache[sessionID]?.removeValue(forKey: messageID)
        partsCache[sessionID]?.removeValue(forKey: messageID)
        partOrder[sessionID]?.removeValue(forKey: messageID)
        ignoredPartIDs[sessionID]?.removeValue(forKey: messageID)
        messageOrder[sessionID]?.removeAll { $0 == messageID }
        let prefix = "\(messageID)\u{0000}"
        deltaChunks[sessionID] = deltaChunks[sessionID]?.filter { !$0.key.hasPrefix(prefix) }
        absorbedReplayChars[sessionID] = absorbedReplayChars[sessionID]?.filter { !$0.key.hasPrefix(prefix) }
        return SessionChangeSet(
            sessionRevision: 0,
            removedMessageIDs: [messageID],
            structuralChange: true
        )
    }

    private func applyPartRemoved(sessionID: String, messageID: String, partID: String) -> SessionChangeSet? {
        let removed = partsCache[sessionID]?[messageID]?.removeValue(forKey: partID) != nil
        partOrder[sessionID]?[messageID]?.removeAll { $0 == partID }
        ignoredPartIDs[sessionID]?[messageID]?.remove(partID)
        if ignoredPartIDs[sessionID]?[messageID]?.isEmpty == true {
            ignoredPartIDs[sessionID]?.removeValue(forKey: messageID)
        }
        clearChunks(sessionID: sessionID, messageID: messageID, partID: partID)
        guard removed else { return nil }
        return SessionChangeSet(
            sessionRevision: 0,
            changedMessageIDs: [messageID],
            changedPartIDsByMessage: [messageID: [partID]],
            structuralChange: true
        )
    }

    private func ensureCaches(sessionID: String) {
        if infoCache[sessionID] == nil { infoCache[sessionID] = [:] }
        if partsCache[sessionID] == nil { partsCache[sessionID] = [:] }
        if partOrder[sessionID] == nil { partOrder[sessionID] = [:] }
        if ignoredPartIDs[sessionID] == nil { ignoredPartIDs[sessionID] = [:] }
        if messageOrder[sessionID] == nil { messageOrder[sessionID] = [] }
        if deltaChunks[sessionID] == nil { deltaChunks[sessionID] = [:] }
        if absorbedReplayChars[sessionID] == nil { absorbedReplayChars[sessionID] = [:] }
    }

    private func bumpRevision(sessionID: String) {
        revisions[sessionID, default: 0] &+= 1
    }

    private func chunkKey(messageID: String, partID: String?) -> String? {
        guard let partID else { return nil }
        return "\(messageID)\u{0000}\(partID)"
    }

    /// Appends a delta chunk, absorbing late replay deltas already covered by a
    /// snapshot base. Returns whether any new characters were stored.
    @discardableResult
    private func appendChunk(
        sessionID: String,
        messageID: String,
        partID: String,
        baseText: String,
        delta: String
    ) -> Bool {
        guard let key = chunkKey(messageID: messageID, partID: partID) else { return false }
        ensureCaches(sessionID: sessionID)
        var sessionChunks = deltaChunks[sessionID] ?? [:]
        var chunks = sessionChunks[key] ?? []

        if chunks.isEmpty, !baseText.isEmpty, !delta.isEmpty {
            var sessionAbsorbed = absorbedReplayChars[sessionID] ?? [:]
            let absorbed = sessionAbsorbed[key] ?? 0
            if absorbed < baseText.count {
                let unmatched = baseText.dropFirst(absorbed)
                if unmatched.hasPrefix(delta) {
                    sessionAbsorbed[key] = absorbed + delta.count
                    absorbedReplayChars[sessionID] = sessionAbsorbed
                    return false
                }
                if delta.hasPrefix(unmatched) {
                    sessionAbsorbed[key] = baseText.count
                    absorbedReplayChars[sessionID] = sessionAbsorbed
                    let overflow = String(delta.dropFirst(unmatched.count))
                    guard !overflow.isEmpty else { return false }
                    chunks.append(overflow)
                    sessionChunks[key] = chunks
                    deltaChunks[sessionID] = sessionChunks
                    return true
                }
                // Not a replay of the snapshot — stop absorbing and append.
                sessionAbsorbed[key] = baseText.count
                absorbedReplayChars[sessionID] = sessionAbsorbed
            }
        }

        chunks.append(delta)
        sessionChunks[key] = chunks
        deltaChunks[sessionID] = sessionChunks
        return true
    }

    private func clearChunks(sessionID: String, messageID: String, partID: String?) {
        guard let key = chunkKey(messageID: messageID, partID: partID) else { return }
        deltaChunks[sessionID]?.removeValue(forKey: key)
        // Reset so delayed deltas that replay the new snapshot can be absorbed.
        absorbedReplayChars[sessionID]?[key] = 0
    }

    private func hasChunks(sessionID: String, messageID: String, partID: String?) -> Bool {
        guard let key = chunkKey(messageID: messageID, partID: partID) else { return false }
        return !(deltaChunks[sessionID]?[key]?.isEmpty ?? true)
    }

    private func flattenedText(sessionID: String, messageID: String, part: TextPart) -> String {
        guard let key = chunkKey(messageID: messageID, partID: part.id) else { return part.text }
        let chunks = deltaChunks[sessionID]?[key] ?? []
        if chunks.isEmpty { return part.text }
        return part.text + chunks.joined()
    }

    private func decodeInfo(from dict: [String: AnySendable]) -> MessageInfoDTO? {
        guard let data = try? JSONSerialization.data(withJSONObject: anySendableToJSON(dict)) else { return nil }
        return try? decoder.decode(MessageInfoDTO.self, from: data)
    }

    private func decodePart(from dict: [String: AnySendable]) -> MessagePartDTO? {
        guard let data = try? JSONSerialization.data(withJSONObject: anySendableToJSON(dict)) else { return nil }
        return try? decoder.decode(MessagePartDTO.self, from: data)
    }

    private func resolvePart(
        sessionID: String,
        messageID: String,
        partDTO: MessagePartDTO,
        existingPart: MessagePart?,
        delta: String?
    ) -> MessagePart? {
        if let delta, partDTO.type == "text" {
            let existingText: String
            if case .text(let textPart) = existingPart {
                existingText = textPart.text
            } else {
                existingText = partDTO.text ?? ""
            }
            let partID = partDTO.id
            let textPart = TextPart(
                id: partID,
                text: existingText,
                isStreaming: true,
                isSynthetic: {
                    if case .text(let existing) = existingPart {
                        return existing.isSynthetic
                    }
                    return partDTO.synthetic == true
                }(),
                isIgnored: {
                    if case .text(let existing) = existingPart {
                        return existing.isIgnored
                    }
                    return partDTO.ignored == true
                }()
            )
            if let partID {
                appendChunk(
                    sessionID: sessionID,
                    messageID: messageID,
                    partID: partID,
                    baseText: existingText,
                    delta: delta
                )
            }
            return .text(textPart)
        }
        return resolveToolStart(adapter.messagePart(from: partDTO), existing: existingPart)
    }

    private func resolveToolStart(_ part: MessagePart?, existing: MessagePart?) -> MessagePart? {
        guard case .tool(let tool) = part else { return part }
        let existingStart: Int64? = {
            if case .tool(let existingTool) = existing { return existingTool.timeStartEpochMs }
            return nil
        }()
        let resolved = ToolStartTime.resolve(
            incoming: tool.timeStartEpochMs,
            existing: existingStart,
            status: tool.status,
            nowEpochMs: ToolStartTime.nowEpochMs()
        )
        guard resolved != tool.timeStartEpochMs else { return part }
        return .tool(tool.withTimeStart(resolved))
    }

    private func orderedParts(
        sessionID: String,
        messageID: String,
        parts: [String: [String: MessagePart]],
        orders: [String: [String]]
    ) -> [MessagePart] {
        guard let messageParts = parts[messageID] else { return [] }
        let orderedIDs = orders[messageID] ?? []
        let ordered = orderedIDs.compactMap { partID -> MessagePart? in
            guard let part = messageParts[partID] else { return nil }
            return resolveStoredPart(sessionID: sessionID, messageID: messageID, part: part)
        }
        let leftovers = messageParts
            .filter { partID, _ in !orderedIDs.contains(partID) }
            .sorted { $0.key < $1.key }
            .map { resolveStoredPart(sessionID: sessionID, messageID: messageID, part: $0.value) }
        return ordered + leftovers
    }

    private func resolveStoredPart(sessionID: String, messageID: String, part: MessagePart) -> MessagePart {
        guard case .text(let text) = part else { return part }
        let flattened = flattenedText(sessionID: sessionID, messageID: messageID, part: text)
        return flattened == text.text
            ? part
            : .text(
                TextPart(
                    id: text.id,
                    text: flattened,
                    isStreaming: text.isStreaming,
                    isSynthetic: text.isSynthetic,
                    isIgnored: text.isIgnored
                )
            )
    }

    private func hasRenderableContent(_ message: Message) -> Bool {
        !message.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !message.parts.isEmpty
    }

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

    private func toolStartTimesLocked(sessionID: String) -> [String: Int64] {
        var starts: [String: Int64] = [:]
        for (_, parts) in partsCache[sessionID] ?? [:] {
            for (partID, part) in parts {
                if case .tool(let tool) = part, let start = tool.timeStartEpochMs {
                    starts[partID] = start
                }
            }
        }
        return starts
    }

    private func seedPartIdentities(
        sessionID: String,
        messages: [Message],
        previousStarts: [String: Int64]
    ) {
        let now = ToolStartTime.nowEpochMs()
        for message in messages {
            var identifiedParts: [String: MessagePart] = [:]
            var identifiedOrder: [String] = []
            for part in message.parts {
                guard let partID = stablePartID(part) else { continue }
                identifiedParts[partID] = resolveSeededTool(
                    part,
                    partID: partID,
                    previousStarts: previousStarts,
                    nowEpochMs: now
                )
                identifiedOrder.append(partID)
            }
            if !identifiedParts.isEmpty {
                partsCache[sessionID]?[message.id] = identifiedParts
                partOrder[sessionID]?[message.id] = identifiedOrder
            }
        }
    }

    private func resolveSeededTool(
        _ part: MessagePart,
        partID: String,
        previousStarts: [String: Int64],
        nowEpochMs: Int64
    ) -> MessagePart {
        guard case .tool(let tool) = part else { return part }
        let resolved = ToolStartTime.resolve(
            incoming: tool.timeStartEpochMs,
            existing: previousStarts[partID],
            status: tool.status,
            nowEpochMs: nowEpochMs
        )
        guard resolved != tool.timeStartEpochMs else { return part }
        return .tool(tool.withTimeStart(resolved))
    }

    private func stablePartID(_ part: MessagePart) -> String? {
        switch part {
        case .text(let text):
            text.id
        case .tool(let tool):
            tool.id
        case .file(let file):
            file.id
        case .compaction(let part):
            part.id
        }
    }
}
