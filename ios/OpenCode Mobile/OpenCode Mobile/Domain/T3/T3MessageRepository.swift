import Foundation

@MainActor
final class T3MessageRepository: MessageRepository {
    private let client: T3EnvironmentClient
    private let eventStream: (any ForegroundEventStream)?
    private let contentPreparer: MessageContentPreparer
    private var lastPendingRequestIDs: [String: String] = [:]
    private var continuations: [UUID: AsyncStream<MessageListUpdate>.Continuation] = [:]
    private var displayContinuations: [UUID: AsyncStream<DisplayMessageListUpdate>.Continuation] = [:]
    private var subscriptions: [String: Task<Void, Never>] = [:]
    private var snapshots: [String: [Message]] = [:]
    private var transcripts: [String: T3Transcript] = [:]
    private var preparedByID: [String: DisplayMessage] = [:]
    private var prepareGeneration = 0
    private var imagePreviews: [String: Data] = [:]
    private var imageAttempts: Set<String> = []
    private var imageLoads: Set<String> = []

    init(
        client: T3EnvironmentClient,
        eventStream: (any ForegroundEventStream)? = nil,
        contentPreparer: MessageContentPreparer = .shared
    ) {
        self.client = client
        self.eventStream = eventStream
        self.contentPreparer = contentPreparer
    }

    func observeMessages(sessionID: String, directory: String?) -> AsyncStream<MessageListUpdate> {
        let id = UUID()
        let (stream, continuation) = AsyncStream.makeStream(of: MessageListUpdate.self)
        continuations[id] = continuation
        continuation.onTermination = { [weak self] _ in
            Task { @MainActor in
                self?.continuations.removeValue(forKey: id)
            }
        }
        Task { [weak self] in
            guard let self else { return }
            do {
                let messages = try await self.refreshMessages(sessionID: sessionID, directory: directory)
                continuation.yield(.messages(messages, isFromCache: false))
            } catch {
                continuation.yield(.loadFailed(error))
            }
            self.startSubscription(sessionID: sessionID)
        }
        return stream
    }

    func observeDisplayMessages(sessionID: String, directory: String?) -> AsyncStream<DisplayMessageListUpdate> {
        let id = UUID()
        let (stream, continuation) = AsyncStream.makeStream(of: DisplayMessageListUpdate.self)
        displayContinuations[id] = continuation
        continuation.onTermination = { [weak self] _ in
            Task { @MainActor in
                self?.displayContinuations.removeValue(forKey: id)
            }
        }
        Task { [weak self] in
            guard let self else { return }
            do {
                let messages = try await self.refreshDisplayMessages(sessionID: sessionID, directory: directory)
                continuation.yield(.messages(messages, isFromCache: false))
            } catch {
                continuation.yield(.loadFailed(error))
            }
            self.startSubscription(sessionID: sessionID)
        }
        return stream
    }

    func refreshMessages(sessionID: String, directory _: String?) async throws -> [Message] {
        let detail = try await client.fetchThread(id: sessionID)
        let transcript = T3Transcript(messages: detail.messages, turnItems: detail.turnItems)
        transcripts[sessionID] = transcript
        let messages = T3MessageMapper.domainMessages(from: transcript)
        retryFailedImageLoads(in: messages)
        snapshots[sessionID] = messages
        publish(sessionID: sessionID, messages: messages)
        startSubscription(sessionID: sessionID, afterSequence: detail.sequence)
        return T3MessageMapper.applyingImagePreviews(messages, previews: imagePreviews)
    }

    func refreshDisplayMessages(sessionID: String, directory: String?) async throws -> [DisplayMessage] {
        let messages = try await refreshMessages(sessionID: sessionID, directory: directory)
        return await contentPreparer.prepareMessages(messages, previousByID: preparedByID)
    }

    func prepareOptimisticUserMessage(id: String, text: String) async -> DisplayMessage {
        await contentPreparer.prepareOptimisticUserMessage(id: id, text: text)
    }

    func clearMessageCache() {
        snapshots.removeAll()
        transcripts.removeAll()
        preparedByID.removeAll()
        prepareGeneration += 1
        imagePreviews.removeAll()
        imageAttempts.removeAll()
        imageLoads.removeAll()
    }

    func listMessages(sessionID: String, directory: String?) async throws -> [Message] {
        try await refreshMessages(sessionID: sessionID, directory: directory)
    }

    func getMessage(sessionID: String, messageID: String, directory: String?) async throws -> Message {
        let messages = try await listMessages(sessionID: sessionID, directory: directory)
        guard let message = messages.first(where: { $0.id == messageID }) else {
            throw MessageError.http(statusCode: 404, message: "Message not found")
        }
        return message
    }

    func sendMessage(sessionID: String, text: String, directory: String?) async throws -> Message {
        try await sendPromptAsync(sessionID: sessionID, text: text, directory: directory)
        return Message(
            id: UUID().uuidString,
            role: .user,
            text: text,
            model: nil,
            mode: nil,
            sentAtEpochMs: Int64(Date().timeIntervalSince1970 * 1_000)
        )
    }

    func sendPromptAsync(sessionID: String, options: PromptSendOptions, directory: String?) async throws {
        let thread = try await resolvedThread(id: sessionID)
        guard let selection = thread.modelSelection else {
            throw T3ClientError.decoding("This thread has no model selection.")
        }
        try await client.dispatchCommand(
            T3RpcCodec.messageDispatchCommand(
                commandID: UUID().uuidString,
                threadID: sessionID,
                messageID: UUID().uuidString,
                text: options.text,
                selection: selection,
                isRunning: thread.isBusy
            )
        )
    }

    private func resolvedThread(id: String) async throws -> T3Thread {
        if let cached = client.cachedThread(id: id) { return cached }
        let snapshot = try await client.fetchShell(force: true)
        if let thread = snapshot.thread(id: id) { return thread }
        if let thread = try await client.fetchThread(id: id).thread { return thread }
        throw T3ClientError.decoding("Thread not found")
    }

    private func startSubscription(sessionID: String, afterSequence: Int? = nil) {
        if subscriptions[sessionID] != nil, afterSequence == nil { return }
        subscriptions[sessionID]?.cancel()
        let sequence = afterSequence ?? 0
        subscriptions[sessionID] = Task { [weak self] in
            guard let self else { return }
            for await value in self.client.subscribeThread(id: sessionID, afterSequence: sequence) {
                if Task.isCancelled { return }
                await MainActor.run {
                    self.applyLiveValue(value, sessionID: sessionID)
                }
            }
        }
    }

    private static let activityEventTypes: Set<String> = [
        "run.created",
        "run.updated",
        "runtime-request.updated",
        "thread.status",
        "thread.updated",
    ]

    private func applyLiveValue(_ value: Any, sessionID: String) {
        if let transcript = T3MessageMapper.transcript(fromSnapshotItem: value) {
            transcripts[sessionID] = transcript
            let messages = T3MessageMapper.domainMessages(from: transcript)
            snapshots[sessionID] = messages
            publish(sessionID: sessionID, messages: messages)
            return
        }
        if Self.isActivityEvent(value) {
            Task { [weak self] in
                await self?.refreshActivity(sessionID: sessionID)
            }
        }
        let currentTranscript = transcripts[sessionID] ?? T3Transcript()
        guard let nextTranscript = T3MessageMapper.applyingLiveEvent(value, to: currentTranscript) else { return }
        transcripts[sessionID] = nextTranscript
        let next = T3MessageMapper.domainMessages(from: nextTranscript)
        guard next != (snapshots[sessionID] ?? []) else { return }
        snapshots[sessionID] = next
        publish(sessionID: sessionID, messages: next)
    }

    private static func isActivityEvent(_ value: Any) -> Bool {
        guard let object = T3JSON.object(value) else { return false }
        let event = T3JSON.object(object["event"]) ?? object
        guard let type = T3JSON.string(event, "type") else { return false }
        return activityEventTypes.contains(type)
    }

    private func refreshActivity(sessionID: String) async {
        guard let thread = try? await client.fetchShell(force: true).thread(id: sessionID) else { return }
        eventStream?.publishSynthetic(
            OpencodeEvent(
                type: "session.status",
                sessionID: sessionID,
                rawProperties: [
                    "status": .dictionary(["type": .string(thread.isBusy ? "busy" : "idle")]),
                ]
            )
        )
        let next = thread.pendingRequestKind == "user_input" ? nil : thread.pendingRequestID
        let previous = lastPendingRequestIDs[sessionID]
        guard previous != next else { return }
        if let next, !next.isEmpty {
            lastPendingRequestIDs[sessionID] = next
            eventStream?.publishSynthetic(OpencodeEvent(type: "permission.asked", sessionID: sessionID))
        } else {
            lastPendingRequestIDs.removeValue(forKey: sessionID)
            if previous != nil {
                eventStream?.publishSynthetic(OpencodeEvent(type: "permission.replied", sessionID: sessionID))
            }
        }
    }

    private func publish(sessionID: String, messages: [Message]) {
        let shown = T3MessageMapper.applyingImagePreviews(messages, previews: imagePreviews)
        let update = MessageListUpdate.messages(shown, isFromCache: false)
        continuations.values.forEach { $0.yield(update) }
        prepareGeneration += 1
        let generation = prepareGeneration
        let previous = preparedByID
        Task { [weak self] in
            guard let self else { return }
            let display = await self.contentPreparer.prepareMessages(shown, previousByID: previous)
            guard self.prepareGeneration == generation else { return }
            self.preparedByID = Dictionary(uniqueKeysWithValues: display.map { ($0.id, $0) })
            let prepared = DisplayMessageListUpdate.messages(display, isFromCache: false)
            self.displayContinuations.values.forEach { $0.yield(prepared) }
        }
        scheduleImageLoads(sessionID: sessionID, messages: shown)
    }

    private func retryFailedImageLoads(in messages: [Message]) {
        for message in messages {
            for case .file(let file) in message.parts where file.kind == .image && file.previewData == nil {
                if imagePreviews[file.id] == nil, !imageLoads.contains(file.id) {
                    imageAttempts.remove(file.id)
                }
            }
        }
    }

    private func scheduleImageLoads(sessionID: String, messages: [Message]) {
        for message in messages {
            for case .file(let file) in message.parts where file.kind == .image && file.mime.lowercased().hasPrefix("image/") && file.previewData == nil {
                guard imagePreviews[file.id] == nil, !imageAttempts.contains(file.id) else { continue }
                guard imageLoads.insert(file.id).inserted else { continue }
                imageAttempts.insert(file.id)
                let id = file.id
                let name = file.filename
                let mime = file.mime
                let client = self.client
                Task { @concurrent in
                    let bytes = await client.fetchImageBytes(id: id, fileName: name, mimeType: mime)
                    let preview = bytes.flatMap { AttachmentPreview.make(data: $0) }
                    await MainActor.run { [weak self] in
                        guard let self else { return }
                        self.imageLoads.remove(id)
                        guard let preview else { return }
                        self.imagePreviews[id] = preview
                        guard let snapshot = self.snapshots[sessionID] else { return }
                        self.publish(sessionID: sessionID, messages: snapshot)
                    }
                }
            }
        }
    }
}
