import Foundation

enum T3RpcFrame: Equatable, @unchecked Sendable {
    case request(id: String, tag: String, payload: [String: Any])
    case exitSuccess(requestID: String, value: Any?)
    case exitFailure(requestID: String, message: String)
    case chunk(requestID: String, values: [Any])
    case ack(requestID: String)
    case ping
    case pong
    case unknown

    static func == (lhs: T3RpcFrame, rhs: T3RpcFrame) -> Bool {
        switch (lhs, rhs) {
        case let (.request(leftID, leftTag, _), .request(rightID, rightTag, _)):
            leftID == rightID && leftTag == rightTag
        case let (.exitSuccess(leftID, _), .exitSuccess(rightID, _)):
            leftID == rightID
        case let (.exitFailure(leftID, leftMessage), .exitFailure(rightID, rightMessage)):
            leftID == rightID && leftMessage == rightMessage
        case let (.chunk(leftID, leftValues), .chunk(rightID, rightValues)):
            leftID == rightID && leftValues.count == rightValues.count
        case let (.ack(leftID), .ack(rightID)):
            leftID == rightID
        case (.ping, .ping), (.pong, .pong), (.unknown, .unknown):
            true
        default:
            false
        }
    }
}

nonisolated enum T3RpcCodec {
    static func encodeRequest(id: String, tag: String, payload: [String: Any]) throws -> Data {
        let frame: [String: Any] = [
            "_tag": "Request",
            "id": id,
            "tag": tag,
            "payload": payload,
            "headers": [Any](),
        ]
        return try JSONSerialization.data(withJSONObject: frame)
    }

    static func encodeAck(requestID: String) throws -> Data {
        try JSONSerialization.data(withJSONObject: [
            "_tag": "Ack",
            "requestId": requestID,
        ])
    }

    static func encodePong() throws -> Data {
        try JSONSerialization.data(withJSONObject: ["_tag": "Pong"])
    }

    /// Client keepalive; the server answers with `Pong`.
    static func encodePing() throws -> Data {
        try JSONSerialization.data(withJSONObject: ["_tag": "Ping"])
    }

    /// Effect RPC `Interrupt`: cancels the server-side fiber for a streaming
    /// request so chunks stop being produced for an observer that went away.
    static func encodeInterrupt(requestID: String) throws -> Data {
        try JSONSerialization.data(withJSONObject: [
            "_tag": "Interrupt",
            "requestId": requestID,
            "interruptors": [Any](),
        ])
    }

    static func parseFrame(_ data: Data) throws -> T3RpcFrame {
        let json = try JSONSerialization.jsonObject(with: data)
        return parseFrame(json)
    }

    static func parseFrame(_ json: Any) -> T3RpcFrame {
        guard let object = T3JSON.object(json),
              let tag = T3JSON.string(object, "_tag")
        else { return .unknown }
        switch tag {
        case "Request":
            return .request(
                id: T3JSON.string(object, "id") ?? "",
                tag: T3JSON.string(object, "tag") ?? "",
                payload: T3JSON.object(object["payload"]) ?? [:]
            )
        case "Exit":
            let requestID = T3JSON.string(object, "requestId") ?? ""
            let exit = T3JSON.object(object["exit"]) ?? [:]
            switch T3JSON.string(exit, "_tag") {
            case "Success":
                return .exitSuccess(requestID: requestID, value: exit["value"])
            case "Failure":
                return .exitFailure(requestID: requestID, message: failureMessage(exit["cause"]))
            default:
                return .unknown
            }
        case "Chunk":
            return .chunk(
                requestID: T3JSON.string(object, "requestId") ?? "",
                values: T3JSON.array(object["values"])
            )
        case "Ack":
            return .ack(requestID: T3JSON.string(object, "requestId") ?? "")
        case "Ping":
            return .ping
        case "Pong":
            return .pong
        default:
            return .unknown
        }
    }

    static func websocketURL(httpBase: URL, ticket: String) -> URL? {
        var components = URLComponents(url: httpBase, resolvingAgainstBaseURL: false)
        switch components?.scheme?.lowercased() {
        case "https":
            components?.scheme = "wss"
        default:
            components?.scheme = "ws"
        }
        let trimmedPath = (components?.path ?? "").trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        components?.path = trimmedPath.isEmpty ? "/ws" : "/\(trimmedPath)/ws"
        components?.queryItems = [
            URLQueryItem(name: "wsTicket", value: ticket),
            URLQueryItem(name: "orchestrationProtocol", value: T3Auth.protocolVersion),
        ]
        return components?.url
    }

    static let subscribeShellTag = "orchestration.subscribeShell"
    static let subscribeArchivedShellTag = "orchestration.subscribeArchivedShell"
    static let archivedShellSnapshotTag = "orchestration.getArchivedShellSnapshot"

    /// `orchestration.subscribeShell` input. With `afterSequence` the server
    /// replays shell events after that sequence instead of sending the full
    /// snapshot again; without it the first item is a `snapshot`.
    static func subscribeShellPayload(afterSequence: Int?) -> [String: Any] {
        var payload: [String: Any] = ["requestCompletionMarker": true]
        if let afterSequence, afterSequence > 0 {
            payload["afterSequence"] = afterSequence
        }
        return payload
    }

    static func dispatchMode(isRunning: Bool) -> [String: Any] {
        ["type": isRunning ? "queue_after_active" : "start_immediately"]
    }

    static func modelSelectionPayload(_ selection: T3ModelSelection) -> [String: Any] {
        [
            "instanceId": selection.instanceID,
            "model": selection.model,
        ]
    }

    /// Temporary title for the first `message.dispatch`. The server shows this at
    /// once, then replaces it with a generated title. A seed only arms that
    /// generation while the thread still has no messages. Matches T3's
    /// `deriveThreadTitleSeed`: collapsed text, else `Image: <name>`, else
    /// "New thread", cut at 50 characters.
    static func titleSeed(text: String, attachmentName: String?) -> String {
        let normalized = collapseWhitespace(text)
        if !normalized.isEmpty { return truncateSeed(normalized) }
        if let name = attachmentName.map(collapseWhitespace), !name.isEmpty {
            return truncateSeed("Image: \(name)")
        }
        return "New thread"
    }

    static func messageDispatchCommand(
        commandID: String,
        threadID: String,
        messageID: String,
        text: String,
        selection: T3ModelSelection,
        isRunning: Bool,
        attachments: [T3Attachment] = [],
        titleSeed: String? = nil
    ) -> [String: Any] {
        var command: [String: Any] = [
            "type": "message.dispatch",
            // Required by `OrchestrationV2CreationFields`; the server rejects the
            // command at schema validation without them.
            "createdBy": "user",
            "creationSource": "mobile",
            "commandId": commandID,
            "threadId": threadID,
            "messageId": messageID,
            "text": text,
            "attachments": attachments.map(chatAttachmentPayload),
            "dispatchMode": dispatchMode(isRunning: isRunning),
            "modelSelection": modelSelectionPayload(selection),
        ]
        if let titleSeed, !titleSeed.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            command["titleSeed"] = titleSeed
        }
        return command
    }

    private static let titleSeedMaxLength = 50

    private static func collapseWhitespace(_ value: String) -> String {
        value.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func truncateSeed(_ value: String) -> String {
        guard value.count > titleSeedMaxLength else { return value }
        let end = value.index(value.startIndex, offsetBy: titleSeedMaxLength)
        return String(value[..<end]) + "..."
    }

    /// `ChatAttachment` as persisted by the server: a stored id plus its metadata.
    static func chatAttachmentPayload(_ attachment: T3Attachment) -> [String: Any] {
        [
            "type": attachment.type,
            "id": attachment.id,
            "name": attachment.name,
            "mimeType": attachment.mimeType,
            "sizeBytes": attachment.sizeBytes,
        ]
    }

    static func approvalDecision(_ decision: PermissionDecision) -> String {
        switch decision {
        case .allowOnce: "accept"
        case .alwaysAllow: "acceptForSession"
        case .deny: "decline"
        }
    }

    static func runtimeRequestRespondCommand(
        commandID: String,
        threadID: String,
        requestID: String,
        decision: PermissionDecision
    ) -> [String: Any] {
        [
            "type": "runtime-request.respond",
            "commandId": commandID,
            "threadId": threadID,
            "requestId": requestID,
            "decision": approvalDecision(decision),
        ]
    }

    /// Pulls a queued message into the active turn. Desktop's Steer button sends
    /// this; the server cancels the queued run and steers that text in.
    static func promoteQueuedToSteerCommand(
        commandID: String,
        threadID: String,
        queuedRunID: String,
        targetRunID: String
    ) -> [String: Any] {
        [
            "type": "queued-message.promote-to-steer",
            "commandId": commandID,
            "threadId": threadID,
            "queuedRunId": queuedRunID,
            "targetRunId": targetRunID,
        ]
    }

    static func cancelQueuedRunCommand(commandID: String, threadID: String, runID: String) -> [String: Any] {
        [
            "type": "queued-run.cancel",
            "commandId": commandID,
            "threadId": threadID,
            "runId": runID,
        ]
    }

    static func interruptCommand(commandID: String, threadID: String, runID: String) -> [String: Any] {
        [
            "type": "run.interrupt",
            "commandId": commandID,
            "threadId": threadID,
            "runId": runID,
        ]
    }

    static func settleCommand(commandID: String, threadID: String) -> [String: Any] {
        [
            "type": "thread.settle",
            "commandId": commandID,
            "threadId": threadID,
        ]
    }

    static func metadataUpdateCommand(commandID: String, threadID: String, title: String) -> [String: Any] {
        [
            "type": "thread.metadata.update",
            "commandId": commandID,
            "threadId": threadID,
            "title": title,
        ]
    }

    static func archiveCommand(commandID: String, threadID: String) -> [String: Any] {
        [
            "type": "thread.archive",
            "commandId": commandID,
            "threadId": threadID,
        ]
    }

    static func deleteCommand(commandID: String, threadID: String) -> [String: Any] {
        [
            "type": "thread.delete",
            "commandId": commandID,
            "threadId": threadID,
        ]
    }

    static func launchThreadPayload(
        commandID: String,
        projectID: String,
        title: String,
        selection: T3ModelSelection,
        runtimeMode: String = T3RuntimeModeLabel.defaultMode
    ) -> [String: Any] {
        [
            "commandId": commandID,
            "creationSource": "mobile",
            "projectId": projectID,
            "title": title,
            "modelSelection": modelSelectionPayload(selection),
            "runtimeMode": runtimeMode,
            "interactionMode": "default",
            "workspaceStrategy": ["type": "root"],
        ]
    }

    /// Server text for an `Exit` failure. Effect encodes the cause as an array
    /// of reasons: `Fail` carries the typed `error`, `Die` the `defect` (a payload
    /// that fails schema validation arrives as a `Die` with the formatted issue).
    static func failureMessage(_ cause: Any?) -> String {
        if let array = cause as? [Any] {
            let parts = array.compactMap(reasonText)
            if !parts.isEmpty { return parts.joined(separator: "; ") }
            return "RPC request failed"
        }
        return reasonText(cause) ?? "RPC request failed"
    }

    private static func reasonText(_ reason: Any?) -> String? {
        if let string = T3JSON.string(reason) { return string }
        guard let object = T3JSON.object(reason) else { return nil }
        switch T3JSON.string(object, "_tag") {
        case "Fail":
            return valueText(object["error"]) ?? "Fail"
        case "Die":
            return valueText(object["defect"]) ?? "Die"
        case "Interrupt":
            return "Interrupted"
        default:
            return valueText(object)
        }
    }

    private static func valueText(_ value: Any?) -> String? {
        if let string = T3JSON.string(value) { return string }
        guard let object = T3JSON.object(value) else { return nil }
        let tag = T3JSON.string(object, "_tag") ?? T3JSON.string(object, "name")
        if let message = T3JSON.string(object, "message") {
            if let tag, !message.hasPrefix(tag) { return "\(tag): \(message)" }
            return message
        }
        if let tag { return tag }
        guard let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]) else { return nil }
        return String(decoding: data, as: UTF8.self)
    }
}
