import Foundation

nonisolated struct EventDomainAdapter: Sendable {
    /// v2 compaction item id per session, remembered from `compaction.started`:
    /// `ended` / `failed` carry no id while the server updates the running item.
    private let runningCompactionIDs = RunningCompactionIDs()
    /// v2 shell item id per shell id, remembered from `shell.started`: `ended`
    /// carries only the shell while the server updates the item it started.
    private let runningShellMessageIDs = RunningCompactionIDs()

    func parseEvent(dataLine: String) -> OpencodeEvent? {
        parseEvents(dataLine: dataLine).first
    }

    func isServerConnectedFrame(_ dataLine: String) -> Bool {
        parseEvents(dataLine: dataLine).first?.type == "server.connected"
    }

    func parseEvents(dataLine: String) -> [OpencodeEvent] {
        if case .events(let events) = parse(dataLine) { return events } else { return [] }
    }

    func parse(_ dataLine: String) -> EventParseOutcome {
        guard let json = jsonObject(dataLine) else { return .unrecognized }

        let payload = json["payload"] as? [String: Any]
        let dataProperties = properties(fromData: json["data"])
        guard let type = eventType(json: json, payload: payload, dataProperties: dataProperties) else {
            return .unrecognized
        }

        let eventProperties = withEnvelopeCreated(
            resolveProperties(payload: payload, raw: json, dataProperties: dataProperties),
            raw: json
        )
        if canonicalEventType(type) == "sync" {
            guard let syncEvent = payload?["syncEvent"] as? [String: Any]
                ?? eventProperties["syncEvent"] as? [String: Any]
            else {
                return .unrecognized
            }
            guard let innerType = string(in: syncEvent, key: "type") else {
                return .unrecognized
            }
            let data = properties(fromData: syncEvent["data"]) ?? [:]
            let envelopeID = string(in: syncEvent, key: "id") ?? string(in: payload, key: "id") ?? json["id"] as? String
            let stamped = stampEnvelopeId(
                project(type: canonicalEventType(innerType), properties: data, envelopeID: envelopeID),
                id: envelopeID
            )
            return stamped.isEmpty
                ? .dropped(type: canonicalEventType(innerType))
                : .events(stamped)
        }
        let envelopeID = string(in: payload, key: "id") ?? json["id"] as? String
        let stamped = stampEnvelopeId(
            project(type: canonicalEventType(type), properties: eventProperties, envelopeID: envelopeID),
            id: envelopeID
        )
        return stamped.isEmpty
            ? .dropped(type: canonicalEventType(type))
            : .events(stamped)
    }

    private func jsonObject(_ dataLine: String) -> [String: Any]? {
        guard let data = dataLine.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    private func eventType(
        json: [String: Any],
        payload: [String: Any]?,
        dataProperties: [String: Any]?
    ) -> String? {
        string(in: payload, key: "type")
            ?? json["type"] as? String
            ?? json["event"] as? String
            ?? dataProperties.flatMap { string(in: $0, key: "type") }
    }

    private func stampEnvelopeId(_ events: [OpencodeEvent], id: String?) -> [OpencodeEvent] {
        guard let id, !id.isEmpty else { return events }
        return events.enumerated().map { index, event in
            guard event.id == nil else { return event }
            return OpencodeEvent(
                type: event.type,
                sessionID: event.sessionID,
                rawProperties: event.rawProperties,
                id: events.count == 1 ? id : "\(id):\(index)"
            )
        }
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

    private func project(type: String, properties: [String: Any], envelopeID: String? = nil) -> [OpencodeEvent] {
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
        case "session.next.prompt.admitted", "session.inbox.enqueued",
             "session.inbox.delivery.changed":
            // A `synthetic` item is server-injected context (a finished shell command's
            // output, Plan mode's reminder), not a prompt the user queued: no pending chrome.
            userPromptEvents(properties, waiting: !isSyntheticInboxItem(properties))
        case "session.next.prompted", "session.inbox.delivered":
            userPromptEvents(properties, waiting: false)
        case "session.inbox.cancelled":
            [inboxCancelled(properties)]
        case "session.next.step.started", "session.step.started":
            stepStarted(properties)
        case "session.execution.started":
            [statusEvent(properties, statusType: "busy")]
        case "session.next.step.ended", "session.step.ended":
            [assistantMessageUpdated(properties, finish: string(in: properties, key: "finish"))].compactMap { $0 }
        case "session.execution.succeeded",
             "session.execution.interrupted":
            [event(type: "session.idle", properties: properties)]
        case "session.next.step.failed", "session.execution.failed":
            failedStepEvents(properties, idle: true)
        case "session.step.failed":
            failedStepEvents(properties, idle: false)
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
            if let shell = properties["shell"] as? [String: Any] {
                shellItemStarted(properties, shell: shell, envelopeID: envelopeID)
            } else {
                [
                    statusEvent(properties, statusType: "busy"),
                    shellPartUpdated(properties, status: "running"),
                ]
            }
        case "session.next.shell.ended", "session.shell.ended":
            if let shell = properties["shell"] as? [String: Any] {
                shellItemEnded(properties, shell: shell)
            } else {
                [shellPartUpdated(properties, status: "completed")]
            }
        case "session.next.compaction.started", "session.compaction.started":
            [statusEvent(properties, statusType: "busy")]
                + compactionUpdated(properties, envelopeID: envelopeID, phase: .started)
        case "session.next.compaction.delta", "session.compaction.delta":
            // The summary streams as model context; the timeline never shows it as text.
            []
        case "session.next.compaction.ended", "session.compaction.ended":
            compactionUpdated(properties, envelopeID: envelopeID, phase: .ended)
        case "session.next.compaction.failed", "session.compaction.failed":
            failedCompactionEvents(properties, envelopeID: envelopeID)
        case "session.revert.committed":
            [revertCommitted(properties)].compactMap { $0 }
        case "session.next.reasoning.started", "session.reasoning.started":
            reasoningStarted(properties)
        case "session.next.reasoning.delta", "session.reasoning.delta":
            [reasoningDelta(properties)]
        case "session.next.reasoning.ended", "session.reasoning.ended":
            [reasoningPartUpdated(properties, completed: true)]
        case "session.next.text.started", "session.text.started":
            textStarted(properties)
        case "session.next.text.delta", "session.text.delta":
            [textDelta(properties)]
        case "session.next.text.ended", "session.text.ended":
            [textPartUpdated(properties)]
        case "session.next.moved", "session.moved":
            [event(type: "session.updated", properties: properties)]
                + noticeEvents(.location, properties: properties, envelopeID: envelopeID)
        case "session.next.agent.switched", "session.agent.selected":
            [event(type: "session.updated", properties: properties), messageContext(properties)].compactMap { $0 }
                + noticeEvents(.agent, properties: properties, envelopeID: envelopeID)
        case "session.next.model.switched", "session.model.selected":
            [event(type: "session.updated", properties: properties), messageContext(properties)].compactMap { $0 }
                + noticeEvents(.model, properties: properties, envelopeID: envelopeID)
        case "session.instructions.updated":
            [event(type: type, properties: properties)]
                + noticeEvents(.instructions, properties: properties, envelopeID: envelopeID)
        case "session.synthetic":
            [event(type: type, properties: properties)]
                + noticeEvents(.synthetic, properties: properties, envelopeID: envelopeID)
        case "session.renamed":
            [renamedToUpdated(properties)]
        case "session.forked":
            [forkedToCreated(properties)]
        case "session.usage.updated", "session.next.usage.updated":
            [usageUpdated(properties)].compactMap { $0 }
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

    private func usageUpdated(_ properties: [String: Any]) -> OpencodeEvent? {
        guard let assistantID = string(in: properties, key: "assistantMessageID") ?? string(in: properties, key: "messageID") else {
            return nil
        }
        var info: [String: Any] = ["id": assistantID, "role": "assistant"]
        if let sessionID = sessionID(from: properties) {
            info["sessionID"] = sessionID
        }
        if let tokens = properties["tokens"] {
            info["tokens"] = tokens
        }
        if let cost = properties["cost"] {
            info["cost"] = cost
        }
        return event(type: "message.updated", properties: ["info": info])
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

    /// v2 announces a fork only as `session.forked`, whose `parentID` is the fork source.
    /// A fork is a new top-level session, so it is projected as `session.created` without
    /// `parentID`, keeping it from reading as a subagent child.
    private func forkedToCreated(_ properties: [String: Any]) -> OpencodeEvent {
        var projected: [String: Any] = [:]
        if let sessionID = sessionID(from: properties) {
            projected["sessionID"] = sessionID
        }
        if let source = string(in: properties, key: "parentID") {
            projected["forkedFromSessionID"] = source
        }
        return event(type: "session.created", properties: projected)
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

    private func userPromptEvents(_ properties: [String: Any], waiting: Bool) -> [OpencodeEvent] {
        [userMessageUpdated(properties, waiting: waiting)] + userPromptParts(properties)
    }

    private func userMessageUpdated(_ properties: [String: Any], waiting: Bool) -> OpencodeEvent {
        let sessionID = sessionID(from: properties)
        var info: [String: Any] = ["role": "user", "waiting": waiting]
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
        if !isSyntheticInboxItem(properties), let delivery = deliveryValue(in: properties) {
            info["delivery"] = delivery
        }
        return event(type: "message.updated", properties: ["info": info])
    }

    private func userPromptParts(_ properties: [String: Any]) -> [OpencodeEvent] {
        let prompt = promptObject(in: properties)
        let sessionID = sessionID(from: properties)
        let messageID = userMessageID(in: properties)
        var parts: [OpencodeEvent] = []
        // v2 inbox `synthetic` items (e.g. Plan mode's system-reminder) are server-injected
        // context, not something the user typed: keep the part but mark it hidden.
        let isSynthetic = (properties["item"] as? [String: Any]).flatMap { string(in: $0, key: "type") } == "synthetic"
        if let text = prompt.flatMap({ string(in: $0, key: "text") }) ?? string(in: properties, key: "text"),
           !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        {
            parts.append(
                userTextPartUpdated(sessionID: sessionID, messageID: messageID, text: text, isSynthetic: isSynthetic)
            )
        }
        guard let prompt else { return parts }
        let files = prompt["files"] as? [Any] ?? []
        for (index, element) in files.enumerated() {
            guard let file = element as? [String: Any] else { continue }
            parts.append(userFilePartUpdated(sessionID: sessionID, messageID: messageID, index: index, file: file))
        }
        return parts
    }

    private func userTextPartUpdated(
        sessionID: String?,
        messageID: String?,
        text: String,
        isSynthetic: Bool = false
    ) -> OpencodeEvent {
        var part: [String: Any] = ["type": "text", "text": text]
        if isSynthetic {
            part["synthetic"] = true
        }
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
        let mime = string(in: file, key: "mime")
            ?? string(in: file, key: "uri").flatMap(AttachmentDataURL.mime(fromDataURL:))
            ?? string(in: file, key: "name").flatMap(AttachmentDataURL.mime(fromFilename:))
        if let uri = string(in: file, key: "uri") {
            part["url"] = uri
        } else if let data = string(in: file, key: "data"), let mime {
            // Inline attachment (a sent photo): same data URL the REST mapping builds.
            part["url"] = "data:\(mime.lowercased());base64,\(data)"
        }
        if let mime {
            part["mime"] = mime
        }
        if let name = string(in: file, key: "name") {
            part["filename"] = name
        }
        return event(type: "message.part.updated", properties: ["part": part])
    }

    private func deliveryValue(in properties: [String: Any]) -> String? {
        if let delivery = string(in: properties, key: "delivery") {
            return delivery
        }
        guard let item = properties["item"] as? [String: Any] else { return nil }
        return string(in: item, key: "delivery")
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
            assistantMessageUpdated(properties, stampCreated: true, withAgentAndModel: true),
            statusEvent(properties, statusType: "busy"),
        ].compactMap { $0 }
    }

    /// A v2 agent or model switch, for the message store: prompts delivered after it are
    /// recorded with it (`message.context` is client-only).
    private func messageContext(_ properties: [String: Any]) -> OpencodeEvent? {
        let agent = string(in: properties, key: "agent")
        let model = properties["model"] as? [String: Any]
        guard agent != nil || model != nil else { return nil }
        var context: [String: Any] = [:]
        if let sessionID = sessionID(from: properties) { context["sessionID"] = sessionID }
        if let agent { context["agent"] = agent }
        if let model { context["model"] = model }
        if let created = int64(in: properties, key: "created") { context["created"] = created }
        return event(type: Self.messageContextEvent, properties: context)
    }

    /// v2 has no assistant `message.updated`; synthesize one. Only `step.started`
    /// stamps `time.created` (the envelope time) so a live reply orders after the
    /// prompt it answers; later partial infos omit `time` and the store keeps it.
    /// An aborted step projects as `finish: "aborted"` so the reply keeps an Interrupted note.
    private func failedStepFinish(_ properties: [String: Any]) -> String {
        let type = (properties["error"] as? [String: Any])?["type"] as? String
        return ToolInterruption.isAbortedType(type) ? "aborted" : "error"
    }

    /// A structured compaction error with no id has no timeline row. Toast it, the same way a
    /// step failure toasts when the assistant row cannot be created.
    private func failedCompactionEvents(_ properties: [String: Any], envelopeID: String?) -> [OpencodeEvent] {
        let updated = compactionUpdated(properties, envelopeID: envelopeID, phase: .failed)
        let toast = updated.isEmpty || structuredErrorInfo(properties) == nil
            ? event(type: "session.error", properties: properties)
            : nil
        return updated + [toast, event(type: "session.idle", properties: properties)].compactMap { $0 }
    }

    private func failedStepEvents(_ properties: [String: Any], idle: Bool) -> [OpencodeEvent] {
        let error = structuredErrorInfo(properties)
        let updated = assistantMessageUpdated(properties, finish: failedStepFinish(properties), error: error)
        return [
            updated,
            (updated == nil || error == nil) ? event(type: "session.error", properties: properties) : nil,
            idle ? event(type: "session.idle", properties: properties) : nil,
        ].compactMap { $0 }
    }

    /// v2 structured error translated into the v1 `{name, data}` shape the message store already decodes.
    private func structuredErrorInfo(_ properties: [String: Any]) -> [String: Any]? {
        guard let error = properties["error"] as? [String: Any] else { return nil }
        let type = string(in: error, key: "type")
        if SessionFailure.isSilent(type: type) { return nil }
        let message = SessionFailure.unwrap(string(in: error, key: "message"))
        let body = (error["response"] as? [String: Any]).flatMap { string(in: $0, key: "body") }
        let status = int64(in: error, key: "status").map { Int($0) }
        if message.isEmpty && status == nil && (body?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true)
            && (type?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true)
        {
            return nil
        }
        var data: [String: Any] = [:]
        if !message.isEmpty { data["message"] = message }
        if let status { data["statusCode"] = status }
        if let body, !body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            data["responseBody"] = body
        }
        var info: [String: Any] = ["data": data]
        if let type { info["name"] = type }
        return info
    }

    private func assistantMessageUpdated(
        _ properties: [String: Any],
        stampCreated: Bool = false,
        finish: String? = nil,
        withAgentAndModel: Bool = false,
        error: [String: Any]? = nil
    ) -> OpencodeEvent? {
        guard let assistantID = string(in: properties, key: "assistantMessageID") ?? string(in: properties, key: "messageID") else {
            return nil
        }
        var info: [String: Any] = ["id": assistantID, "role": "assistant"]
        if let sessionID = sessionID(from: properties) {
            info["sessionID"] = sessionID
        }
        if let finish { info["finish"] = finish }
        if stampCreated, let created = int64(in: properties, key: "created") {
            info["time"] = ["created": created]
        }
        // `step.started` carries the agent and model the step runs with.
        if withAgentAndModel {
            if let agent = string(in: properties, key: "agent") {
                info["agent"] = agent
                info["mode"] = agent
            }
            if let model = properties["model"] as? [String: Any] {
                if let id = string(in: model, key: "id") { info["modelID"] = id }
                if let providerID = string(in: model, key: "providerID") { info["providerID"] = providerID }
                if let variant = string(in: model, key: "variant") { info["variant"] = variant }
            }
        }
        if let error {
            info["error"] = error
        } else if let finish, finish.lowercased() != "error" {
            // A later successful or aborted step must drop a failure kept from a partial info.
            info["error"] = NSNull()
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
        let name = resolvedToolName(properties)
        let callID = string(in: properties, key: "callID")
        let partID = string(in: properties, key: "id") ?? callID
        var output = string(in: properties, key: "output")
        if output == nil, let content = properties["content"] as? [[String: Any]] {
            let texts = content.compactMap { $0["text"] as? String }
            if !texts.isEmpty {
                output = texts.joined()
            }
        }
        let errorObject = properties["error"] as? [String: Any]
        let unwrapped = SessionFailure.unwrap(errorObject?["message"] as? String)
        let error = unwrapped.isEmpty ? nil : unwrapped
        let interrupted = status == "error" && ToolInterruption.isAbortedType(errorObject?["type"] as? String)
        let malformed = SessionFailure.isMalformedTool(
            type: errorObject?["type"] as? String,
            executed: boolValue(properties["executed"])
        )
        let metadata = toolMetadata(properties)
        // v2 `tool.progress` carries the live title in metadata (v1: state.title).
        let title = (metadata?["title"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        return toolLikePartUpdated(
            properties: properties,
            messageID: string(in: properties, key: "assistantMessageID"),
            partID: partID,
            toolName: name,
            status: status,
            callID: callID,
            input: objectInput(properties["input"]),
            output: output,
            error: error,
            metadata: metadata,
            title: title,
            interrupted: interrupted,
            malformed: malformed
        )
    }

    /// v2 tool events carry tool-reported metadata (a subagent's `sessionID`
    /// arrives on `tool.progress` while it is still running) under `metadata`;
    /// older betas used `structured`. Merge both so neither shape drops it.
    private func toolMetadata(_ properties: [String: Any]) -> [String: Any]? {
        let structured = properties["structured"] as? [String: Any]
        let metadata = properties["metadata"] as? [String: Any]
        switch (structured, metadata) {
        case (nil, nil): return nil
        case (let structured?, nil): return structured
        case (nil, let metadata?): return metadata
        case (let structured?, let metadata?): return structured.merging(metadata) { _, new in new }
        }
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

    private func isSyntheticInboxItem(_ properties: [String: Any]) -> Bool {
        (properties["item"] as? [String: Any]).flatMap { string(in: $0, key: "type") } == "synthetic"
    }

    /// A v2 shell run (`{shell: Info}`) is its own transcript item whose id is the
    /// `started` envelope id with `evt_` swapped for `msg_`. It projects like the
    /// REST copy: the command as a user message, then a Shell row under
    /// `<id>:assistant`, so a history load replaces both in place. The shell runs
    /// beside the agent loop, so it leaves the session status alone.
    private func shellItemStarted(_ properties: [String: Any], shell: [String: Any], envelopeID: String?) -> [OpencodeEvent] {
        guard let envelopeID, !envelopeID.isEmpty else { return [] }
        let messageID = envelopeID.hasPrefix("evt_") ? "msg_" + String(envelopeID.dropFirst("evt_".count)) : envelopeID
        if let shellID = string(in: shell, key: "id") {
            runningShellMessageIDs.set(messageID, for: shellID)
        }
        let created = (shell["time"] as? [String: Any]).flatMap { int64(in: $0, key: "started") }
            ?? int64(in: properties, key: "created")
        return shellItemEvents(properties, shell: shell, messageID: messageID, created: created, completed: nil, output: nil)
    }

    private func shellItemEnded(_ properties: [String: Any], shell: [String: Any]) -> [OpencodeEvent] {
        guard let shellID = string(in: shell, key: "id"),
              let messageID = runningShellMessageIDs.id(for: shellID)
        else { return [] }
        runningShellMessageIDs.set(nil, for: shellID)
        let time = shell["time"] as? [String: Any]
        let output = (properties["output"] as? [String: Any]).flatMap { string(in: $0, key: "output") }
            ?? string(in: properties, key: "output")
        return shellItemEvents(
            properties,
            shell: shell,
            messageID: messageID,
            created: time.flatMap { int64(in: $0, key: "started") },
            completed: time.flatMap { int64(in: $0, key: "completed") } ?? int64(in: properties, key: "created"),
            output: output
        )
    }

    private func shellItemEvents(
        _ properties: [String: Any],
        shell: [String: Any],
        messageID: String,
        created: Int64?,
        completed: Int64?,
        output: String?
    ) -> [OpencodeEvent] {
        let sessionID = sessionID(from: properties)
        let command = string(in: shell, key: "command") ?? ""
        var time: [String: Any] = [:]
        if let created { time["created"] = created }
        if let completed { time["completed"] = completed }
        func info(id: String, role: String) -> [String: Any] {
            var info: [String: Any] = ["id": id, "role": role]
            if let sessionID { info["sessionID"] = sessionID }
            if !time.isEmpty { info["time"] = time }
            return info
        }
        let assistantID = "\(messageID):assistant"
        var state: [String: Any] = ["status": completed == nil ? "running" : "completed", "title": "Shell"]
        if !command.isEmpty { state["input"] = ["command": command] }
        if let output { state["output"] = output }
        if let exit = int64(in: shell, key: "exit") { state["metadata"] = ["exit": Int(exit)] }
        if let created { state["time"] = ["start": created] }
        var tool: [String: Any] = [
            "id": "\(messageID):tool",
            "messageID": assistantID,
            "type": "tool",
            "tool": "bash",
            "state": state,
        ]
        if let sessionID { tool["sessionID"] = sessionID }
        var events = [event(type: "message.updated", properties: ["info": info(id: messageID, role: "user")])]
        if !command.isEmpty {
            events.append(userTextPartUpdated(sessionID: sessionID, messageID: messageID, text: command))
        }
        events.append(event(type: "message.updated", properties: ["info": info(id: assistantID, role: "assistant")]))
        events.append(event(type: "message.part.updated", properties: ["part": tool]))
        return events
    }

    private enum CompactionPhase { case started, ended, failed }

    private enum NoticeSource { case location, agent, model, instructions, synthetic }

    /// The timeline notice the server appends for a v2 move, switch, instruction update
    /// or restart. Its message id is the envelope id with `evt_` swapped for `msg_`
    /// (`SessionMessage.ID.fromEvent`), so the REST copy replaces this row in place.
    /// Live events may omit `previous`; the REST copy fills it in.
    private func noticeEvents(
        _ source: NoticeSource,
        properties: [String: Any],
        envelopeID: String?
    ) -> [OpencodeEvent] {
        guard let envelopeID, !envelopeID.isEmpty, let sessionID = sessionID(from: properties) else { return [] }
        let messageID = envelopeID.hasPrefix("evt_") ? "msg_" + String(envelopeID.dropFirst("evt_".count)) : envelopeID
        var notice: [String: Any]
        switch source {
        case .location:
            guard let location = properties["location"] as? [String: Any],
                  let directory = string(in: location, key: "directory")
            else { return [] }
            notice = ["type": "location-switched", "location": ["directory": directory]]
        case .agent:
            guard let agent = string(in: properties, key: "agent") else { return [] }
            notice = ["type": "agent-switched", "agent": agent]
            if let previous = string(in: properties, key: "previous") { notice["previous"] = previous }
        case .model:
            guard let model = properties["model"] as? [String: Any] else { return [] }
            notice = ["type": "model-switched", "model": model]
            if let previous = properties["previous"] as? [String: Any] { notice["previous"] = previous }
        case .instructions:
            // The initial baseline and empty deltas carry no text and append no message.
            guard properties["text"] is String else { return [] }
            let sources = ((properties["delta"] as? [String: Any])?.keys).map { Array($0).sorted() } ?? []
            notice = [
                "type": "system",
                "description": MessageDomainAdapter.instructionsDescriptionPrefix + sources.joined(separator: ", "),
                "metadata": ["notice": "instructions", "instructionSources": sources],
            ]
        case .synthetic:
            // Only the restart synthetic is a notice; other synthetic items stay as they are.
            let metadata = properties["metadata"] as? [String: Any]
            let description = string(in: properties, key: "description")
            guard string(in: metadata, key: "notice") == "restart"
                || description == MessageDomainAdapter.restartDescription
            else { return [] }
            notice = ["type": "synthetic", "metadata": ["notice": "restart"]]
            if let description { notice["description"] = description }
        }
        var info: [String: Any] = ["id": messageID, "role": "notice", "sessionID": sessionID]
        if let created = int64(in: properties, key: "created") {
            info["time"] = ["created": created, "completed": created]
        }
        let part: [String: Any] = [
            "id": messageID,
            "messageID": messageID,
            "sessionID": sessionID,
            "type": "notice",
            "notice": notice,
        ]
        return [
            event(type: "message.updated", properties: ["info": info]),
            event(type: "message.part.updated", properties: ["part": part]),
        ]
    }

    /// v2 compaction is its own transcript item (`type: compaction`). Its id is
    /// `inputID` or the `started` envelope id with `evt_` swapped for `msg_`; a
    /// finished one without a remembered start uses its own envelope id, as the
    /// server does when it appends a completed item.
    private func compactionUpdated(
        _ properties: [String: Any],
        envelopeID: String?,
        phase: CompactionPhase
    ) -> [OpencodeEvent] {
        let sessionID = sessionID(from: properties)
        let fromEnvelope = envelopeID.map { id -> String in
            id.hasPrefix("evt_") ? "msg_" + String(id.dropFirst("evt_".count)) : id
        }
        let explicit = string(in: properties, key: "inputID") ?? string(in: properties, key: "messageID")
        let resolved: String?
        switch phase {
        case .started:
            resolved = explicit ?? fromEnvelope
        case .ended, .failed:
            resolved = explicit ?? sessionID.flatMap { runningCompactionIDs.id(for: $0) } ?? fromEnvelope
        }
        guard let messageID = resolved else { return [] }
        if let sessionID {
            runningCompactionIDs.set(phase == .started ? messageID : nil, for: sessionID)
        }
        var info: [String: Any] = ["id": messageID, "role": "assistant", "mode": "compaction"]
        var part: [String: Any] = [
            "id": messageID,
            "messageID": messageID,
            "type": "compaction",
            "auto": string(in: properties, key: "reason") == "auto",
            "completed": phase != .started,
        ]
        if let sessionID {
            info["sessionID"] = sessionID
            part["sessionID"] = sessionID
        }
        if phase == .failed, let error = structuredErrorInfo(properties) {
            info["error"] = error
        } else if phase == .ended {
            // An omitted error would keep a failed caption when a retry reuses this id.
            info["error"] = NSNull()
        }
        if phase == .started, let created = int64(in: properties, key: "created") {
            info["time"] = ["created": created]
        }
        if phase == .ended, let summary = string(in: properties, key: "text"),
           !summary.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        {
            part["summary"] = summary
        }
        return [
            event(type: "message.updated", properties: ["info": info]),
            event(type: "message.part.updated", properties: ["part": part]),
        ]
    }

    /// v2 announces a committed revert only as `session.revert.committed {to}`:
    /// the server dropped `to` and every later message, with no per-message
    /// `message.removed` (v1 sends those instead).
    private func revertCommitted(_ properties: [String: Any]) -> OpencodeEvent? {
        guard let messageID = string(in: properties, key: "to") ?? string(in: properties, key: "messageID") else {
            return nil
        }
        var projected: [String: Any] = ["messageID": messageID]
        if let sessionID = sessionID(from: properties) {
            projected["sessionID"] = sessionID
        }
        return event(type: "session.revert.committed", properties: projected)
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
        title: String? = nil,
        interrupted: Bool = false,
        malformed: Bool = false
    ) -> OpencodeEvent {
        var state: [String: Any] = ["status": status]
        if interrupted { state["interrupted"] = true }
        if malformed { state["malformed"] = true }
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
        let updated: OpencodeEvent?
        if let assistantID = string(in: properties, key: "assistantMessageID") {
            var info: [String: Any] = ["id": assistantID, "role": "assistant"]
            if let sessionID {
                info["sessionID"] = sessionID
            }
            updated = event(type: "message.updated", properties: ["info": info])
        } else {
            updated = nil
        }
        return [
            updated,
            reasoningPartUpdated(properties, text: ""),
        ].compactMap { $0 }
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

    private func reasoningPartUpdated(
        _ properties: [String: Any],
        text: String? = nil,
        completed: Bool = false
    ) -> OpencodeEvent {
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
        if let created = int64(in: properties, key: "created") {
            var time: [String: Any] = [:]
            if completed {
                time["end"] = created
            } else {
                time["start"] = created
            }
            part["time"] = time
        }
        return event(type: "message.part.updated", properties: ["part": part])
    }

    private func textStarted(_ properties: [String: Any]) -> [OpencodeEvent] {
        let sessionID = sessionID(from: properties)
        let updated: OpencodeEvent?
        if let assistantID = string(in: properties, key: "assistantMessageID") {
            var info: [String: Any] = ["id": assistantID, "role": "assistant"]
            if let sessionID {
                info["sessionID"] = sessionID
            }
            updated = event(type: "message.updated", properties: ["info": info])
        } else {
            updated = nil
        }
        return [
            updated,
            textPartUpdated(properties, text: ""),
        ].compactMap { $0 }
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
        var status: [String: Any] = ["type": statusType]
        let nested = properties["status"] as? [String: Any]
        if let attempt = int64(in: properties, key: "attempt")
            ?? nested.flatMap({ int64(in: $0, key: "attempt") })
        {
            status["attempt"] = attempt
        }
        if let nextAt = nested.flatMap({ int64(in: $0, key: "at") })
            ?? nested.flatMap({ int64(in: $0, key: "next") })
            ?? nested.flatMap({ int64(in: $0, key: "nextAt") })
            ?? nested.flatMap({ int64(in: $0, key: "nextAtMs") })
            ?? int64(in: properties, key: "at")
            ?? int64(in: properties, key: "next")
            ?? int64(in: properties, key: "nextAt")
            ?? int64(in: properties, key: "nextAtMs")
        {
            status["nextAtMs"] = nextAt
        }
        let nestedError = (properties["error"] as? [String: Any]).flatMap { string(in: $0, key: "message") }
            ?? nested.flatMap { ($0["error"] as? [String: Any]).flatMap { string(in: $0, key: "message") } }
        if let message = nested.flatMap({ string(in: $0, key: "message") })
            ?? string(in: properties, key: "message")
        {
            status["message"] = message
        } else if let nestedError {
            let unwrapped = SessionFailure.unwrap(nestedError)
            if !unwrapped.isEmpty { status["message"] = unwrapped }
        }
        if statusType == "retry" {
            if let errorType = retryErrorType(properties) { status["errorType"] = errorType }
            if let label = retryActionLabel(properties) { status["actionLabel"] = label }
            if let link = retryActionLink(properties) { status["actionLink"] = link }
        }
        var projected: [String: Any] = ["status": status]
        if let sessionID = sessionID(from: properties) {
            projected["sessionID"] = sessionID
        }
        return event(type: "session.status", properties: projected)
    }

    private func retryErrorType(_ properties: [String: Any]) -> String? {
        let status = properties["status"] as? [String: Any]
        let error = (properties["error"] as? [String: Any]) ?? (status?["error"] as? [String: Any])
        return string(in: error, key: "type")
            ?? string(in: status, key: "errorType")
            ?? string(in: properties, key: "errorType")
    }

    private func retryActionObject(_ properties: [String: Any]) -> [String: Any]? {
        let status = properties["status"] as? [String: Any]
        return (status?["action"] as? [String: Any]) ?? (properties["action"] as? [String: Any])
    }

    private func retryActionLabel(_ properties: [String: Any]) -> String? {
        let label = string(in: retryActionObject(properties), key: "label")?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return label?.isEmpty == false ? label : nil
    }

    private func retryActionLink(_ properties: [String: Any]) -> String? {
        let link = string(in: retryActionObject(properties), key: "link")?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return link?.isEmpty == false ? link : nil
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
        return ordinalPartID(in: properties, kind: "text")
    }

    private func reasoningPartID(in properties: [String: Any]) -> String? {
        if let reasoningID = string(in: properties, key: "reasoningID") {
            return reasoningID
        }
        if let id = string(in: properties, key: "id") {
            return id
        }
        return ordinalPartID(in: properties, kind: "reasoning")
    }

    /// v2 text/reasoning events carry no part id, only an `ordinal` that restarts
    /// at 0 in every assistant message. Scope it to the message so step texts of
    /// one turn stay distinct, matching `MessageDomainAdapter`'s REST ids.
    private func ordinalPartID(in properties: [String: Any], kind: String) -> String? {
        guard let ordinal = string(in: properties, key: "ordinal") else { return nil }
        guard let messageID = string(in: properties, key: "assistantMessageID") else {
            return "\(kind)-\(ordinal)"
        }
        return V2ContentPartID.make(messageID: messageID, kind: kind, ordinal: ordinal)
    }

    private func resolvedToolName(_ properties: [String: Any]) -> String {
        ToolPartIdentity.resolvedName(
            explicit: string(in: properties, key: "tool") ?? string(in: properties, key: "name"),
            input: objectInput(properties["input"])
        )
    }

    private func objectInput(_ value: Any?) -> [String: Any]? {
        if let object = value as? [String: Any] { return object }
        guard let raw = value as? String,
              let data = raw.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return nil }
        return object
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

    private func boolValue(_ value: Any?) -> Bool? {
        switch value {
        case let value as Bool:
            value
        case let value as NSNumber:
            value.boolValue
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
        SessionActivity(raw: status.raw).statusLabel
    }

    func sessionActivity(from status: SessionStatus) -> SessionActivity {
        SessionActivity(raw: status.raw)
    }

    /// Activity carried by a `session.status` SSE payload.
    func sessionActivity(from event: OpencodeEvent) -> (sessionID: String, activity: SessionActivity)? {
        guard let status = sessionStatus(from: event) else { return nil }
        return (status.sessionID, SessionActivity(raw: status.raw))
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

    func isCompletedAssistantMessage(_ event: OpencodeEvent) -> Bool {
        guard event.type == "message.updated" else { return false }
        let info = event.rawProperties?["info"]?.dictionaryValue
        guard info?["role"]?.stringValue == "assistant" else { return false }
        guard let completed = info?["time"]?.dictionaryValue?["completed"] else { return false }
        return completed != .null
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

    /// Pending-revert change. v1 `session.updated` carries the whole session, so an absent
    /// `info.revert` means none; v2 stages, clears and commits with dedicated events. A partial
    /// `info` (the v2 `session.renamed` projection) says nothing about the revert and is ignored.
    func sessionRevertUpdate(from event: OpencodeEvent) -> SessionRevertUpdate? {
        switch event.type {
        case "session.updated":
            guard let info = event.rawProperties?["info"]?.dictionaryValue, info["time"] != nil else { return nil }
            guard let sessionID = event.sessionID ?? info["id"]?.stringValue else { return nil }
            let messageID = info["revert"]?.dictionaryValue?["messageID"]?.stringValue
            return SessionRevertUpdate(sessionID: sessionID, messageID: messageID)
        case "session.revert.staged":
            guard let sessionID = event.sessionID,
                  let messageID = event.rawProperties?["revert"]?.dictionaryValue?["messageID"]?.stringValue
            else { return nil }
            return SessionRevertUpdate(sessionID: sessionID, messageID: messageID)
        case "session.revert.cleared", "session.revert.committed":
            guard let sessionID = event.sessionID else { return nil }
            return SessionRevertUpdate(sessionID: sessionID, messageID: nil)
        default:
            return nil
        }
    }

    /// Session removed on the server. v1 carries the id only in `info.id`; v2 sends `sessionID`.
    func sessionDeletedID(from event: OpencodeEvent) -> String? {
        guard event.type == "session.deleted" else { return nil }
        let info = event.rawProperties?["info"]?.dictionaryValue
        return event.sessionID
            ?? info?["id"]?.stringValue
            ?? info?["sessionID"]?.stringValue
            ?? info?["sessionId"]?.stringValue
    }

    /// Viewed watermark carried by a v2 `session.viewed` event (`{ sessionID, idle }`).
    /// Returns nil when the event is not a usable viewed update.
    func sessionViewed(_ event: OpencodeEvent) -> SessionViewedUpdate? {
        guard event.type == OpencodeEvent.sessionViewedType else { return nil }
        guard let sessionID = event.sessionID, !sessionID.isEmpty else { return nil }
        guard let idle = event.rawProperties?["idle"]?.int64Value else { return nil }
        return SessionViewedUpdate(sessionID: sessionID, idleAtEpochMs: idle)
    }

    /// Client-only projection of a v2 agent/model switch for the message store.
    static let messageContextEvent = "message.context"

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

nonisolated enum EventParseOutcome: Sendable {
    /// Frame could not be decoded or no event type could be resolved. Possible lost event.
    case unrecognized
    /// Frame was recognized but intentionally projected to no domain event.
    case dropped(type: String)
    case events([OpencodeEvent])
}

nonisolated struct SessionViewedUpdate: Equatable, Sendable {
    let sessionID: String
    let idleAtEpochMs: Int64
}

nonisolated struct SessionTitleUpdate: Equatable, Sendable {
    let sessionID: String
    let title: String
}

/// Pending revert of a session after an event: `messageID` is the first reverted message, nil
/// when no revert is pending any more (cleared, or committed and already truncated).
nonisolated struct SessionRevertUpdate: Equatable, Sendable {
    let sessionID: String
    let messageID: String?
}

/// Session → running v2 compaction item id, shared by copies of the adapter.
nonisolated final class RunningCompactionIDs: @unchecked Sendable {
    private let lock = NSLock()
    private var ids: [String: String] = [:]

    func id(for sessionID: String) -> String? {
        lock.lock()
        defer { lock.unlock() }
        return ids[sessionID]
    }

    func set(_ id: String?, for sessionID: String) {
        lock.lock()
        defer { lock.unlock() }
        ids[sessionID] = id
    }
}
