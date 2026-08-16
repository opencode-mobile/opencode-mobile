import Foundation

nonisolated struct EventDomainAdapter: Sendable {
    func parseEvent(dataLine: String) -> OpencodeEvent? {
        parseEvents(dataLine: dataLine).first
    }

    func parseEvents(dataLine: String) -> [OpencodeEvent] {
        guard let data = dataLine.data(using: .utf8) else { return [] }
        guard
            let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return [] }

        let payload = json["payload"] as? [String: Any]
        let type = payload?["type"] as? String ?? json["type"] as? String
        guard let type else { return [] }

        let properties = payload?["properties"] as? [String: Any]
            ?? json["properties"] as? [String: Any]
            ?? json["data"] as? [String: Any]
            ?? [:]
        return project(type: type, properties: properties)
    }

    private func project(type: String, properties: [String: Any]) -> [OpencodeEvent] {
        switch type {
        case "permission.v2.asked":
            [event(type: "permission.asked", properties: properties)]
        case "permission.v2.replied":
            [event(type: "permission.replied", properties: properties)]
        case "question.v2.asked":
            [event(type: "question.asked", properties: properties)]
        case "question.v2.replied":
            [event(type: "question.replied", properties: properties)]
        case "question.v2.rejected":
            [event(type: "question.rejected", properties: properties)]
        case "session.next.prompt.admitted", "session.next.prompted",
             "session.inbox.enqueued", "session.inbox.delivered":
            [userMessageUpdated(properties)]
        case "session.next.step.started", "session.step.started":
            stepStarted(properties)
        case "session.execution.started", "session.compaction.started", "session.shell.started":
            [statusEvent(properties, statusType: "busy")]
        case "session.next.step.ended",
             "session.execution.succeeded",
             "session.execution.interrupted",
             "session.compaction.ended",
             "session.compaction.failed",
             "session.shell.ended":
            [event(type: "session.idle", properties: properties)]
        case "session.next.step.failed", "session.execution.failed":
            [
                event(type: "session.error", properties: properties),
                event(type: "session.idle", properties: properties),
            ]
        case "session.next.retried", "session.retry.scheduled":
            [statusEvent(properties, statusType: "retry")]
        case "session.next.text.started", "session.text.started":
            textStarted(properties)
        case "session.next.text.delta", "session.text.delta":
            [textDelta(properties)]
        case "session.next.text.ended", "session.text.ended":
            [textPartUpdated(properties)]
        case "session.next.moved", "session.next.agent.switched", "session.next.model.switched",
             "session.moved", "session.agent.selected", "session.model.selected":
            [event(type: "session.updated", properties: properties)]
        case "session.renamed":
            [renamedToUpdated(properties)]
        default:
            [event(type: type, properties: properties)]
        }
    }

    private func event(type: String, properties: [String: Any]) -> OpencodeEvent {
        OpencodeEvent(
            type: type,
            sessionID: sessionID(from: properties),
            rawProperties: properties.mapValues { AnySendable.from($0) ?? .null }
        )
    }

    private func renamedToUpdated(_ properties: [String: Any]) -> OpencodeEvent {
        let sessionID = sessionID(from: properties)
        var info: [String: Any] = [:]
        if let sessionID {
            info["id"] = sessionID
            info["sessionID"] = sessionID
        }
        if let title = string(in: properties, key: "title") {
            info["title"] = title
        }
        return event(type: "session.updated", properties: ["info": info])
    }

    private func userMessageUpdated(_ properties: [String: Any]) -> OpencodeEvent {
        let sessionID = sessionID(from: properties)
        var info: [String: Any] = ["role": "user"]
        if let messageID = string(in: properties, key: "messageID")
            ?? string(in: properties, key: "id")
            ?? string(in: properties, key: "inboxID")
        {
            info["id"] = messageID
        }
        if let sessionID {
            info["sessionID"] = sessionID
        }
        return event(type: "message.updated", properties: ["info": info])
    }

    private func stepStarted(_ properties: [String: Any]) -> [OpencodeEvent] {
        let sessionID = sessionID(from: properties)
        var info: [String: Any] = ["role": "assistant"]
        if let assistantID = string(in: properties, key: "assistantMessageID") {
            info["id"] = assistantID
        }
        if let sessionID {
            info["sessionID"] = sessionID
        }
        return [
            event(type: "message.updated", properties: ["info": info]),
            statusEvent(properties, statusType: "busy"),
        ]
    }

    private func textStarted(_ properties: [String: Any]) -> [OpencodeEvent] {
        let sessionID = sessionID(from: properties)
        var info: [String: Any] = ["role": "assistant"]
        if let assistantID = string(in: properties, key: "assistantMessageID") {
            info["id"] = assistantID
        }
        if let sessionID {
            info["sessionID"] = sessionID
        }
        return [
            event(type: "message.updated", properties: ["info": info]),
            textPartUpdated(properties, text: ""),
        ]
    }

    private func textDelta(_ properties: [String: Any]) -> OpencodeEvent {
        var projected: [String: Any] = ["field": "text"]
        if let sessionID = sessionID(from: properties) {
            projected["sessionID"] = sessionID
        }
        if let messageID = string(in: properties, key: "assistantMessageID") {
            projected["messageID"] = messageID
        }
        if let partID = textPartID(in: properties) {
            projected["partID"] = partID
        }
        if let delta = string(in: properties, key: "delta") {
            projected["delta"] = delta
        }
        return event(type: "message.part.delta", properties: projected)
    }

    private func textPartUpdated(_ properties: [String: Any], text: String? = nil) -> OpencodeEvent {
        var part: [String: Any] = ["type": "text", "text": text ?? string(in: properties, key: "text") ?? ""]
        if let partID = textPartID(in: properties) {
            part["id"] = partID
        }
        if let sessionID = sessionID(from: properties) {
            part["sessionID"] = sessionID
        }
        if let messageID = string(in: properties, key: "assistantMessageID") {
            part["messageID"] = messageID
        }
        return event(type: "message.part.updated", properties: ["part": part])
    }

    private func statusEvent(_ properties: [String: Any], statusType: String) -> OpencodeEvent {
        var projected: [String: Any] = ["status": ["type": statusType]]
        if let sessionID = sessionID(from: properties) {
            projected["sessionID"] = sessionID
        }
        return event(type: "session.status", properties: projected)
    }

    private func sessionID(from properties: [String: Any]?) -> String? {
        guard let properties else { return nil }

        if let id = string(in: properties, key: "sessionID") ?? string(in: properties, key: "sessionId") {
            return id
        }
        if let info = properties["info"] as? [String: Any],
           let id = string(in: info, key: "sessionID") ?? string(in: info, key: "sessionId")
        {
            return id
        }
        if let part = properties["part"] as? [String: Any],
           let id = string(in: part, key: "sessionID") ?? string(in: part, key: "sessionId")
        {
            return id
        }
        return nil
    }

    private func textPartID(in properties: [String: Any]) -> String? {
        if let textID = string(in: properties, key: "textID") {
            return textID
        }
        if let ordinal = string(in: properties, key: "ordinal") {
            return "text-\(ordinal)"
        }
        return nil
    }

    private func string(in object: [String: Any], key: String) -> String? {
        switch object[key] {
        case let value as String:
            value
        case let value as NSNumber:
            value.stringValue
        case let value as Int:
            String(value)
        default:
            nil
        }
    }

    func statusLabel(from status: SessionStatus) -> String? {
        statusLabel(fromRawType: status.raw["type"])
    }

    /// Status carried by a `session.status` SSE payload.
    /// Returns nil when the event is not a usable status update.
    func sessionStatus(from event: OpencodeEvent) -> SessionStatus? {
        guard event.type == "session.status" else { return nil }
        guard let sessionID = event.sessionID else { return nil }
        guard let status = event.rawProperties?["status"]?.dictionaryValue else { return nil }
        var raw: [String: String] = [:]
        for (key, value) in status {
            if let string = Self.string(from: value) {
                raw[key] = string
            }
        }
        return SessionStatus(sessionID: sessionID, raw: raw)
    }

    private static func string(from value: AnySendable) -> String? {
        switch value {
        case let .string(s): s
        case let .int(v): String(v)
        case let .int64(v): String(v)
        case let .double(v): String(v)
        case let .bool(v): String(v)
        case .dictionary, .array, .null: nil
        }
    }

    /// Title change carried by a `session.updated` event for a concrete session id.
    /// Returns nil when the event is not a usable title update.
    func sessionTitleUpdate(from event: OpencodeEvent) -> SessionTitleUpdate? {
        guard event.type == "session.updated" else { return nil }
        let info = event.rawProperties?["info"]?.dictionaryValue
        let sessionID =
            event.sessionID
            ?? info?["id"]?.stringValue
            ?? info?["sessionID"]?.stringValue
            ?? info?["sessionId"]?.stringValue
        guard let sessionID else { return nil }
        guard let title = info?["title"]?.stringValue else { return nil }
        return SessionTitleUpdate(sessionID: sessionID, title: title)
    }

    private func statusLabel(fromRawType rawType: String?) -> String? {
        switch rawType {
        case "busy":
            "Working"
        case "retry":
            "Retrying"
        default:
            nil
        }
    }
}

nonisolated struct SessionTitleUpdate: Equatable, Sendable {
    let sessionID: String
    let title: String
}
