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
        let type = string(in: payload, key: "type")
            ?? json["type"] as? String
            ?? json["event"] as? String
            ?? dataProperties.flatMap { string(in: $0, key: "type") }
        guard let type else { return [] }

        let eventProperties = withEnvelopeCreated(
            resolveProperties(payload: payload, raw: json, dataProperties: dataProperties),
            raw: json
        )
        if canonicalEventType(type) == "sync" {
            let syncEvent = payload?["syncEvent"] as? [String: Any]
                ?? eventProperties["syncEvent"] as? [String: Any]
            let innerType = syncEvent.flatMap { string(in: $0, key: "type") }
            let data = properties(fromData: syncEvent?["data"]) ?? [:]
            guard let innerType else { return [] }
            return project(type: canonicalEventType(innerType), properties: data)
        }
        return project(type: canonicalEventType(type), properties: eventProperties)
    }

    private func resolveProperties(
        payload: [String: Any]?,
        raw: [String: Any],
        dataProperties: [String: Any]?
    ) -> [String: Any] {
        let candidates: [[String: Any]] = [
            payload?["properties"] as? [String: Any],
            raw["properties"] as? [String: Any],
            nestedEventProperties(dataProperties),
            flattenedEventFields(payload),
            flattenedEventFields(raw),
        ].compactMap { $0 }
        return candidates.first(where: { $0["prompt"] != nil })
            ?? candidates.first(where: { $0["sessionID"] != nil || $0["messageID"] != nil })
            ?? candidates.first
            ?? [:]
    }

    private func withEnvelopeCreated(_ properties: [String: Any], raw: [String: Any]) -> [String: Any] {
        if properties["created"] != nil { return properties }
        guard let created = raw["created"] else { return properties }
        var merged = properties
        merged["created"] = created
        return merged
    }

    private func flattenedEventFields(_ source: [String: Any]?) -> [String: Any]? {
        guard let source else { return nil }
        let fields = source.filter { !Self.wrapperKeys.contains($0.key) }
        return fields.isEmpty ? nil : fields
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
        if data["prompt"] != nil {
            return data
        }
        if let properties = data["properties"] as? [String: Any] {
            return properties
        }
        if let nested = properties(fromData: data["data"]) {
            return nested
        }
        return data
    }

    private func canonicalEventType(_ type: String) -> String {
        guard let dot = type.lastIndex(of: "."),
              type[type.index(after: dot)...].allSatisfy(\.isNumber)
        else { return type }
        return String(type[..<dot])
    }

    private func project(type: String, properties: [String: Any]) -> [OpencodeEvent] {
        switch type {
        case "permission.v2.asked":
            [event(type: "permission.asked", properties: properties)]
        case "permission.v2.replied":
            [event(type: "permission.replied", properties: properties)]
        case "question.v2.asked", "form.created":
            [event(type: "question.asked", properties: properties)]
        case "question.v2.replied", "form.replied":
            [event(type: "question.replied", properties: properties)]
        case "question.v2.rejected", "form.cancelled":
            [event(type: "question.rejected", properties: properties)]
        case "session.next.prompt.admitted", "session.next.prompted",
             "session.inbox.enqueued", "session.inbox.delivered",
             "session.inbox.delivery.changed":
            userPromptEvents(properties)
        case "session.inbox.cancelled":
            [inboxCancelled(properties)]
        case "session.next.step.started", "session.step.started":
            stepStarted(properties)
        case "session.execution.started":
            [statusEvent(properties, statusType: "busy")]
        case "session.next.step.ended", "session.step.ended":
            [
                assistantMessageUpdated(properties),
                event(type: "session.active.snapshot", properties: properties),
            ]
        case "session.execution.succeeded",
             "session.execution.interrupted":
            [event(type: "session.idle", properties: properties)]
        case "session.next.step.failed", "session.execution.failed":
            [
                event(type: "session.error", properties: properties),
                event(type: "session.idle", properties: properties),
            ]
        case "session.step.failed":
            [
                event(type: "session.error", properties: properties),
                assistantMessageUpdated(properties),
            ]
        case "session.next.retried", "session.retry.scheduled":
            [statusEvent(properties, statusType: "retry")]
        case "session.next.tool.input.started", "session.tool.input.started",
             "session.next.tool.input.ended", "session.tool.input.ended",
             "session.next.tool.called", "session.tool.called",
             "session.next.tool.progress", "session.tool.progress",
             "session.next.tool.success", "session.tool.success",
             "session.next.tool.failed", "session.tool.failed":
            [toolPartUpdated(type: type, properties: properties)]
        case "session.next.tool.input.delta", "session.tool.input.delta":
            [toolInputDelta(properties)]
        case "session.next.shell.started", "session.shell.started":
            [
                statusEvent(properties, statusType: "busy"),
                shellPartUpdated(properties, status: "running"),
            ]
        case "session.next.shell.ended", "session.shell.ended":
            [
                shellPartUpdated(properties, status: "completed"),
                event(type: "session.active.snapshot", properties: properties),
            ]
        case "session.next.compaction.started", "session.compaction.started":
            [
                statusEvent(properties, statusType: "busy"),
                compactionPartUpdated(properties, completed: false),
            ]
        case "session.next.compaction.delta", "session.compaction.delta":
            [compactionTextDelta(properties)]
        case "session.next.compaction.ended", "session.compaction.ended":
            [
                compactionPartUpdated(properties, completed: true),
                event(type: "session.active.snapshot", properties: properties),
            ]
        case "session.next.compaction.failed", "session.compaction.failed":
            [
                compactionPartUpdated(properties, completed: true),
                event(type: "session.error", properties: properties),
            ]
        case "session.next.reasoning.started", "session.reasoning.started":
            reasoningStarted(properties)
        case "session.next.reasoning.delta", "session.reasoning.delta":
            [reasoningDelta(properties)]
        case "session.next.reasoning.ended", "session.reasoning.ended":
            [reasoningPartUpdated(properties)]
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

    private func inboxCancelled(_ properties: [String: Any]) -> OpencodeEvent {
        var projected: [String: Any] = [:]
        if let sessionID = sessionID(from: properties) {
            projected["sessionID"] = sessionID
        }
        if let messageID = userMessageID(in: properties) {
            projected["messageID"] = messageID
        }
        return event(type: "message.removed", properties: projected)
    }

    private func userPromptEvents(_ properties: [String: Any]) -> [OpencodeEvent] {
        [userMessageUpdated(properties)] + userPromptParts(properties)
    }

    private func userMessageUpdated(_ properties: [String: Any]) -> OpencodeEvent {
        let sessionID = sessionID(from: properties)
        var info: [String: Any] = ["role": "user"]
        if let messageID = userMessageID(in: properties) {
            info["id"] = messageID
        }
        if let sessionID {
            info["sessionID"] = sessionID
        }
        if let created = int64(in: properties, key: "created") {
            info["time"] = ["created": created]
        }
        if let agent = string(in: properties, key: "agent") ?? string(in: properties, key: "mode") {
            info["agent"] = agent
            info["mode"] = agent
        }
        return event(type: "message.updated", properties: ["info": info])
    }

    private func userPromptParts(_ properties: [String: Any]) -> [OpencodeEvent] {
        let prompt = promptObject(in: properties)
        let sessionID = sessionID(from: properties)
        let messageID = userMessageID(in: properties)
        var parts: [OpencodeEvent] = []
        if let text = prompt.flatMap({ string(in: $0, key: "text") }) ?? string(in: properties, key: "text"),
           !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        {
            parts.append(userTextPartUpdated(sessionID: sessionID, messageID: messageID, text: text))
        }
        guard let prompt else { return parts }
        let files = prompt["files"] as? [Any] ?? []
        for (index, element) in files.enumerated() {
            guard let file = element as? [String: Any] else { continue }
            parts.append(userFilePartUpdated(sessionID: sessionID, messageID: messageID, index: index, file: file))
        }
        return parts
    }

    private func userTextPartUpdated(sessionID: String?, messageID: String?, text: String) -> OpencodeEvent {
        var part: [String: Any] = ["type": "text", "text": text]
        if let messageID {
            part["id"] = "\(messageID):text:0"
            part["messageID"] = messageID
        }
        if let sessionID {
            part["sessionID"] = sessionID
        }
        return event(type: "message.part.updated", properties: ["part": part])
    }

    private func userFilePartUpdated(
        sessionID: String?,
        messageID: String?,
        index: Int,
        file: [String: Any]
    ) -> OpencodeEvent {
        var part: [String: Any] = ["type": "file"]
        if let messageID {
            part["id"] = "\(messageID):file:\(index)"
            part["messageID"] = messageID
        }
        if let sessionID {
            part["sessionID"] = sessionID
        }
        if let uri = string(in: file, key: "uri") {
            part["url"] = uri
        }
        if let mime = string(in: file, key: "mime")
            ?? string(in: file, key: "uri").flatMap(AttachmentDataURL.mime(fromDataURL:))
            ?? string(in: file, key: "name").flatMap(AttachmentDataURL.mime(fromFilename:))
        {
            part["mime"] = mime
        }
        if let name = string(in: file, key: "name") {
            part["filename"] = name
        }
        return event(type: "message.part.updated", properties: ["part": part])
    }

    private func promptObject(in properties: [String: Any]) -> [String: Any]? {
        if let prompt = decodePromptValue(properties["prompt"]) {
            return prompt
        }
        if let item = properties["item"] as? [String: Any] {
            return decodePromptValue(item["payload"])
        }
        return nil
    }

    private func decodePromptValue(_ value: Any?) -> [String: Any]? {
        if let prompt = value as? [String: Any] {
            return prompt
        }
        if let raw = value as? String,
           let data = raw.data(using: .utf8),
           let prompt = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        {
            return prompt
        }
        return nil
    }

    private func userMessageID(in properties: [String: Any]) -> String? {
        string(in: properties, key: "messageID")
            ?? string(in: properties, key: "id")
            ?? string(in: properties, key: "inboxID")
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
        if type.hasSuffix("tool.input.started") || type.hasSuffix("tool.input.ended") {
            status = "pending"
        } else if type.hasSuffix("tool.success") {
            status = "completed"
        } else if type.hasSuffix("tool.failed") {
            status = "error"
        } else {
            status = "running"
        }
        let name = string(in: properties, key: "tool") ?? string(in: properties, key: "name") ?? "tool"
        let callID = string(in: properties, key: "callID")
        let partID = string(in: properties, key: "id") ?? callID
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
            partID: partID,
            toolName: name,
            status: status,
            callID: callID,
            input: properties["input"] as? [String: Any],
            output: output,
            error: error,
            metadata: properties["structured"] as? [String: Any]
        )
    }

    private func toolInputDelta(_ properties: [String: Any]) -> OpencodeEvent {
        var projected: [String: Any] = ["field": "input"]
        if let sessionID = sessionID(from: properties) {
            projected["sessionID"] = sessionID
        }
        if let messageID = string(in: properties, key: "assistantMessageID") {
            projected["messageID"] = messageID
        }
        if let partID = string(in: properties, key: "id") ?? string(in: properties, key: "callID") {
            projected["partID"] = partID
        }
        if let delta = string(in: properties, key: "delta") {
            projected["delta"] = delta
        }
        return event(type: "message.part.delta", properties: projected)
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
        if let start = int64(in: properties, key: "created") {
            state["time"] = ["start": start]
        }
        var part: [String: Any] = ["type": "tool", "tool": toolName, "state": state]
        if let partID { part["id"] = partID }
        if let sessionID = sessionID(from: properties) { part["sessionID"] = sessionID }
        if let messageID { part["messageID"] = messageID }
        if let callID { part["callID"] = callID }
        return event(type: "message.part.updated", properties: ["part": part])
    }

    private func reasoningStarted(_ properties: [String: Any]) -> [OpencodeEvent] {
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
            reasoningPartUpdated(properties, text: ""),
        ]
    }

    private func reasoningDelta(_ properties: [String: Any]) -> OpencodeEvent {
        var projected: [String: Any] = ["field": "reasoning"]
        if let sessionID = sessionID(from: properties) {
            projected["sessionID"] = sessionID
        }
        if let messageID = string(in: properties, key: "assistantMessageID") {
            projected["messageID"] = messageID
        }
        if let partID = reasoningPartID(in: properties) {
            projected["partID"] = partID
        }
        if let delta = string(in: properties, key: "delta") {
            projected["delta"] = delta
        }
        return event(type: "message.part.delta", properties: projected)
    }

    private func reasoningPartUpdated(_ properties: [String: Any], text: String? = nil) -> OpencodeEvent {
        var part: [String: Any] = ["type": "reasoning", "text": text ?? string(in: properties, key: "text") ?? ""]
        if let partID = reasoningPartID(in: properties) {
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
        if let form = properties["form"] as? [String: Any],
           let id = string(in: form, key: "sessionID") ?? string(in: form, key: "sessionId")
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

    private func reasoningPartID(in properties: [String: Any]) -> String? {
        if let reasoningID = string(in: properties, key: "reasoningID") {
            return reasoningID
        }
        if let id = string(in: properties, key: "id") {
            return id
        }
        if let ordinal = string(in: properties, key: "ordinal") {
            return "reasoning-\(ordinal)"
        }
        return nil
    }

    private func string(in object: [String: Any]?, key: String) -> String? {
        guard let object else { return nil }
        return string(in: object, key: key)
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

    private func int64(in object: [String: Any], key: String) -> Int64? {
        switch object[key] {
        case let value as Int64:
            value
        case let value as Int:
            Int64(value)
        case let value as NSNumber:
            value.int64Value
        case let value as Double:
            Int64(value)
        case let value as String:
            Int64(value)
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

    private static let wrapperKeys: Set<String> = [
        "payload",
        "type",
        "event",
        "id",
        "durable",
        "location",
        "metadata",
        "properties",
        "data",
        "directory",
        "project",
        "workspace",
    ]
}

nonisolated struct SessionTitleUpdate: Equatable, Sendable {
    let sessionID: String
    let title: String
}
