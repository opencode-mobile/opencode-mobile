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
        let dataProperties = properties(fromData: json["data"])
        let type = payload?["type"] as? String
            ?? json["type"] as? String
            ?? json["event"] as? String
            ?? dataProperties?["type"] as? String
        guard let type else { return [] }

        let properties = payload?["properties"] as? [String: Any]
            ?? json["properties"] as? [String: Any]
            ?? nestedEventProperties(dataProperties)
            ?? [:]
        return project(type: type, properties: properties)
    }

    private func properties(fromData data: Any?) -> [String: Any]? {
        if let object = data as? [String: Any] {
            return object
        }
        if let raw = data as? String, let encoded = raw.data(using: .utf8) {
            return (try? JSONSerialization.jsonObject(with: encoded)) as? [String: Any]
        }
        return nil
    }

    private func nestedEventProperties(_ data: [String: Any]?) -> [String: Any]? {
        guard let data else { return nil }
        if let properties = data["properties"] as? [String: Any] {
            return properties
        }
        if let nested = properties(fromData: data["data"]) {
            return nested
        }
        return data
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
        case "session.execution.started":
            [statusEvent(properties, statusType: "busy")]
        case "session.next.step.ended":
            [
                assistantMessageUpdated(properties),
                event(type: "session.active.snapshot", properties: properties),
            ]
        case "session.execution.succeeded",
             "session.execution.interrupted",
             "session.compaction.ended",
             "session.compaction.failed":
            [event(type: "session.idle", properties: properties)]
        case "session.next.step.failed", "session.execution.failed":
            [
                event(type: "session.error", properties: properties),
                event(type: "session.idle", properties: properties),
            ]
        case "session.next.retried", "session.retry.scheduled":
            [statusEvent(properties, statusType: "retry")]
        case "session.next.tool.input.started",
             "session.next.tool.called",
             "session.next.tool.progress",
             "session.next.tool.success",
             "session.next.tool.failed":
            [toolPartUpdated(type: type, properties: properties)]
        case "session.next.shell.started":
            [
                statusEvent(properties, statusType: "busy"),
                shellPartUpdated(properties, status: "running"),
            ]
        case "session.next.shell.ended":
            [
                shellPartUpdated(properties, status: "completed"),
                event(type: "session.active.snapshot", properties: properties),
            ]
        case "session.shell.started":
            [statusEvent(properties, statusType: "busy")]
        case "session.shell.ended":
            [event(type: "session.idle", properties: properties)]
        case "session.next.compaction.started", "session.compaction.started":
            [
                statusEvent(properties, statusType: "busy"),
                compactionPartUpdated(properties, completed: false),
            ]
        case "session.next.compaction.delta":
            [compactionTextDelta(properties)]
        case "session.next.compaction.ended":
            [
                compactionPartUpdated(properties, completed: true),
                event(type: "session.active.snapshot", properties: properties),
            ]
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
        [
            assistantMessageUpdated(properties),
            statusEvent(properties, statusType: "busy"),
        ]
    }

    private func assistantMessageUpdated(_ properties: [String: Any]) -> OpencodeEvent {
        let sessionID = sessionID(from: properties)
        var info: [String: Any] = ["role": "assistant"]
        if let assistantID = string(in: properties, key: "assistantMessageID") ?? string(in: properties, key: "messageID") {
            info["id"] = assistantID
        }
        if let sessionID {
            info["sessionID"] = sessionID
        }
        return event(type: "message.updated", properties: ["info": info])
    }

    private func toolPartUpdated(type: String, properties: [String: Any]) -> OpencodeEvent {
        let status: String
        switch type {
        case "session.next.tool.input.started":
            status = "pending"
        case "session.next.tool.success":
            status = "completed"
        case "session.next.tool.failed":
            status = "error"
        default:
            status = "running"
        }
        let name = string(in: properties, key: "tool") ?? string(in: properties, key: "name") ?? "tool"
        let callID = string(in: properties, key: "callID")
        var output = string(in: properties, key: "output")
        if output == nil, let content = properties["content"] as? [[String: Any]] {
            let texts = content.compactMap { $0["text"] as? String }
            if !texts.isEmpty {
                output = texts.joined()
            }
        }
        let error = (properties["error"] as? [String: Any]).flatMap { $0["message"] as? String }
        return toolLikePartUpdated(
            properties: properties,
            messageID: string(in: properties, key: "assistantMessageID"),
            partID: callID,
            toolName: name,
            status: status,
            callID: callID,
            input: properties["input"] as? [String: Any],
            output: output,
            error: error,
            metadata: properties["structured"] as? [String: Any]
        )
    }

    private func shellPartUpdated(_ properties: [String: Any], status: String) -> OpencodeEvent {
        toolLikePartUpdated(
            properties: properties,
            messageID: string(in: properties, key: "messageID") ?? string(in: properties, key: "assistantMessageID"),
            partID: string(in: properties, key: "callID"),
            toolName: "bash",
            status: status,
            callID: string(in: properties, key: "callID"),
            output: string(in: properties, key: "output"),
            title: "Shell"
        )
    }

    private func compactionPartUpdated(_ properties: [String: Any], completed: Bool) -> OpencodeEvent {
        let sessionID = sessionID(from: properties)
        let messageID = string(in: properties, key: "messageID")
        var part: [String: Any] = [
            "type": "compaction",
            "auto": string(in: properties, key: "reason") == "auto",
            "completed": completed,
        ]
        if let messageID {
            part["id"] = messageID
            part["messageID"] = messageID
        }
        if let sessionID {
            part["sessionID"] = sessionID
        }
        return event(type: "message.part.updated", properties: ["part": part])
    }

    private func compactionTextDelta(_ properties: [String: Any]) -> OpencodeEvent {
        var projected: [String: Any] = ["field": "text"]
        if let sessionID = sessionID(from: properties) {
            projected["sessionID"] = sessionID
        }
        if let messageID = string(in: properties, key: "messageID") {
            projected["messageID"] = messageID
            projected["partID"] = "\(messageID):text:0"
        }
        if let text = string(in: properties, key: "text") {
            projected["delta"] = text
        }
        return event(type: "message.part.delta", properties: projected)
    }

    private func toolLikePartUpdated(
        properties: [String: Any],
        messageID: String?,
        partID: String?,
        toolName: String,
        status: String,
        callID: String?,
        input: [String: Any]? = nil,
        output: String? = nil,
        error: String? = nil,
        metadata: [String: Any]? = nil,
        title: String? = nil
    ) -> OpencodeEvent {
        var state: [String: Any] = ["status": status]
        if let title { state["title"] = title }
        if let input { state["input"] = input }
        if let output { state["output"] = output }
        if let error { state["error"] = error }
        if let metadata { state["metadata"] = metadata }
        var part: [String: Any] = ["type": "tool", "tool": toolName, "state": state]
        if let partID { part["id"] = partID }
        if let sessionID = sessionID(from: properties) { part["sessionID"] = sessionID }
        if let messageID { part["messageID"] = messageID }
        if let callID { part["callID"] = callID }
        return event(type: "message.part.updated", properties: ["part": part])
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
