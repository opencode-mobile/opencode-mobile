import Foundation

@MainActor
final class SessionChatTimeline {
    var onChange: (() -> Void)?

    private(set) var state = SessionChatTimelineRegionState()
    /// Latest projected frame for Screen Model reuse and host declared-ID apply.
    private(set) var timelineFrame: TimelineFrame = .empty

    private var sessionID: String
    private let worktree: String
    private let messageRepository: any MessageRepository
    private var observationTask: Task<Void, Never>?
    private var displayApplyTask: Task<Void, Never>?
    private var observationGeneration = 0
    private var mergePendingWhileSending = false
    private var pendingOptimisticRows: [SessionChatRow] = []
    private var pendingBaselineSignatureCounts: [String: Int] = [:]
    private var lastLoadedMessages: [Message] = []
    private var lastLoadedDisplayMessages: [DisplayMessage] = []
    private var displayFrameRevision: UInt64 = 0
    /// After network confirms the cache, ignore late isFromCache publishes for Syncing.
    private var networkCacheSyncConfirmed = false
    /// Catalog used to collapse expanded slash-command templates in user bubbles.
    private(set) var serverCommands: [ServerCommand] = []
    /// Collapse-consecutive-tool-calls preference (OPE-144); widens grouping.
    private(set) var collapseConsecutiveToolCalls = false

    init(
        sessionID: String,
        worktree: String,
        messageRepository: any MessageRepository
    ) {
        self.sessionID = sessionID
        self.worktree = worktree
        self.messageRepository = messageRepository
    }

    func updateServerCommands(_ commands: [ServerCommand]) {
        guard commands != serverCommands else { return }
        let wasEmpty = serverCommands.isEmpty
        serverCommands = commands
        guard wasEmpty, !commands.isEmpty, !lastLoadedDisplayMessages.isEmpty else { return }
        let snapshot = lastLoadedDisplayMessages
        let fromCache = state.messagesFromCache
        Task {
            await applyLoadedDisplayMessagesTransform(
                snapshot,
                isRefreshing: false,
                fromQuietRefresh: true,
                messagesFromCache: fromCache
            )
        }
    }

    func updateCollapseConsecutiveToolCalls(_ enabled: Bool) {
        guard enabled != collapseConsecutiveToolCalls else { return }
        collapseConsecutiveToolCalls = enabled
        guard !lastLoadedDisplayMessages.isEmpty else { return }
        let snapshot = lastLoadedDisplayMessages
        let fromCache = state.messagesFromCache
        Task {
            await applyLoadedDisplayMessagesTransform(
                snapshot,
                isRefreshing: false,
                fromQuietRefresh: true,
                messagesFromCache: fromCache
            )
        }
    }

    func reset() {
        cancelObservationTasks()
        mergePendingWhileSending = false
        pendingOptimisticRows = []
        pendingBaselineSignatureCounts = [:]
        lastLoadedMessages = []
        lastLoadedDisplayMessages = []
        timelineFrame = .empty
        displayFrameRevision = 0
        networkCacheSyncConfirmed = false
        state = SessionChatTimelineRegionState()
        notifyChange()
    }

    func prepareForOptimisticCreate() {
        cancelObservationTasks()
        state.timeline = .creating
        state.errorMessage = nil
        notifyChange()
    }

    func showCreateFailed(_ message: String) {
        state.timeline = .error
        state.errorMessage = message
        notifyChange()
    }

    func rebind(sessionID: String) {
        guard sessionID != self.sessionID else { return }
        cancelObservationTasks()
        self.sessionID = sessionID
        reset()
    }

    func startObserving() {
        cancelObservationTasks()
        let generation = observationGeneration
        if let warm = messageRepository.peekWarmDisplayMessages(sessionID: sessionID, directory: worktree), !warm.isEmpty {
            // In-memory rows already on screen. Not unconfirmed disk cache — resume
            // skip/same-fingerprint catch-up never confirms Syncing if we raise it here.
            state.timeline = .ready
            state.errorMessage = nil
            notifyChange()
            observationTask = Task {
                await applyLoadedDisplayMessagesTransform(
                    warm,
                    isRefreshing: false,
                    fromQuietRefresh: true,
                    messagesFromCache: false
                )
                await Task.yield()
                guard !Task.isCancelled, self.observationGeneration == generation else { return }
                await runObservationLoop(generation: generation)
            }
            return
        }
        state.timeline = .loading
        state.errorMessage = nil
        notifyChange()
        observationTask = Task {
            await runObservationLoop(generation: generation)
        }
    }

    private func runObservationLoop(generation: Int) async {
        var pendingUpdate: DisplayMessageListUpdate?

        @MainActor
        func kickApply() {
            guard displayApplyTask == nil else { return }
            displayApplyTask = Task { @MainActor [weak self] in
                guard let self else { return }
                while let current = pendingUpdate {
                    guard self.observationGeneration == generation, !Task.isCancelled else { return }
                    pendingUpdate = nil
                    switch current {
                    case .loadFailed(let error):
                        applyError(error)
                    case .cacheSyncConfirmed:
                        confirmCacheSync()
                    case .messages(let displayMessages, let isFromCache):
                        await applyLoadedDisplayMessagesTransform(
                            displayMessages,
                            isRefreshing: false,
                            fromQuietRefresh: true,
                            messagesFromCache: isFromCache
                        )
                    }
                    // Yield so the observation task can land a newer snapshot while we apply;
                    // without this, MainActor serialize makes coalesce-latest a no-op.
                    await Task.yield()
                }
                guard self.observationGeneration == generation else { return }
                self.displayApplyTask = nil
                if pendingUpdate != nil, !Task.isCancelled {
                    kickApply()
                }
            }
        }

        for await update in messageRepository.observeDisplayMessages(sessionID: sessionID, directory: worktree) {
            guard observationGeneration == generation, !Task.isCancelled else { return }
            // Confirm must not sit behind coalesce-latest: a later cache-generation
            // Messages publish would otherwise drop it and leave Syncing stuck.
            if case .cacheSyncConfirmed = update {
                confirmCacheSync()
                continue
            }
            pendingUpdate = update
            kickApply()
        }
    }

    func stopObserving() {
        cancelObservationTasks()
    }

    private func cancelObservationTasks() {
        observationGeneration &+= 1
        observationTask?.cancel()
        observationTask = nil
        displayApplyTask?.cancel()
        displayApplyTask = nil
    }

    func loadMessages() async {
        state.timeline = .loading
        state.errorMessage = nil
        notifyChange()
        do {
            let displayMessages = try await messageRepository.refreshDisplayMessages(sessionID: sessionID, directory: worktree)
            await applyLoadedDisplayMessagesTransform(displayMessages, isRefreshing: false)
        } catch {
            applyError(error)
        }
    }

    func refreshMessages() async {
        if state.timeline == .ready {
            state.isRefreshing = true
            state.errorMessage = nil
            notifyChange()
        }

        do {
            let displayMessages = try await messageRepository.refreshDisplayMessages(sessionID: sessionID, directory: worktree)
            await applyLoadedDisplayMessagesTransform(displayMessages, isRefreshing: false)
        } catch {
            if state.timeline == .ready {
                state.isRefreshing = false
                state.errorMessage = errorMessage(error)
                notifyChange()
            }
        }
    }

    func beginOptimisticSend(_ rows: [SessionChatRow]) {
        mergePendingWhileSending = true
        pendingOptimisticRows = rows
        pendingBaselineSignatureCounts = signatureCounts(state.messages.filter { !$0.isPending })
        state.messages.append(contentsOf: rows)
        state.timeline = .ready
        state.errorMessage = nil
        let inserted = Set(rows.map(\.id))
        publishSyntheticTimelineFrame(
            messages: state.messages,
            insertedRowIDs: inserted,
            changedRowIDs: [],
            removedRowIDs: [],
            layoutChangedRowIDs: inserted
        )
        notifyChange()
    }

    func failOptimisticSend(optimisticID: String, error: String) {
        mergePendingWhileSending = false
        pendingOptimisticRows = []
        pendingBaselineSignatureCounts = [:]
        state.messages.removeAll { $0.id == optimisticID }
        state.errorMessage = error
        publishSyntheticTimelineFrame(
            messages: state.messages,
            insertedRowIDs: [],
            changedRowIDs: [],
            removedRowIDs: [optimisticID],
            layoutChangedRowIDs: []
        )
        notifyChange()
    }

    func clearSendingState() {
        mergePendingWhileSending = false
        pendingOptimisticRows = []
        pendingBaselineSignatureCounts = [:]
    }

    func clearReadyError() {
        guard state.timeline == .ready else { return }
        state.errorMessage = nil
        notifyChange()
    }

    func reloadAfterSend(onComplete: @escaping () -> Void, onReloadFailed: @escaping () -> Void) async {
        do {
            let displayMessages = try await messageRepository.refreshDisplayMessages(sessionID: sessionID, directory: worktree)
            await applyLoadedDisplayMessagesTransform(displayMessages, isRefreshing: false, onPublished: onComplete)
        } catch {
            onReloadFailed()
        }
    }

    func latestModelHint() -> LatestModelHint? {
        latestModelHint(from: lastLoadedMessages)
    }

    func modelSelectionHistoryHints() -> ModelSelectionHistoryHints {
        let sorted = sortedByRecency(lastLoadedMessages)
        return ModelSelectionHistoryHints(
            user: latestModelHint(from: sorted, role: .user),
            assistant: latestModelHint(from: sorted, role: .assistant),
            userAgent: latestAgentHint(from: sorted, role: .user),
            assistantAgent: latestAgentHint(from: sorted, role: .assistant)
        )
    }

    func loadedMessages() -> [Message] {
        lastLoadedMessages
    }

    func row(from message: Message, previous: SessionChatRow? = nil) -> SessionChatRow? {
        row(from: DisplayMessage(message: message), previous: previous)
    }

    func row(from display: DisplayMessage, previous: SessionChatRow? = nil) -> SessionChatRow? {
        // Single-row path stays synchronous for optimistic send; builders are
        // nonisolated so this does not pull unrelated MainActor work.
        let frame = DisplayFrameBuilder.build(
            sessionKey: sessionID,
            frameRevision: 0,
            sourceRevision: 0,
            displayMessages: [display],
            serverCommands: serverCommands,
            collapseConsecutiveToolCalls: collapseConsecutiveToolCalls
        )
        guard !frame.messages.isEmpty else { return nil }
        let previousTimeline: TimelineFrame? =
            if let previous {
                TimelineFrame(
                    frameRevision: 0,
                    rows: [TimelineRow.fromSessionChatRow(previous)],
                    insertedRowIDs: [],
                    changedRowIDs: [],
                    removedRowIDs: [],
                    layoutChangedRowIDs: [],
                    grewBottomRowIDs: [],
                    decorateRowIDs: [],
                    isRequired: false
                )
            } else {
                nil
            }
        let projected = SessionChatTimelineProjector.project(
            frame: frame,
            optimistic: .none,
            previous: previousTimeline
        )
        guard let timelineRow = projected.rows.first else { return nil }
        if let previous,
           !projected.changedRowIDs.contains(previous.id),
           !projected.insertedRowIDs.contains(previous.id),
           projected.rows.contains(where: { $0.id == previous.id })
        {
            return previous
        }
        return timelineRow.toSessionChatRow()
    }

    private func applyLoadedDisplayMessagesTransform(
        _ displayMessages: [DisplayMessage],
        isRefreshing: Bool,
        fromQuietRefresh: Bool = false,
        messagesFromCache: Bool = false,
        onPublished: (() -> Void)? = nil
    ) async {
        lastLoadedDisplayMessages = displayMessages
        lastLoadedMessages = displayMessages.map(\.message)
        guard !Task.isCancelled else { return }

        let stateSnapshot = state
        let previousErrorMessage = fromQuietRefresh ? stateSnapshot.errorMessage : nil
        let previousByID = Dictionary(uniqueKeysWithValues: stateSnapshot.messages.map { ($0.id, $0) })
        let applyStartedAt = ContinuousClock.now

        displayFrameRevision &+= 1
        let frameRevision = displayFrameRevision
        let sessionKey = sessionID
        let commands = serverCommands
        let collapse = collapseConsecutiveToolCalls

        let pendingRows: [TimelineRow]
        if mergePendingWhileSending {
            let source = pendingOptimisticRows.isEmpty
                ? stateSnapshot.messages.filter(\.isPending)
                : pendingOptimisticRows
            pendingRows = source.map { TimelineRow.fromSessionChatRow($0) }
        } else {
            pendingRows = []
        }
        let optimistic = OptimisticInputs(
            pendingRows: pendingRows,
            baselineSignatureCounts: pendingBaselineSignatureCounts,
            mergePending: mergePendingWhileSending
        )
        let previousFrame = timelineFrame.frameRevision == 0 && timelineFrame.rows.isEmpty
            ? nil
            : timelineFrame

        // Match Android: structural DisplayFrame + TimelineFrame conversion off main.
        let projected = await Task.detached(priority: .userInitiated) {
            let frame = DisplayFrameBuilder.build(
                sessionKey: sessionKey,
                frameRevision: frameRevision,
                sourceRevision: frameRevision,
                displayMessages: displayMessages,
                serverCommands: commands,
                collapseConsecutiveToolCalls: collapse
            )
            return SessionChatTimelineProjector.project(
                frame: frame,
                optimistic: optimistic,
                previous: previousFrame
            )
        }.value
        guard !Task.isCancelled else { return }
        // A newer apply may have started while we were off-main; drop stale results.
        guard frameRevision == displayFrameRevision else { return }

        let isRequired = !(fromQuietRefresh && onPublished == nil)
        let stamped = projected.withRequired(isRequired)
        let apply = TimelineApply.classify(stamped)
        timelineFrame = stamped

        var rows: [SessionChatRow] = []
        rows.reserveCapacity(stamped.rows.count)
        var rebuiltCount = 0
        for (index, timelineRow) in stamped.rows.enumerated() {
            guard frameRevision == displayFrameRevision, !Task.isCancelled else { return }
            let previous = previousByID[timelineRow.id]
            let reusePrevious =
                previous != nil
                && !stamped.changedRowIDs.contains(timelineRow.id)
                && !stamped.insertedRowIDs.contains(timelineRow.id)
                && !timelineRow.isPending
            if reusePrevious, let previous {
                rows.append(previous)
            } else {
                rebuiltCount += 1
                rows.append(timelineRow.toSessionChatRow())
            }
            let shouldYield =
                timelineRow.text.count >= 4_000
                || rebuiltCount > 0 && rebuiltCount % 2 == 0
                || (index > 0 && index % 4 == 0)
            if shouldYield {
                await Task.yield()
                guard frameRevision == displayFrameRevision, !Task.isCancelled else { return }
            }
        }

        let applyMs = Int((ContinuousClock.now - applyStartedAt) / .milliseconds(1))
        let layoutAffectingCount = stamped.layoutAffectingRowIDs.count
        let thoughtChrome = ThoughtPartChrome.summarize(
            messages: lastLoadedMessages,
            paintedCount: rows.count
        )
        if applyMs >= 50 || rows.count >= 20 || layoutAffectingCount > 0 || !stamped.grewBottomRowIDs.isEmpty {
            OpenCodeLog.sessionChat.info(
                "timelineApply rows=\(rows.count) rebuilt=\(rebuiltCount) kinds=\(TimelineApply.kindsLabel(apply.kinds)) required=\(isRequired) changed=\(stamped.changedRowIDs.count) layout=\(stamped.layoutChangedRowIDs.count) layoutAffecting=\(layoutAffectingCount) decorate=\(stamped.decorateRowIDs.count) grewBottom=\(stamped.grewBottomRowIDs.count) inserted=\(stamped.insertedRowIDs.count) rev=\(stamped.frameRevision) ms=\(applyMs) \(thoughtChrome)"
            )
        } else {
            OpenCodeLog.sessionChat.info("\(thoughtChrome)")
        }
        guard !Task.isCancelled else { return }

        let timeline: SessionChatTimelineState =
            if rows.isEmpty { .empty } else { .ready }
        let resolvedTimeline: SessionChatTimelineState =
            if fromQuietRefresh, stateSnapshot.timeline == .error, rows.isEmpty {
                .error
            } else {
                timeline
            }
        let resolvedErrorMessage: String? =
            if fromQuietRefresh {
                if !rows.isEmpty, stateSnapshot.timeline == .error {
                    nil
                } else {
                    previousErrorMessage
                }
            } else {
                nil
            }

        state.timeline = resolvedTimeline
        state.messages = rows
        state.isRefreshing = isRefreshing
        if !messagesFromCache {
            networkCacheSyncConfirmed = true
        }
        let fromCache = messagesFromCache && !networkCacheSyncConfirmed
        state.messagesFromCache = fromCache
        state.isSyncingMessages = fromCache
        state.errorMessage = resolvedErrorMessage
        clearOptimisticIfReconciled(rows: stamped.rows)
        notifyChange()
        SessionPerformanceProbe.emit("styled_timeline_published")
        MessagePipelineProbe.emit(.styledPaintCompleted)
        onPublished?()
    }

    private func clearOptimisticIfReconciled(rows: [TimelineRow]) {
        guard mergePendingWhileSending else { return }
        let pendingIDs = Set(pendingOptimisticRows.map(\.id))
        let stillPending =
            if pendingIDs.isEmpty {
                rows.contains(where: \.isPending)
            } else {
                rows.contains { pendingIDs.contains($0.id) }
            }
        guard !stillPending else { return }
        mergePendingWhileSending = false
        pendingOptimisticRows = []
        pendingBaselineSignatureCounts = [:]
    }

    private func confirmCacheSync() {
        networkCacheSyncConfirmed = true
        state.messagesFromCache = false
        state.isSyncingMessages = false
        notifyChange()
    }

    private func applyError(_ error: Error) {
        mergePendingWhileSending = false
        pendingOptimisticRows = []
        pendingBaselineSignatureCounts = [:]
        // Retain the last good timeline when one exists; only clear on cold failure.
        let retainLastGood = !state.messages.isEmpty
        if !retainLastGood {
            lastLoadedMessages = []
            lastLoadedDisplayMessages = []
            timelineFrame = .empty
            state.messages = []
        }
        state.timeline = .error
        state.errorMessage = errorMessage(error)
        notifyChange()
    }

    private func publishSyntheticTimelineFrame(
        messages: [SessionChatRow],
        insertedRowIDs: Set<String>,
        changedRowIDs: Set<String>,
        removedRowIDs: Set<String>,
        layoutChangedRowIDs: Set<String>,
        grewBottomRowIDs: Set<String> = [],
        decorateRowIDs: Set<String> = [],
        isRequired: Bool = false
    ) {
        let previousByID = Dictionary(uniqueKeysWithValues: timelineFrame.rows.map { ($0.id, $0) })
        let rows = messages.map { row -> TimelineRow in
            if let previous = previousByID[row.id],
               !insertedRowIDs.contains(row.id),
               !changedRowIDs.contains(row.id)
            {
                return previous
            }
            return TimelineRow.fromSessionChatRow(row)
        }
        displayFrameRevision &+= 1
        timelineFrame = TimelineFrame(
            frameRevision: displayFrameRevision,
            rows: rows,
            insertedRowIDs: insertedRowIDs,
            changedRowIDs: changedRowIDs,
            removedRowIDs: removedRowIDs,
            layoutChangedRowIDs: layoutChangedRowIDs,
            grewBottomRowIDs: grewBottomRowIDs,
            decorateRowIDs: decorateRowIDs,
            isRequired: isRequired
        )
    }

    private func latestModelHint(from messages: [Message]) -> LatestModelHint? {
        let sorted = sortedByRecency(messages)
        return latestModelHint(from: sorted, role: .user)
            ?? latestModelHint(from: sorted, role: .assistant)
    }

    private func sortedByRecency(_ messages: [Message]) -> [Message] {
        messages.sorted(by: {
                let leftTime = $0.sentAtEpochMs ?? Int64.min
                let rightTime = $1.sentAtEpochMs ?? Int64.min
                if leftTime != rightTime { return leftTime > rightTime }
                return $0.id > $1.id
            })
    }

    private func latestModelHint(from sortedMessages: [Message], role: MessageRole) -> LatestModelHint? {
        guard let message = sortedMessages.first(where: { $0.role == role && $0.modelProviderID != nil && $0.modelID != nil }),
              let providerID = message.modelProviderID,
              let modelID = message.modelID
        else { return nil }
        return LatestModelHint(
            providerID: providerID,
            modelID: modelID,
            variant: message.variant,
            sentAtEpochMs: message.sentAtEpochMs,
            source: role == .user ? .user : .assistant
        )
    }

    private func latestAgentHint(from sortedMessages: [Message], role: MessageRole) -> LatestAgentHint? {
        guard let message = sortedMessages.first(where: {
            $0.role == role && !($0.mode?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true)
        }), let mode = message.mode
        else { return nil }
        return LatestAgentHint(mode: mode, sentAtEpochMs: message.sentAtEpochMs)
    }

    private func signatureCounts(_ rows: [SessionChatRow]) -> [String: Int] {
        SessionChatTimelineProjector.signatureCounts(fromSessionChatRows: rows)
    }

    private func errorMessage(_ error: Error) -> String {
        if let messageError = error as? MessageError {
            return messageError.localizedDescription
        }
        return error.localizedDescription
    }

    private func notifyChange() {
        onChange?()
    }
}
