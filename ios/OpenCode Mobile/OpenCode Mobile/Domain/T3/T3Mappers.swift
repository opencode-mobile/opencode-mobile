import Foundation

nonisolated enum T3ShellMapper {
    static func snapshot(from data: Data) throws -> T3ShellSnapshot {
        let json = try JSONSerialization.jsonObject(with: data)
        return snapshot(from: json)
    }

    static func snapshot(from json: Any) -> T3ShellSnapshot {
        let root = T3JSON.object(json) ?? [:]
        let sequence = T3JSON.int(root["snapshotSequence"] ?? root["sequence"]) ?? 0
        var threads = T3JSON.array(root["threads"]).compactMap { thread(from: $0) }
        let archived = T3JSON.array(root["archivedThreads"]).compactMap { thread(from: $0) }
        threads.append(contentsOf: archived)
        let projects = T3JSON.array(root["projects"]).compactMap { project(from: $0) }
        return T3ShellSnapshot(sequence: sequence, threads: threads, projects: projects)
    }

    static func sessions(from snapshot: T3ShellSnapshot, directory: String? = nil) -> [Session] {
        snapshot.activeThreads.map { thread in
            session(from: thread, directory: directory ?? projectRoot(for: thread, in: snapshot))
        }
    }

    /// Workspace root of the project that owns `thread`; the directory a
    /// root-strategy thread (no worktree of its own) belongs to.
    static func projectRoot(for thread: T3Thread, in snapshot: T3ShellSnapshot) -> String? {
        snapshot.projects.first { $0.id == thread.projectID }?.workspaceRoot
    }

    /// `directory` fills in `Session.directory` when the thread has no worktree
    /// of its own (root workspace strategy).
    static func session(from thread: T3Thread, directory: String? = nil) -> Session {
        Session(
            id: thread.id,
            title: thread.title,
            slug: nil,
            projectID: thread.projectID,
            directory: thread.worktreePath ?? directory,
            parentID: nil,
            createdAtEpochMs: thread.createdAtEpochMs,
            updatedAtEpochMs: thread.updatedAtEpochMs,
            archivedAtEpochMs: T3JSON.epochMs(thread.archivedAt),
            providerInstanceID: thread.providerInstanceID,
            runtimeMode: thread.runtimeMode,
            modelName: thread.modelSelection?.model,
            activeRunID: thread.activeRunID
        )
    }

    static func statuses(from snapshot: T3ShellSnapshot) -> [SessionStatus] {
        snapshot.activeThreads.map { thread in
            SessionStatus(
                sessionID: thread.id,
                raw: ["type": thread.isBusy ? "busy" : "idle"]
            )
        }
    }

    static func thread(from value: Any) -> T3Thread? {
        guard let object = T3JSON.object(value),
              let id = T3JSON.string(object, "id")
        else { return nil }
        let pending = T3JSON.object(object["pendingRuntimeRequest"])
        let statusValue = object["status"]
        let status: String
        if let string = T3JSON.string(statusValue) {
            status = string
        } else if statusValue == nil || statusValue is NSNull {
            status = "idle"
        } else {
            status = "running"
        }
        return T3Thread(
            id: id,
            title: T3JSON.string(object, "title") ?? "Thread",
            projectID: T3JSON.string(object, "projectId"),
            status: status,
            archivedAt: T3JSON.string(object["archivedAt"]),
            settledAt: T3JSON.string(object["settledAt"]),
            settledOverride: T3JSON.string(object["settledOverride"]),
            deletedAt: T3JSON.string(object["deletedAt"]),
            activeRunID: T3JSON.string(object, "activeRunId"),
            activityRunStatus: T3JSON.string(object, "activityRunStatus"),
            providerInstanceID: T3JSON.string(object, "providerInstanceId"),
            runtimeMode: T3JSON.string(object, "runtimeMode"),
            interactionMode: T3JSON.string(object, "interactionMode"),
            modelSelection: modelSelection(from: object["modelSelection"]),
            worktreePath: T3JSON.string(object, "worktreePath"),
            updatedAtEpochMs: T3JSON.epochMs(object["updatedAt"]),
            createdAtEpochMs: T3JSON.epochMs(object["createdAt"]),
            pendingRequestID: pending.flatMap { T3JSON.string($0, "id") },
            pendingRequestKind: pending.flatMap { T3JSON.string($0, "kind") }
        )
    }

    private static let userInputKind = "user_input"
    private static let dynamicToolKind = "dynamic_tool_call"
    /// Turn items that describe the tool a `dynamic_tool_call` approval is waiting on.
    private static let toolItemTypes: Set<String> = ["tool_call", "dynamic_tool", "command_execution", "file_change", "mcp_tool_call"]
    private static let settledItemStatuses: Set<String> = ["completed", "failed", "cancelled", "declined", "interrupted", "rolled_back"]

    /// The pending approval request id; nil when the thread waits on the user instead.
    static func approvalID(for thread: T3Thread) -> String? {
        guard let id = thread.pendingRequestID, !id.isEmpty else { return nil }
        guard thread.pendingRequestKind != userInputKind else { return nil }
        return id
    }

    /// The pending `user_input` request id; nil when the thread is not waiting on the user.
    static func questionID(for thread: T3Thread) -> String? {
        guard let id = thread.pendingRequestID, !id.isEmpty else { return nil }
        guard thread.pendingRequestKind == userInputKind else { return nil }
        return id
    }

    static func permissionRequest(from thread: T3Thread) -> PermissionRequest? {
        permissionRequest(from: thread, turnItems: [])
    }

    /// The approval prompt. `turnItems` (the thread projection) supplies what is
    /// being approved; without them the card still shows the request kind.
    static func permissionRequest(from thread: T3Thread, turnItems: [T3TurnItem]) -> PermissionRequest? {
        guard let id = approvalID(for: thread) else { return nil }
        guard let kind = thread.pendingRequestKind, !kind.isEmpty else { return nil }
        let approval = turnItems.last { $0.type == "approval_request" && $0.requestID == id }
        let tool = approval == nil ? pendingToolItem(in: turnItems) : nil
        let requestKind = approval?.requestKind ?? kind
        let detail = approval?.prompt ?? tool?.inputText.flatMap(nonEmpty) ?? tool?.fileName
        let toolName = tool?.toolName ?? approval?.appName
        let title = approval?.title ?? tool?.title
        var metadata: [String: AnyCodable] = ["kind": .string(requestKind)]
        if let toolName { metadata["tool"] = .string(toolName) }
        if let title = title.flatMap(nonEmpty) { metadata["title"] = .string(title) }
        if let appName = approval?.appName { metadata["appName"] = .string(appName) }
        if let detail {
            switch requestKind {
            case "command": metadata["command"] = .string(detail)
            case "file-change", "file-read": metadata["filepath"] = .string(detail)
            default: metadata["prompt"] = .string(detail)
            }
        }
        for (key, value) in tool?.inputFields ?? [:] where metadata[key] == nil {
            metadata[key] = .string(value)
        }
        return PermissionRequest(
            id: id,
            sessionID: thread.id,
            permission: permissionName(requestKind: requestKind, toolName: toolName),
            patterns: detail.map { [$0] } ?? [],
            metadata: metadata,
            always: ["this session"],
            toolMessageID: nil,
            toolCallID: nil
        )
    }

    /// The question card for a thread waiting on `user_input`; needs the thread's turn items.
    static func questionRequest(from thread: T3Thread, turnItems: [T3TurnItem]) -> QuestionRequest? {
        guard let id = questionID(for: thread) else { return nil }
        let item = turnItems.last { $0.type == "user_input_request" && $0.requestID == id }
            ?? turnItems.last { $0.type == "user_input_request" && !settledItemStatuses.contains($0.status?.lowercased() ?? "") }
        guard let item else { return nil }
        let questions = item.questions.map(questionInfo(from:))
        guard !questions.isEmpty else { return nil }
        return QuestionRequest(id: id, sessionID: thread.id, questions: questions, toolMessageID: nil, toolCallID: nil)
    }

    static func questionInfo(from question: T3UserInputQuestion) -> QuestionInfo {
        QuestionInfo(
            question: question.question,
            header: question.header.isEmpty ? question.question : question.header,
            options: question.options.map { option in
                QuestionOption(label: option.label, description: option.description, value: option.value)
            },
            multiple: question.multiSelect,
            custom: question.allowCustomAnswer,
            fieldKey: question.id
        )
    }

    private static func permissionName(requestKind: String, toolName: String?) -> String {
        if requestKind == dynamicToolKind {
            return toolName.flatMap(nonEmpty) ?? "tool"
        }
        return requestKind.replacingOccurrences(of: "-", with: "_")
    }

    private static func pendingToolItem(in turnItems: [T3TurnItem]) -> T3TurnItem? {
        turnItems.last { item in
            toolItemTypes.contains(item.type) && !settledItemStatuses.contains(item.status?.lowercased() ?? "")
        }
    }

    private static func nonEmpty(_ value: String) -> String? {
        value.isEmpty ? nil : value
    }

    static func project(from value: Any) -> T3Project? {
        guard let object = T3JSON.object(value),
              let id = T3JSON.string(object, "id")
        else { return nil }
        return T3Project(
            id: id,
            title: T3JSON.string(object, "title") ?? "Project",
            workspaceRoot: T3JSON.string(object, "workspaceRoot"),
            defaultModelSelection: modelSelection(from: object["defaultModelSelection"])
        )
    }

    static func modelSelection(from value: Any?) -> T3ModelSelection? {
        guard let object = T3JSON.object(value),
              let model = T3JSON.string(object, "model")
        else { return nil }
        let instanceID = T3JSON.string(object, "instanceId") ?? T3JSON.string(object, "provider")
        guard let instanceID else { return nil }
        return T3ModelSelection(instanceID: instanceID, model: model)
    }

    static func descriptor(from data: Data) throws -> T3EnvironmentDescriptor {
        guard let object = T3JSON.object(try JSONSerialization.jsonObject(with: data)),
              let environmentID = T3JSON.string(object, "environmentId")
        else {
            throw T3ClientError.decoding("T3 environment descriptor is missing environmentId.")
        }
        let version = T3JSON.int(object["orchestrationProtocolVersion"])
        if let version, version != 2 {
            throw T3ClientError.unsupportedProtocol(version)
        }
        if version == nil {
            throw T3ClientError.unsupportedProtocol(1)
        }
        return T3EnvironmentDescriptor(
            environmentID: environmentID,
            label: T3JSON.string(object, "label") ?? "T3",
            orchestrationProtocolVersion: version
        )
    }
}

nonisolated enum T3MessageMapper {
    static func detail(from data: Data) throws -> T3ThreadDetail {
        let json = try JSONSerialization.jsonObject(with: data)
        return detail(from: json)
    }

    static func detail(from json: Any) -> T3ThreadDetail {
        let root = T3JSON.object(json) ?? [:]
        let projection = T3JSON.object(root["projection"]) ?? root
        let sequence = T3JSON.int(root["snapshotSequence"] ?? projection["snapshotSequence"]) ?? 0
        let thread = T3ShellMapper.thread(from: projection["thread"] as Any)
            ?? T3ShellMapper.thread(from: root["thread"] as Any)
        let messages = T3JSON.array(projection["messages"]).compactMap { message(from: $0) }
        let turnItems = turnItems(from: projection)
        let runtimeRequests = T3JSON.array(projection["runtimeRequests"]).compactMap { runtimeRequest(from: $0) }
        return T3ThreadDetail(
            sequence: sequence,
            thread: thread,
            messages: messages,
            turnItems: turnItems,
            runtimeRequests: runtimeRequests
        )
    }

    private static func runtimeRequest(from value: Any) -> T3RuntimeRequest? {
        guard let object = T3JSON.object(value), let id = T3JSON.string(object, "id") else { return nil }
        return T3RuntimeRequest(
            id: id,
            kind: T3JSON.string(object, "kind") ?? "",
            status: T3JSON.string(object, "status") ?? "pending"
        )
    }

    static func userInputQuestion(from value: Any) -> T3UserInputQuestion? {
        guard let object = T3JSON.object(value),
              let id = T3JSON.string(object, "id"),
              let question = T3JSON.string(object, "question")
        else { return nil }
        let options = T3JSON.array(object["options"]).compactMap { raw -> T3UserInputOption? in
            guard let option = T3JSON.object(raw), let label = T3JSON.string(option, "label") else { return nil }
            return T3UserInputOption(
                label: label,
                description: T3JSON.string(option, "description") ?? "",
                value: option["value"] as? String
            )
        }
        return T3UserInputQuestion(
            id: id,
            header: T3JSON.string(object, "header") ?? "",
            question: question,
            options: options,
            multiSelect: T3JSON.bool(object["multiSelect"]),
            allowCustomAnswer: object["allowCustomAnswer"] == nil ? true : T3JSON.bool(object["allowCustomAnswer"])
        )
    }

    static func messages(from json: Any) -> [Message] {
        let parsed = detail(from: json)
        return domainMessages(messages: parsed.messages, turnItems: parsed.turnItems)
    }

    static func domainMessages(from transcript: T3Transcript) -> [Message] {
        domainMessages(messages: transcript.messages, turnItems: transcript.turnItems)
    }

    /// Places visible activity between the conversation messages it belongs to.
    /// An empty turn-item list is exactly the conversation messages, in order.
    static func domainMessages(messages: [T3ConversationMessage], turnItems: [T3TurnItem]) -> [Message] {
        if turnItems.isEmpty {
            return messages.map(domainMessage(from:))
        }
        let byID = Dictionary(messages.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        var emitted: Set<String> = []
        var result: [Message] = []
        var buffer = ActivityBuffer()

        func emit(_ message: T3ConversationMessage) {
            guard emitted.insert(message.id).inserted else { return }
            result.append(domainMessage(from: message))
        }

        for item in turnItems {
            switch item.type {
            case "user_message", "assistant_message":
                buffer.flush(into: &result)
                let id = item.messageID ?? item.id
                if let existing = byID[id] {
                    emit(existing)
                } else {
                    emit(conversationMessage(from: item, id: id))
                }
            default:
                guard let part = activityPart(from: item) else { continue }
                buffer.append(part, item: item)
            }
        }
        buffer.flush(into: &result)
        for message in messages where !emitted.contains(message.id) {
            emit(message)
        }
        return result
    }

    static func transcript(fromSnapshotItem value: Any) -> T3Transcript? {
        guard let object = T3JSON.object(value), T3JSON.string(object, "kind") == "snapshot" else { return nil }
        let projection = T3JSON.object(object["projection"]) ?? object
        guard object["projection"] != nil || projection["messages"] != nil else { return nil }
        let parsed = detail(from: object)
        return T3Transcript(messages: parsed.messages, turnItems: parsed.turnItems)
    }

    static func applyingLiveEvent(_ value: Any, to transcript: T3Transcript) -> T3Transcript? {
        guard let update = liveUpdate(from: value) else { return nil }
        switch update {
        case .message(let message):
            return T3Transcript(
                messages: upserting(message, into: transcript.messages),
                turnItems: transcript.turnItems
            )
        case .turnItem(let item):
            return T3Transcript(
                messages: transcript.messages,
                turnItems: upserting(item, into: transcript.turnItems)
            )
        }
    }

    static func message(from value: Any) -> T3ConversationMessage? {
        guard let object = T3JSON.object(value),
              let id = T3JSON.string(object, "id")
        else { return nil }
        return T3ConversationMessage(
            id: id,
            role: T3JSON.string(object, "role") ?? "assistant",
            text: T3JSON.string(object["text"]) ?? "",
            streaming: T3JSON.bool(object["streaming"]),
            createdAtEpochMs: T3JSON.epochMs(object["createdAt"]),
            attachments: attachments(from: object["attachments"])
        )
    }

    static func attachments(from value: Any?) -> [T3Attachment] {
        T3JSON.array(value).compactMap { item in
            guard let object = T3JSON.object(item),
                  let id = T3JSON.string(object, "id"),
                  let name = T3JSON.string(object, "name")
            else { return nil }
            return T3Attachment(
                id: id,
                type: T3JSON.string(object, "type") ?? "file",
                name: name,
                mimeType: T3JSON.string(object, "mimeType") ?? "application/octet-stream",
                sizeBytes: T3JSON.int64(object["sizeBytes"]) ?? 0
            )
        }
    }

    static func domainMessage(from message: T3ConversationMessage) -> Message {
        let role = role(from: message.role)
        let text = withoutContextImages(message.text)
        var parts: [MessagePart] = [
            .text(TextPart(id: message.id, text: text, isStreaming: message.streaming)),
        ]
        parts.append(contentsOf: message.attachments.map { .file(filePart(from: $0)) })
        return Message(
            id: message.id,
            role: role,
            text: text,
            parts: parts,
            model: nil,
            mode: nil,
            sentAtEpochMs: message.createdAtEpochMs
        )
    }

    static func withoutContextImages(_ text: String) -> String {
        guard let regex = try? NSRegularExpression(pattern: #"!\[[^\]]*\]\(t3-context:[^)]*\)\s*"#) else {
            return text
        }
        let range = NSRange(text.startIndex..., in: text)
        let stripped = regex.stringByReplacingMatches(in: text, range: range, withTemplate: "")
        return stripped.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func filePart(from attachment: T3Attachment) -> FilePart {
        FilePart(
            id: attachment.id,
            filename: attachment.name,
            mime: attachment.mimeType,
            kind: attachmentKind(for: attachment),
            dataURL: ""
        )
    }

    static func attachmentKind(for attachment: T3Attachment) -> AttachmentKind {
        let mime = attachment.mimeType.lowercased()
        if attachment.type == "image" || mime.hasPrefix("image/") { return .image }
        if mime == "application/pdf" { return .pdf }
        return .text
    }

    static func applyingImagePreviews(_ messages: [Message], previews: [String: Data]) -> [Message] {
        guard !previews.isEmpty else { return messages }
        return messages.map { applyingImagePreviews($0, previews: previews) }
    }

    static func applyingImagePreviews(_ message: Message, previews: [String: Data]) -> Message {
        var changed = false
        let parts = message.parts.map { part -> MessagePart in
            guard case .file(let file) = part, file.kind == .image, file.previewData == nil,
                  let preview = previews[file.id]
            else { return part }
            changed = true
            return .file(
                FilePart(
                    id: file.id,
                    filename: file.filename,
                    mime: file.mime,
                    kind: file.kind,
                    dataURL: file.dataURL,
                    previewData: preview
                )
            )
        }
        guard changed else { return message }
        return replacingParts(message, parts: parts)
    }

    static func messagesFromSnapshotItem(_ value: Any) -> [Message]? {
        guard let transcript = transcript(fromSnapshotItem: value) else { return nil }
        return domainMessages(from: transcript)
    }

    static func role(from raw: String) -> MessageRole {
        switch raw.lowercased() {
        case "user": .user
        case "assistant": .assistant
        case "system": .system
        default: .unknown
        }
    }

    private static func turnItems(from projection: [String: Any]) -> [T3TurnItem] {
        let visible = T3JSON.array(projection["visibleTurnItems"])
        let values = visible.isEmpty ? T3JSON.array(projection["turnItems"]) : visible
        return values.compactMap { turnItem(from: $0) }
    }

    private static func turnItem(from value: Any) -> T3TurnItem? {
        let raw = nestedTurnItem(value)
        guard let object = T3JSON.object(raw),
              let id = plainText(object["id"]),
              let type = plainText(object["type"])
        else { return nil }
        let input = object["input"]
        return T3TurnItem(
            id: id,
            ordinal: T3JSON.int(object["ordinal"]) ?? 0,
            type: type.lowercased(),
            status: plainText(object["status"]),
            title: plainText(object["title"]),
            startedAtEpochMs: T3JSON.epochMs(object["startedAt"]),
            completedAtEpochMs: T3JSON.epochMs(object["completedAt"]),
            messageID: plainText(object["messageId"]),
            text: object["text"] as? String ?? "",
            streaming: T3JSON.bool(object["streaming"]),
            attachments: attachments(from: object["attachments"]),
            toolName: plainText(object["toolName"]),
            inputText: input as? String,
            inputFields: stringFields(input),
            outputText: object["output"] as? String,
            exitCode: T3JSON.int(object["exitCode"]),
            pattern: plainText(object["pattern"]),
            patterns: stringList(object["patterns"]),
            fileName: plainText(object["fileName"]),
            prompt: plainText(object["prompt"]),
            progress: plainText(object["progress"]),
            result: plainText(object["result"]),
            childThreadID: plainText(object["childThreadId"]),
            requestID: plainText(object["requestId"]),
            requestKind: plainText(object["requestKind"]),
            appName: plainText(object["appName"]),
            responseMode: plainText(object["responseMode"]),
            questions: T3JSON.array(object["questions"]).compactMap { userInputQuestion(from: $0) }
        )
    }

    private static func nestedTurnItem(_ value: Any) -> Any {
        guard let object = T3JSON.object(value),
              let item = object["item"],
              T3JSON.object(item) != nil
        else { return value }
        return item
    }

    private static func conversationMessage(from item: T3TurnItem, id: String) -> T3ConversationMessage {
        T3ConversationMessage(
            id: id,
            role: item.type == "user_message" ? "user" : "assistant",
            text: item.text,
            streaming: item.streaming,
            createdAtEpochMs: item.startedAtEpochMs,
            attachments: item.attachments
        )
    }

    private static func activityPart(from item: T3TurnItem) -> MessagePart? {
        switch item.type {
        case "reasoning":
            reasoningPart(from: item)
        case "dynamic_tool":
            dynamicToolPart(from: item)
        case "command_execution":
            commandPart(from: item)
        case "file_search":
            namedToolPart(
                item,
                name: "grep",
                title: item.title,
                input: stringInput("pattern", item.pattern ?? item.title)
            )
        case "web_search":
            namedToolPart(
                item,
                name: "websearch",
                title: item.title,
                input: stringInput("query", item.patterns.first ?? item.title)
            )
        case "file_change":
            namedToolPart(
                item,
                name: "edit",
                title: item.fileName,
                input: stringInput("filePath", item.fileName)
            )
        case "subagent":
            subagentPart(from: item)
        default:
            nil
        }
    }

    private static func reasoningPart(from item: T3TurnItem) -> MessagePart {
        let text = capped(item.text, limit: 32_000)
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        // Non-empty thoughts must not stay streaming: the timeline hides those into footer chrome.
        let isStreaming = trimmed.isEmpty && item.streaming
        return .reasoning(
            ReasoningPart(
                id: item.id,
                text: text,
                isStreaming: isStreaming,
                timeStartEpochMs: item.startedAtEpochMs,
                timeEndEpochMs: item.completedAtEpochMs
            )
        )
    }

    private static func dynamicToolPart(from item: T3TurnItem) -> MessagePart {
        let name = dynamicToolName(item.toolName)
        return namedToolPart(item, name: name, title: item.title, input: dynamicInput(name: name, item: item))
    }

    private static func commandPart(from item: T3TurnItem) -> MessagePart {
        let command = item.inputText ?? ""
        var metadata: [String: AnyCodable]?
        if let exit = item.exitCode {
            metadata = ["exit": .int(exit)]
        }
        return namedToolPart(
            item,
            name: "bash",
            title: item.title ?? firstLine(command),
            input: stringInput("command", command),
            metadata: metadata
        )
    }

    private static func subagentPart(from item: T3TurnItem) -> MessagePart {
        let agent = item.title ?? "Subagent"
        var input = ["agent": agent]
        if let description = cappedOptional(item.prompt ?? item.progress ?? item.result, limit: 4_000) {
            input["description"] = description
        }
        var metadata: [String: AnyCodable]?
        if let child = item.childThreadID {
            metadata = ["sessionId": .string(child)]
        }
        return namedToolPart(
            item,
            name: "subagent",
            title: item.title,
            input: stringInput(input),
            metadata: metadata
        )
    }

    private static func namedToolPart(
        _ item: T3TurnItem,
        name: String,
        title: String?,
        input: [String: AnyCodable]?,
        metadata: [String: AnyCodable]? = nil
    ) -> MessagePart {
        let state = toolState(from: item.status)
        return .tool(
            ToolPart(
                id: item.id,
                toolName: name,
                status: state.status,
                title: title,
                input: input,
                output: cappedOptional(item.outputText, limit: 4_000),
                metadata: metadata,
                timeStartEpochMs: item.startedAtEpochMs,
                isInterrupted: state.interrupted
            )
        )
    }

    private static func dynamicToolName(_ raw: String?) -> String {
        let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let stripped = trimmed.lowercased().filter { $0 != "_" && $0 != "-" && $0 != " " }
        switch stripped {
        case "read", "readfile":
            return "read"
        case "grep", "rg", "find", "ls":
            return "grep"
        case "glob":
            return "glob"
        case "bash", "shell", "terminal":
            return "bash"
        case "edit", "write", "applypatch":
            return "edit"
        default:
            let lowered = trimmed.lowercased()
            if lowered.isEmpty || lowered == "tool" { return "activity" }
            return lowered
        }
    }

    private static func dynamicInput(name: String, item: T3TurnItem) -> [String: AnyCodable]? {
        switch name {
        case "read":
            return stringInput("filePath", field(item.inputFields, "path", "filePath", "file_path", "target_file", "file"))
        case "grep":
            return stringInput("pattern", field(item.inputFields, "pattern", "query") ?? item.title)
        case "glob":
            return stringInput("pattern", field(item.inputFields, "glob", "pattern", "path"))
        case "bash":
            return stringInput("command", field(item.inputFields, "command", "cmd") ?? item.inputText)
        case "edit":
            return stringInput("filePath", field(item.inputFields, "path", "filePath"))
        default:
            return nil
        }
    }

    private static func toolState(from status: String?) -> (status: ToolStatus, interrupted: Bool) {
        switch status?.lowercased() {
        case "running", "waiting":
            (.running, false)
        case "completed":
            (.completed, false)
        case "failed":
            (.error, false)
        case "cancelled", "interrupted":
            (.error, true)
        default:
            (.pending, false)
        }
    }

    private static func liveUpdate(from value: Any) -> LiveUpdate? {
        if let event = typedEvent(from: value) {
            let payload = event["payload"] ?? event
            switch T3JSON.string(event, "type") {
            case "message.updated":
                return message(from: payload).map(LiveUpdate.message)
            case "turn-item.updated":
                return turnItem(from: payload).map(LiveUpdate.turnItem)
            default:
                return nil
            }
        }
        guard let object = T3JSON.object(value), object["role"] != nil, let message = message(from: object) else {
            return nil
        }
        return .message(message)
    }

    private static func typedEvent(from value: Any) -> [String: Any]? {
        guard let object = T3JSON.object(value) else { return nil }
        if let type = T3JSON.string(object, "type") {
            if type == "message.updated" || type == "turn-item.updated" {
                return object
            }
            return nil
        }
        if let nested = T3JSON.object(object["event"]) {
            return typedEvent(from: nested)
        }
        return nil
    }

    private static func upserting(_ message: T3ConversationMessage, into messages: [T3ConversationMessage]) -> [T3ConversationMessage] {
        var next = messages
        if let index = next.firstIndex(where: { $0.id == message.id }) {
            next[index] = message
        } else {
            next.append(message)
        }
        return next
    }

    private static func upserting(_ item: T3TurnItem, into items: [T3TurnItem]) -> [T3TurnItem] {
        var next = items
        if let index = next.firstIndex(where: { $0.id == item.id }) {
            next[index] = item
            return next
        }
        let index = next.firstIndex { existing in
            if existing.ordinal != item.ordinal {
                return existing.ordinal > item.ordinal
            }
            return existing.id > item.id
        } ?? next.endIndex
        next.insert(item, at: index)
        return next
    }

    private static func plainText(_ value: Any?) -> String? {
        guard let string = value as? String else { return nil }
        let trimmed = string.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    private static func stringFields(_ value: Any?) -> [String: String] {
        guard let object = T3JSON.object(value) else { return [:] }
        var fields: [String: String] = [:]
        for (key, raw) in object {
            guard let text = plainText(raw) else { continue }
            fields[key] = text
        }
        return fields
    }

    private static func stringList(_ value: Any?) -> [String] {
        T3JSON.array(value).compactMap { plainText($0) }
    }

    private static func field(_ fields: [String: String], _ keys: String...) -> String? {
        for key in keys {
            if let value = fields[key] { return value }
        }
        return nil
    }

    private static func stringInput(_ key: String, _ value: String?) -> [String: AnyCodable]? {
        guard let value, !value.isEmpty else { return nil }
        return [key: .string(value)]
    }

    private static func stringInput(_ pairs: [String: String]) -> [String: AnyCodable]? {
        let mapped = pairs.compactMapValues { value -> AnyCodable? in
            value.isEmpty ? nil : .string(value)
        }
        return mapped.isEmpty ? nil : mapped
    }

    private static func firstLine(_ command: String) -> String? {
        guard let line = command.split(separator: "\n", maxSplits: 1, omittingEmptySubsequences: false).first else {
            return nil
        }
        return plainText(String(line))
    }

    private static func capped(_ text: String, limit: Int) -> String {
        guard text.count > limit else { return text }
        return String(text.prefix(limit))
    }

    private static func cappedOptional(_ text: String?, limit: Int) -> String? {
        guard let text, !text.isEmpty else { return nil }
        return capped(text, limit: limit)
    }

    private enum LiveUpdate {
        case message(T3ConversationMessage)
        case turnItem(T3TurnItem)
    }

    private struct ActivityBuffer {
        var parts: [MessagePart] = []
        var startedAtEpochMs: Int64?
        var firstID: String?

        mutating func append(_ part: MessagePart, item: T3TurnItem) {
            if parts.isEmpty {
                firstID = item.id
                startedAtEpochMs = item.startedAtEpochMs
            }
            parts.append(part)
        }

        mutating func flush(into result: inout [Message]) {
            guard let firstID, !parts.isEmpty else {
                self = ActivityBuffer()
                return
            }
            result.append(
                Message(
                    id: "activity:\(firstID)",
                    role: .assistant,
                    text: "",
                    parts: parts,
                    model: nil,
                    mode: nil,
                    sentAtEpochMs: startedAtEpochMs
                )
            )
            self = ActivityBuffer()
        }
    }
}

private nonisolated func replacingParts(_ message: Message, parts: [MessagePart]) -> Message {
    Message(
        id: message.id,
        role: message.role,
        text: message.text,
        parts: parts,
        cost: message.cost,
        tokens: message.tokens,
        model: message.model,
        modelProviderID: message.modelProviderID,
        modelID: message.modelID,
        variant: message.variant,
        mode: message.mode,
        sentAtEpochMs: message.sentAtEpochMs,
        completedAtEpochMs: message.completedAtEpochMs,
        contextInputTokens: message.contextInputTokens,
        contextEstimate: message.contextEstimate,
        followUpDelivery: message.followUpDelivery,
        isWaitingForPickup: message.isWaitingForPickup,
        stopReason: message.stopReason
    )
}

nonisolated enum T3Assets {
    static let createURLTag = "assets.createUrl"
    static let maxImageBytes = 10 * 1024 * 1024

    static func createURLPayload(id: String, fileName: String, mimeType: String) -> [String: Any] {
        [
            "resource": [
                "_tag": "attachment",
                "attachmentId": id,
                "fileName": fileName,
                "mimeType": mimeType,
                "disposition": "inline",
            ],
        ]
    }

    static func relativeURL(from value: Any?) -> String? {
        guard let object = T3JSON.object(value),
              let url = T3JSON.string(object, "relativeUrl"),
              url.hasPrefix("/"),
              !url.hasPrefix("//")
        else { return nil }
        return url
    }

    static func resolve(baseURL: URL, relativePath: String) -> URL? {
        guard relativePath.hasPrefix("/"), !relativePath.hasPrefix("//") else { return nil }
        let absolute = baseURL.absoluteString
        let root = absolute.hasSuffix("/") ? String(absolute.dropLast()) : absolute
        return URL(string: root + relativePath)
    }
}
