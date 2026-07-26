import Foundation

@MainActor
final class InMemoryMessageStore: MessageStore {
    private let adapter = MessageDomainAdapter()
    private let decoder = JSONDecoder()

    private var infoCache: [String: [String: AnySendable]] = [:]
    private var partsCache: [String: [String: [String: MessagePart]]] = [:]
    private var partOrder: [String: [String: [String]]] = [:]
    private var ignoredPartIDs: [String: [String: Set<String>]] = [:]
    private var messageOrder: [String: [String]] = [:]
    private var baseMessages: [String: [Message]] = [:]
    private var revisions: [String: UInt64] = [:]

    func replaceAll(sessionID: String, messages: [Message]) {
        replaceAllLocked(sessionID: sessionID, messages: messages)
    }

    private func replaceAllLocked(sessionID: String, messages: [Message]) {
        infoCache[sessionID] = [:]
        partsCache[sessionID] = [:]
        partOrder[sessionID] = [:]
        ignoredPartIDs[sessionID] = [:]
        messageOrder[sessionID] = messages.map(\.id)
        baseMessages[sessionID] = messages
        bumpRevision(sessionID: sessionID)
    }

    func apply(sessionID: String, patch: MessagePatch) {
        switch patch {
        case .infoUpdated(let infoDict):
            applyInfoUpdated(sessionID: sessionID, infoDict: infoDict)
        case .partUpdated(let partDict, let delta):
            applyPartUpdated(sessionID: sessionID, partDict: partDict, delta: delta)
        case .partDelta(let messageID, let partID, let delta):
            applyPartDelta(sessionID: sessionID, messageID: messageID, partID: partID, delta: delta)
        case .removed(let messageID):
            applyMessageRemoved(sessionID: sessionID, messageID: messageID)
        case .partRemoved(let messageID, let partID):
            applyPartRemoved(sessionID: sessionID, messageID: messageID, partID: partID)
        }
        bumpRevision(sessionID: sessionID)
    }

    func messages(sessionID: String) -> [Message] {
        let infos = infoCache[sessionID] ?? [:]
        let parts = partsCache[sessionID] ?? [:]
        let orders = partOrder[sessionID] ?? [:]
        let order = messageOrder[sessionID] ?? []
        let base = baseMessages[sessionID] ?? []
        let baseByID = Dictionary(uniqueKeysWithValues: base.map { ($0.id, $0) })

        return order.compactMap { messageID -> Message? in
            if let infoValue = infos[messageID], case .dictionary(let infoDict) = infoValue,
               let info = decodeInfo(from: infoDict) {
                let sortedParts = orderedParts(for: messageID, parts: parts, orders: orders)
                let message = adapter.message(info: info, domainParts: sortedParts)
                return hasRenderableContent(message) ? message : baseByID[messageID]
            }
            return baseByID[messageID]
        }
    }

    func isRestSeededMessage(sessionID: String, messageID: String) -> Bool {
        baseMessages[sessionID]?.contains { $0.id == messageID } == true
    }

    func hasStreamingText(sessionID: String) -> Bool {
        guard let sessionParts = partsCache[sessionID] else { return false }
        for messageParts in sessionParts.values {
            for part in messageParts.values {
                if case .text(let textPart) = part, textPart.isStreaming {
                    return true
                }
            }
        }
        return false
    }

    func revision(sessionID: String) -> UInt64 {
        revisions[sessionID] ?? 0
    }

    func replaceAllIfRevision(
        sessionID: String,
        messages: [Message],
        expectedRevision: UInt64
    ) -> Bool {
        guard revision(sessionID: sessionID) == expectedRevision else { return false }
        replaceAllLocked(sessionID: sessionID, messages: messages)
        return true
    }

    func finishStreamingText(sessionID: String) {
        var changed = false
        guard var sessionParts = partsCache[sessionID] else { return }
        for (messageID, var messageParts) in sessionParts {
            for (partID, part) in messageParts {
                guard case .text(let text) = part, text.isStreaming else { continue }
                messageParts[partID] = .text(TextPart(text: text.text, isStreaming: false))
                changed = true
            }
            sessionParts[messageID] = messageParts
        }
        guard changed else { return }
        partsCache[sessionID] = sessionParts
        bumpRevision(sessionID: sessionID)
    }

    func clear() {
        infoCache.removeAll()
        partsCache.removeAll()
        partOrder.removeAll()
        ignoredPartIDs.removeAll()
        messageOrder.removeAll()
        baseMessages.removeAll()
        revisions.removeAll()
    }

    private func applyInfoUpdated(sessionID: String, infoDict: [String: AnySendable]) {
        guard let info = decodeInfo(from: infoDict) else { return }
        let messageID = info.id

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

        if messageOrder[sessionID] == nil { messageOrder[sessionID] = [] }
        if !messageOrder[sessionID]!.contains(messageID) {
            messageOrder[sessionID]?.append(messageID)
        }
    }

    private func applyPartUpdated(sessionID: String, partDict: [String: AnySendable], delta: String?) {
        guard let partDTO = decodePart(from: partDict),
              let messageID = partDTO.messageID,
              let partID = partDTO.id
        else { return }

        if partsCache[sessionID] == nil { partsCache[sessionID] = [:] }
        if partsCache[sessionID]?[messageID] == nil { partsCache[sessionID]?[messageID] = [:] }
        if partOrder[sessionID] == nil { partOrder[sessionID] = [:] }
        if partOrder[sessionID]?[messageID] == nil { partOrder[sessionID]?[messageID] = [] }
        if ignoredPartIDs[sessionID] == nil { ignoredPartIDs[sessionID] = [:] }

        let existingPart = partsCache[sessionID]?[messageID]?[partID]
        guard let resolvedPart = resolvePart(partDTO: partDTO, existingPart: existingPart, delta: delta) else {
            // Reasoning/step/etc. — never promote their deltas into assistant text.
            var ignored = ignoredPartIDs[sessionID]?[messageID] ?? []
            ignored.insert(partID)
            ignoredPartIDs[sessionID]?[messageID] = ignored
            partsCache[sessionID]?[messageID]?.removeValue(forKey: partID)
            partOrder[sessionID]?[messageID]?.removeAll { $0 == partID }
            return
        }

        ignoredPartIDs[sessionID]?[messageID]?.remove(partID)
        if partOrder[sessionID]?[messageID]?.contains(partID) != true {
            partOrder[sessionID]?[messageID]?.append(partID)
        }
        partsCache[sessionID]?[messageID]?[partID] = resolvedPart
    }

    private func applyPartDelta(sessionID: String, messageID: String, partID: String, delta: String) {
        if ignoredPartIDs[sessionID]?[messageID]?.contains(partID) == true { return }
        guard case .text(let existing)? = partsCache[sessionID]?[messageID]?[partID] else { return }
        partsCache[sessionID]?[messageID]?[partID] = .text(TextPart(text: existing.text + delta, isStreaming: true))
    }

    private func applyMessageRemoved(sessionID: String, messageID: String) {
        infoCache[sessionID]?.removeValue(forKey: messageID)
        partsCache[sessionID]?.removeValue(forKey: messageID)
        partOrder[sessionID]?.removeValue(forKey: messageID)
        ignoredPartIDs[sessionID]?.removeValue(forKey: messageID)
        messageOrder[sessionID]?.removeAll { $0 == messageID }
    }

    private func applyPartRemoved(sessionID: String, messageID: String, partID: String) {
        partsCache[sessionID]?[messageID]?.removeValue(forKey: partID)
        partOrder[sessionID]?[messageID]?.removeAll { $0 == partID }
        ignoredPartIDs[sessionID]?[messageID]?.remove(partID)
        if ignoredPartIDs[sessionID]?[messageID]?.isEmpty == true {
            ignoredPartIDs[sessionID]?.removeValue(forKey: messageID)
        }
    }

    private func decodeInfo(from dict: [String: AnySendable]) -> MessageInfoDTO? {
        guard let data = try? JSONSerialization.data(withJSONObject: anySendableToJSON(dict)) else { return nil }
        return try? decoder.decode(MessageInfoDTO.self, from: data)
    }

    private func decodePart(from dict: [String: AnySendable]) -> MessagePartDTO? {
        guard let data = try? JSONSerialization.data(withJSONObject: anySendableToJSON(dict)) else { return nil }
        return try? decoder.decode(MessagePartDTO.self, from: data)
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

    private func bumpRevision(sessionID: String) {
        revisions[sessionID, default: 0] &+= 1
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
}
