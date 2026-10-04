import Foundation

@MainActor
final class T3MessageRepository: MessageRepository {
    static let defaultResubscribeDelay: Duration = .seconds(1)
    static let maxResubscribeDelay: Duration = .seconds(10)

    private let client: T3EnvironmentClient
    private let eventStream: (any ForegroundEventStream)?
    private let contentPreparer: MessageContentPreparer
    private let resubscribeDelay: Duration
    private var lastPendingRequestIDs: [String: String] = [:]
    private var continuations: [UUID: Observer<MessageListUpdate>] = [:]
    private var displayContinuations: [UUID: Observer<DisplayMessageListUpdate>] = [:]
    private var subscriptions: [String: Task<Void, Never>] = [:]
    /// Last known thread sequence per session: `snapshotSequence` from REST or
    /// a snapshot item, or `sequence` from a live event item. A re-established
    /// subscription resumes from here.
    private var sequences: [String: Int] = [:]
    private var snapshots: [String: [Message]] = [:]
    private var transcripts: [String: T3Transcript] = [:]
    private var preparedByID: [String: DisplayMessage] = [:]
    private var prepareGeneration = 0
    private var imagePreviews: [String: Data] = [:]
    private var imageAttempts: Set<String> = []
    private var imageLoads: Set<String> = []

    private struct Observer<Update> {
        let sessionID: String
        let continuation: AsyncStream<Update>.Continuation
    }

    init(
        client: T3EnvironmentClient,
        eventStream: (any ForegroundEventStream)? = nil,
        contentPreparer: MessageContentPreparer = .shared,
        resubscribeDelay: Duration = T3MessageRepository.defaultResubscribeDelay
    ) {
        self.client = client
        self.eventStream = eventStream
        self.contentPreparer = contentPreparer
        self.resubscribeDelay = resubscribeDelay
    }

    /// Sessions with a live subscription task. Exposed for tests.
    var subscribedSessionIDs: Set<String> {
        Set(subscriptions.keys)
    }

    func lastKnownSequence(sessionID: String) -> Int? {
        sequences[sessionID]
    }

    func observeMessages(sessionID: String, directory: String?) -> AsyncStream<MessageListUpdate> {
        let id = UUID()
        let (stream, continuation) = AsyncStream.makeStream(of: MessageListUpdate.self)
        continuations[id] = Observer(sessionID: sessionID, continuation: continuation)
        continuation.onTermination = { [weak self] _ in
            Task { @MainActor in
                self?.continuations.removeValue(forKey: id)
                self?.stopSubscriptionIfUnobserved(sessionID: sessionID)
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
            self.ensureSubscription(sessionID: sessionID)
        }
        return stream
    }

    func observeDisplayMessages(sessionID: String, directory: String?) -> AsyncStream<DisplayMessageListUpdate> {
        let id = UUID()
        let (stream, continuation) = AsyncStream.makeStream(of: DisplayMessageListUpdate.self)
        displayContinuations[id] = Observer(sessionID: sessionID, continuation: continuation)
        continuation.onTermination = { [weak self] _ in
            Task { @MainActor in
                self?.displayContinuations.removeValue(forKey: id)
                self?.stopSubscriptionIfUnobserved(sessionID: sessionID)
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
            self.ensureSubscription(sessionID: sessionID)
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
        sequences[sessionID] = max(sequences[sessionID] ?? 0, detail.sequence)
        publish(sessionID: sessionID, messages: messages)
        ensureSubscription(sessionID: sessionID)
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
        for (_, task) in subscriptions {
            task.cancel()
        }
        subscriptions.removeAll()
        sequences.removeAll()
        lastPendingRequestIDs.removeAll()
        snapshots.removeAll()
        transcripts.removeAll()
        preparedByID.removeAll()
        prepareGeneration += 1
        imagePreviews.removeAll()
        imageAttempts.removeAll()
        imageLoads.removeAll()
        // Sessions still on screen re-subscribe from sequence 0 and receive a
        // fresh snapshot instead of silently losing live updates.
        let observed = Set(continuations.values.map(\.sessionID))
            .union(displayContinuations.values.map(\.sessionID))
        for sessionID in observed {
            ensureSubscription(sessionID: sessionID)
        }
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

    // MARK: - Live subscription

    private func hasObservers(sessionID: String) -> Bool {
        continuations.values.contains { $0.sessionID == sessionID }
            || displayContinuations.values.contains { $0.sessionID == sessionID }
    }

    private func stopSubscriptionIfUnobserved(sessionID: String) {
        guard !hasObservers(sessionID: sessionID) else { return }
        subscriptions.removeValue(forKey: sessionID)?.cancel()
    }

    /// Starts the live subscription loop for an observed session. The loop
    /// keeps running until the last observer goes away: when the socket dies or
    /// the server ends the stream it surfaces the failure, waits, and
    /// re-subscribes from the last known sequence.
    private func ensureSubscription(sessionID: String) {
        guard hasObservers(sessionID: sessionID) else { return }
        guard subscriptions[sessionID] == nil else { return }
        subscriptions[sessionID] = Task { [weak self] in
            await self?.runSubscription(sessionID: sessionID)
        }
    }

    private func runSubscription(sessionID: String) async {
        var attempt = 0
        while !Task.isCancelled {
            let afterSequence = sequences[sessionID] ?? 0
            var failure: Error?
            do {
                for try await value in client.subscribeThread(id: sessionID, afterSequence: afterSequence) {
                    if Task.isCancelled { return }
                    attempt = 0
                    applyLiveValue(value, sessionID: sessionID)
                }
                failure = T3ClientError.rpc("T3 thread subscription ended.")
            } catch is CancellationError {
                return
            } catch {
                failure = error
            }
            if Task.isCancelled { return }
            if let failure {
                DiagnosticLog.warning(.sse, "T3 thread subscription lost session=\(sessionID): \(failure)")
                publishFailure(sessionID: sessionID, error: failure)
            }
            let delay = min(
                Self.maxResubscribeDelay,
                resubscribeDelay * (1 << min(attempt, 4))
            )
            attempt += 1
            try? await Task.sleep(for: delay)
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
        trackSequence(value, sessionID: sessionID)
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

    private func trackSequence(_ value: Any, sessionID: String) {
        guard let object = T3JSON.object(value) else { return }
        let sequence: Int?
        switch T3JSON.string(object, "kind") {
        case "snapshot":
            sequence = T3JSON.int(object["snapshotSequence"])
        case "event":
            sequence = T3JSON.int(object["sequence"])
        default:
            sequence = nil
        }
        guard let sequence else { return }
        sequences[sessionID] = max(sequences[sessionID] ?? 0, sequence)
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

    private func publishFailure(sessionID: String, error: Error) {
        for observer in continuations.values where observer.sessionID == sessionID {
            observer.continuation.yield(.loadFailed(error))
        }
        for observer in displayContinuations.values where observer.sessionID == sessionID {
            observer.continuation.yield(.loadFailed(error))
        }
    }

    private func publish(sessionID: String, messages: [Message]) {
        let shown = T3MessageMapper.applyingImagePreviews(messages, previews: imagePreviews)
        let update = MessageListUpdate.messages(shown, isFromCache: false)
        for observer in continuations.values where observer.sessionID == sessionID {
            observer.continuation.yield(update)
        }
        prepareGeneration += 1
        let generation = prepareGeneration
        let previous = preparedByID
        Task { [weak self] in
            guard let self else { return }
            let display = await self.contentPreparer.prepareMessages(shown, previousByID: previous)
            guard self.prepareGeneration == generation else { return }
            self.preparedByID = Dictionary(uniqueKeysWithValues: display.map { ($0.id, $0) })
            let prepared = DisplayMessageListUpdate.messages(display, isFromCache: false)
            for observer in self.displayContinuations.values where observer.sessionID == sessionID {
                observer.continuation.yield(prepared)
            }
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
