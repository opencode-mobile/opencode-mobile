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

    static func dispatchMode(isRunning: Bool) -> [String: Any] {
        ["type": isRunning ? "queue_after_active" : "start_immediately"]
    }

    static func modelSelectionPayload(_ selection: T3ModelSelection) -> [String: Any] {
        [
            "instanceId": selection.instanceID,
            "model": selection.model,
        ]
    }

    static func messageDispatchCommand(
        commandID: String,
        threadID: String,
        messageID: String,
        text: String,
        selection: T3ModelSelection,
        isRunning: Bool,
        attachments: [T3Attachment] = []
    ) -> [String: Any] {
        [
            "type": "message.dispatch",
            "commandId": commandID,
            "threadId": threadID,
            "messageId": messageID,
            "text": text,
            "attachments": attachments.map(chatAttachmentPayload),
            "dispatchMode": dispatchMode(isRunning: isRunning),
            "modelSelection": modelSelectionPayload(selection),
        ]
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
        selection: T3ModelSelection
    ) -> [String: Any] {
        [
            "commandId": commandID,
            "creationSource": "mobile",
            "projectId": projectID,
            "title": title,
            "modelSelection": modelSelectionPayload(selection),
            "runtimeMode": "approval-required",
            "interactionMode": "default",
            "workspaceStrategy": ["type": "root"],
        ]
    }

    private static func failureMessage(_ cause: Any?) -> String {
        if let string = T3JSON.string(cause) { return string }
        if let array = cause as? [Any] {
            let parts = array.compactMap { item -> String? in
                if let string = T3JSON.string(item) { return string }
                if let object = T3JSON.object(item) {
                    return T3JSON.string(object, "message")
                        ?? T3JSON.string(object, "_tag")
                }
                return nil
            }
            if !parts.isEmpty { return parts.joined(separator: "; ") }
        }
        if let object = T3JSON.object(cause) {
            return T3JSON.string(object, "message") ?? "RPC request failed"
        }
        return "RPC request failed"
    }
}
