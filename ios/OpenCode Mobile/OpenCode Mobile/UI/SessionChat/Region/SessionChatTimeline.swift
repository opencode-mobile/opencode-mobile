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
    private var catchUpChromeTask: Task<Void, Never>?
    private var displayApplyTask: Task<Void, Never>?
    private var observationGeneration = 0
    private var mergePendingWhileSending = false
    private var pendingOptimisticRows: [SessionChatRow] = []
    private var pendingBaselineIDs: Set<String> = []
    private var lastLoadedMessages: [Message] = []
    private var lastLoadedDisplayMessages: [DisplayMessage] = []
    private var pendingPromptPatches: [String: PendingPromptPatch] = [:]
    private var pendingRequiredDisplayApplies: [PendingRequiredDisplayApply] = []
    private var pendingLiveDisplayUpdate: DisplayMessageListUpdate?
    private var displayFrameRevision: UInt64 = 0
    /// After network confirms the cache, ignore late isFromCache publishes for Syncing.
    private var networkCacheSyncConfirmed = false
    private var catchUpSyncing = false
    /// Catalog used to collapse expanded slash-command templates in user bubbles.
    private(set) var serverCommands: [ServerCommand] = []
    /// Collapse-consecutive-tool-calls preference (OPE-144); widens grouping.
    private(set) var collapseConsecutiveToolCalls = false
    /// Chip-timeline preference (OPE-260); narration paragraphs with tool chips.
    private(set) var chipTimeline = false
    /// Step rail timeline preference (#779); one line per step on a rail.
    private(set) var stepRailTimeline = false
    /// Activity card timeline preference (#779); one live card per run of steps.
    private(set) var activityCardTimeline = false
    /// First message of a pending revert (v1 `session.revert`, v2 `session.revert.staged`): it and
    /// every later message stay hidden until the revert is cleared or committed (#619).
    private(set) var revertBoundaryMessageID: String?
    /// Children of this session that subagent parts without a child id link to (#648).
    private(set) var childSessions: [SessionChatChildSessionCandidate] = []
    private let listChildSessions: (@MainActor (String) async throws -> [Session])?
    /// Unlinked subagent parts already covered by a child-session lookup.
    private var childSessionLookupPartIDs: Set<String> = []
    private var childSessionLookupTask: Task<Void, Never>?
    /// Activity card: one observation per running child session the live card
    /// shows, keyed by child session id (#779).
    private var childActivityTasks: [String: Task<Void, Never>] = [:]
    /// Footer hold of the trailing activity card, by card id, and the step
    /// entry debounce of rails and cards (#779); `stepTimingTask` re-runs them when due.
    private var footerHolds: [String: SessionChatActivityFooterHold] = [:]
    private var stepEntryDebounce = SessionChatStepEntryDebounce()
    private var stepTimingTask: Task<Void, Never>?
    /// Wall clock in epoch ms for step timing; tests replace it.
    static var stepTimingNowMs: @MainActor () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1_000) }
    /// Test-only: runs after a frame revision is taken, before off-main build.
    static var applyTestGate: (@MainActor () async -> Void)?
    /// Wait before looking up child sessions, so a live `tool.progress` can link
    /// a just-started subagent first. Doubles before each retry of a failed lookup.
    static var childSessionLookupDelay: Duration = .milliseconds(1_500)
    static let childSessionLookupAttempts = 3

    init(
        sessionID: String,
        worktree: String,
        messageRepository: any MessageRepository,
        listChildSessions: (@MainActor (String) async throws -> [Session])? = nil
    ) {
        self.sessionID = sessionID
        self.worktree = worktree
        self.messageRepository = messageRepository
        self.listChildSessions = listChildSessions
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
                messagesFromCache: fromCache,
                expirePendingPromptPatches: false
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
                messagesFromCache: fromCache,
                expirePendingPromptPatches: false
            )
        }
    }

    func updateChipTimeline(_ enabled: Bool) {
        guard enabled != chipTimeline else { return }
        chipTimeline = enabled
        guard !lastLoadedDisplayMessages.isEmpty else { return }
        let snapshot = lastLoadedDisplayMessages
        let fromCache = state.messagesFromCache
        Task {
            await applyLoadedDisplayMessagesTransform(
                snapshot,
                isRefreshing: false,
                fromQuietRefresh: true,
                messagesFromCache: fromCache,
                expirePendingPromptPatches: false
            )
        }
    }

    func updateStepRailTimeline(_ enabled: Bool) {
        guard enabled != stepRailTimeline else { return }
        stepRailTimeline = enabled
        guard !lastLoadedDisplayMessages.isEmpty else { return }
        let snapshot = lastLoadedDisplayMessages
        let fromCache = state.messagesFromCache
        Task {
            await applyLoadedDisplayMessagesTransform(
                snapshot,
                isRefreshing: false,
                fromQuietRefresh: true,
                messagesFromCache: fromCache,
                expirePendingPromptPatches: false
            )
        }
    }

    func updateActivityCardTimeline(_ enabled: Bool) {
        guard enabled != activityCardTimeline else { return }
        activityCardTimeline = enabled
        guard !lastLoadedDisplayMessages.isEmpty else { return }
        let snapshot = lastLoadedDisplayMessages
        let fromCache = state.messagesFromCache
        Task {
            await applyLoadedDisplayMessagesTransform(
                snapshot,
                isRefreshing: false,
                fromQuietRefresh: true,
                messagesFromCache: fromCache,
                expirePendingPromptPatches: false
            )
        }
    }

    /// Activity card: open the live activity sheet for one card. The sheet
    /// resolves the card from the current rows by id on every render.
    func openActivityCard(_ cardID: String) {
        guard state.presentedActivityCard != cardID else { return }
        state.presentedActivityCard = cardID
        state.activityCardDetailStepId = nil
        notifyChange()
    }

    func dismissActivityCard() {
        guard state.presentedActivityCard != nil || state.activityCardDetailStepId != nil else { return }
        state.presentedActivityCard = nil
        state.activityCardDetailStepId = nil
        notifyChange()
    }

    /// Activity sheet: show one step's detail inside the open sheet. Ignored
    /// unless the step belongs to the presented card.
    func openActivityCardStep(_ stepID: String) {
        guard state.activityCardDetailStepId != stepID,
              let cardID = state.presentedActivityCard,
              SessionChatActivityCardPresentation.card(id: cardID, in: state.messages)?
                  .card.steps.contains(where: { $0.id == stepID }) == true
        else { return }
        state.activityCardDetailStepId = stepID
        notifyChange()
    }

    /// Activity sheet: back from a step's detail to the list.
    func closeActivityCardStep() {
        guard state.activityCardDetailStepId != nil else { return }
        state.activityCardDetailStepId = nil
        notifyChange()
    }

    /// Step rail: show or fold the earlier settled steps of one rail. Fold
    /// state is presentation, keyed by the rail's stable id; blocks never change.
    func toggleStepRailFold(_ railID: String) {
        if state.unfoldedStepRails.contains(railID) {
            state.unfoldedStepRails.remove(railID)
        } else {
            state.unfoldedStepRails.insert(railID)
        }
        notifyChange()
    }

    func updateRevertBoundary(_ messageID: String?) {
        guard messageID != revertBoundaryMessageID else { return }
        revertBoundaryMessageID = messageID
        guard !lastLoadedDisplayMessages.isEmpty else { return }
        let snapshot = lastLoadedDisplayMessages
        let fromCache = state.messagesFromCache
        Task {
            await applyLoadedDisplayMessagesTransform(
                snapshot,
                isRefreshing: false,
                fromQuietRefresh: true,
                messagesFromCache: fromCache,
                expirePendingPromptPatches: false
            )
        }
    }

    /// Drops the revert boundary message and everything after it. A boundary outside the loaded
    /// window falls back to id order, which opencode message ids follow.
    nonisolated static func hidingReverted(
        _ messages: [DisplayMessage],
        from boundary: String?
    ) -> [DisplayMessage] {
        guard let boundary else { return messages }
        if let index = messages.firstIndex(where: { $0.message.id == boundary }) {
            return Array(messages[..<index])
        }
        return messages.filter { $0.message.id < boundary }
    }

    /// Newest messages the projection shows, so reverted rows hidden at the
    /// tail never take the warm slots from on-screen images.
    nonisolated static func previewWarmCandidates(_ visible: [DisplayMessage]) -> [Message] {
        visible.suffix(AttachmentPreviewCache.warmMessageCount).map(\.message)
    }

    /// Looks up this session's children when a subagent part has no child id
    /// (history reloads drop the one-shot `tool.progress` id), then re-projects
    /// so its row or chip can open the child. Each unlinked part is looked up
    /// once; a later reload with a new unlinked part looks up again. A failed
    /// fetch retries with backoff.
    private func scheduleChildSessionLookup() {
        guard listChildSessions != nil, childSessionLookupTask == nil else { return }
        guard !lastLoadedMessages.isEmpty, !OptimisticSessionCreate.isPlaceholder(sessionID) else { return }
        let unlinked = SessionChatChildSessionLinker.unlinkedPartIDs(in: lastLoadedMessages)
        guard !unlinked.isSubset(of: childSessionLookupPartIDs) else { return }
        let lookupSessionID = sessionID
        childSessionLookupTask = Task { [weak self] in
            var delay = Self.childSessionLookupDelay
            for attempt in 1...Self.childSessionLookupAttempts {
                try? await Task.sleep(for: delay)
                guard let self, !Task.isCancelled, lookupSessionID == self.sessionID else { return }
                let retry = await self.lookUpChildSessions(sessionID: lookupSessionID, attempt: attempt)
                guard retry else { break }
                delay *= 2
            }
            guard let self, !Task.isCancelled, lookupSessionID == self.sessionID else { return }
            self.childSessionLookupTask = nil
        }
    }

    /// Returns true when the fetch failed and should be retried.
    private func lookUpChildSessions(sessionID lookupSessionID: String, attempt: Int) async -> Bool {
        guard let listChildSessions else { return false }
        let unlinked = SessionChatChildSessionLinker.unlinkedPartIDs(in: lastLoadedMessages)
        guard !unlinked.isSubset(of: childSessionLookupPartIDs) else { return false }
        let sessions: [Session]
        do {
            sessions = try await listChildSessions(lookupSessionID)
        } catch {
            OpenCodeLog.sessionChat.warning(
                "childSessions lookup failed session=\(lookupSessionID) attempt=\(attempt) error=\(String(describing: error))"
            )
            return true
        }
        guard !Task.isCancelled, lookupSessionID == sessionID else { return false }
        childSessionLookupPartIDs.formUnion(unlinked)
        let candidates = sessions
            .filter { !$0.isArchived }
            .map { SessionChatChildSessionCandidate(id: $0.id, title: $0.title, createdAtEpochMs: $0.createdAtEpochMs) }
        OpenCodeLog.sessionChat.info(
            "childSessions lookup session=\(lookupSessionID) unlinked=\(unlinked.count) children=\(candidates.count)"
        )
        guard candidates != childSessions else { return false }
        childSessions = candidates
        guard !lastLoadedDisplayMessages.isEmpty else { return false }
        await applyLoadedDisplayMessagesTransform(
            lastLoadedDisplayMessages,
            isRefreshing: false,
            fromQuietRefresh: true,
            messagesFromCache: state.messagesFromCache,
            expirePendingPromptPatches: false
        )
        return false
    }

    func reset() {
        cancelObservationTasks()
        mergePendingWhileSending = false
        pendingOptimisticRows = []
        pendingBaselineIDs = []
        lastLoadedMessages = []
        lastLoadedDisplayMessages = []
        childSessionLookupTask?.cancel()
        childSessionLookupTask = nil
        childSessionLookupPartIDs = []
        childSessions = []
        pendingPromptPatches = [:]
        pendingRequiredDisplayApplies = []
        pendingLiveDisplayUpdate = nil
        timelineFrame = .empty
        displayFrameRevision = 0
        networkCacheSyncConfirmed = false
        catchUpSyncing = false
        state = SessionChatTimelineRegionState()
        stepTimingTask?.cancel()
        stepTimingTask = nil
        footerHolds = [:]
        stepEntryDebounce = SessionChatStepEntryDebounce()
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
        catchUpChromeTask = Task {
            await consumeCatchUpChrome(generation: generation)
        }
        if let warm = messageRepository.peekWarmDisplayMessages(sessionID: sessionID, directory: worktree), !warm.isEmpty {
            // In-memory rows already on screen. Not unconfirmed disk cache — resume
            // skip/same-fingerprint catch-up never confirms Syncing if we raise it here.
            if warm.count <= Self.warmSynchronousPaintLimit {
                // Project before publishing `.ready`: an async apply mounted the ready
                // list with zero rows for a frame or more, which blinked on open.
                applyWarmDisplayMessagesSynchronously(warm)
                notifyChange()
                observationTask = Task {
                    await Task.yield()
                    guard !Task.isCancelled, self.observationGeneration == generation else { return }
                    await runObservationLoop(generation: generation)
                }
                return
            }
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

    /// Upper bound for projecting warm rows on the main actor. Larger in-memory
    /// histories keep the off-main apply.
    static let warmSynchronousPaintLimit = 120

    /// Same projection as `applyLoadedDisplayMessagesTransform` for a quiet,
    /// network-confirmed snapshot, without the off-main hop and row-loop yields.
    private func applyWarmDisplayMessagesSynchronously(_ incoming: [DisplayMessage]) {
        let displayMessages = applyPendingPromptOverlay(incoming, expireAgainstAuthoritative: true)
        lastLoadedDisplayMessages = displayMessages
        lastLoadedMessages = displayMessages.map(\.message)
        scheduleChildSessionLookup()

        displayFrameRevision &+= 1
        let frameRevision = displayFrameRevision
        let pendingRows: [TimelineRow] =
            mergePendingWhileSending
                ? pendingOptimisticItems().map { TimelineRow.fromSessionChatRow($0) }
                : []
        let optimistic = OptimisticInputs(
            pendingRows: pendingRows,
            baselineIDs: pendingBaselineIDs,
            mergePending: mergePendingWhileSending
        )
        let previousFrame = timelineFrame.frameRevision == 0 && timelineFrame.rows.isEmpty
            ? nil
            : timelineFrame
        let frame = DisplayFrameBuilder.build(
            sessionKey: sessionID,
            frameRevision: frameRevision,
            sourceRevision: frameRevision,
            displayMessages: Self.hidingReverted(displayMessages, from: revertBoundaryMessageID),
            serverCommands: serverCommands,
            collapseConsecutiveToolCalls: collapseConsecutiveToolCalls,
            chipTimeline: chipTimeline,
            stepRail: stepRailTimeline,
            activityCard: activityCardTimeline,
            childSessions: childSessions
        )
        let stamped = SessionChatTimelineProjector.project(
            frame: frame,
            optimistic: optimistic,
            previous: previousFrame
        ).withRequired(false)
        timelineFrame = stamped

        let previousByID = Dictionary(uniqueKeysWithValues: state.messages.map { ($0.id, $0) })
        let rows = stamped.rows.map { timelineRow -> SessionChatRow in
            if let previous = previousByID[timelineRow.id],
               !stamped.changedRowIDs.contains(timelineRow.id),
               !stamped.insertedRowIDs.contains(timelineRow.id),
               !timelineRow.isPending
            {
                return previous
            }
            return timelineRow.toSessionChatRow()
        }

        state.timeline = rows.isEmpty ? .empty : .ready
        state.messages = rows
        state.isRefreshing = false
        networkCacheSyncConfirmed = true
        state.messagesFromCache = false
        state.isSyncingMessages = catchUpSyncing
        state.hasOlder = messageRepository.hasOlderMessages(sessionID: sessionID, directory: worktree)
        state.errorMessage = nil
        clearOptimisticIfReconciled()
    }

    private func runObservationLoop(generation: Int) async {
        for await update in messageRepository.observeDisplayMessages(sessionID: sessionID, directory: worktree) {
            guard observationGeneration == generation, !Task.isCancelled else { return }
            // Confirm must not sit behind coalesce-latest: a later cache-generation
            // Messages publish would otherwise drop it and leave Syncing stuck.
            if case .cacheSyncConfirmed = update {
                confirmCacheSync()
                continue
            }
            pendingLiveDisplayUpdate = update
            kickDisplayApply()
        }
    }

    private func kickDisplayApply() {
        let generation = observationGeneration
        guard displayApplyTask == nil else { return }
        displayApplyTask = Task { @MainActor [weak self] in
            guard let self else { return }
            while true {
                guard self.observationGeneration == generation, !Task.isCancelled else { return }
                if !self.pendingRequiredDisplayApplies.isEmpty {
                    let pending = self.pendingRequiredDisplayApplies.removeFirst()
                    await self.applyLoadedDisplayMessagesTransform(
                        pending.displayMessages,
                        isRefreshing: pending.isRefreshing,
                        fromQuietRefresh: pending.fromQuietRefresh,
                        messagesFromCache: pending.messagesFromCache,
                        expirePendingPromptPatches: pending.expirePendingPromptPatches,
                        onPublished: pending.onPublished
                    )
                } else if let current = self.pendingLiveDisplayUpdate {
                    self.pendingLiveDisplayUpdate = nil
                    switch current {
                    case .loadFailed(let error):
                        self.applyError(error)
                    case .cacheSyncConfirmed:
                        self.confirmCacheSync()
                    case .messages(let displayMessages, let isFromCache):
                        await self.applyLoadedDisplayMessagesTransform(
                            displayMessages,
                            isRefreshing: false,
                            fromQuietRefresh: true,
                            messagesFromCache: isFromCache,
                            expirePendingPromptPatches: true
                        )
                    }
                } else {
                    break
                }
                // Yield so the observation task can land a newer snapshot while we apply;
                // without this, MainActor serialize makes coalesce-latest a no-op.
                await Task.yield()
            }
            guard self.observationGeneration == generation else { return }
            self.displayApplyTask = nil
            if (!self.pendingRequiredDisplayApplies.isEmpty || self.pendingLiveDisplayUpdate != nil),
               !Task.isCancelled
            {
                self.kickDisplayApply()
            }
        }
    }

    func stopObserving() {
        cancelObservationTasks()
    }

    private func cancelObservationTasks() {
        observationGeneration &+= 1
        observationTask?.cancel()
        observationTask = nil
        catchUpChromeTask?.cancel()
        catchUpChromeTask = nil
        displayApplyTask?.cancel()
        displayApplyTask = nil
        pendingRequiredDisplayApplies = []
        pendingLiveDisplayUpdate = nil
        for task in childActivityTasks.values { task.cancel() }
        childActivityTasks = [:]
    }

    private func consumeCatchUpChrome(generation: Int) async {
        for await catchingUp in messageRepository.observeCatchUpChrome(sessionID: sessionID, directory: worktree) {
            guard observationGeneration == generation, !Task.isCancelled else { return }
            catchUpSyncing = catchingUp
            publishSyncingMessages()
        }
    }

    private func publishSyncingMessages() {
        let fromCache = state.messagesFromCache && !networkCacheSyncConfirmed
        let next = fromCache || catchUpSyncing
        guard state.isSyncingMessages != next else { return }
        state.isSyncingMessages = next
        notifyChange()
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

    func loadOlderMessages() async {
        guard state.hasOlder, !state.isLoadingOlder else { return }
        state.isLoadingOlder = true
        notifyChange()
        do {
            let displayMessages = try await messageRepository.loadOlderDisplayMessages(
                sessionID: sessionID,
                directory: worktree
            )
            await applyLoadedDisplayMessagesTransform(
                displayMessages,
                isRefreshing: false,
                fromQuietRefresh: true
            )
            state.hasOlder = messageRepository.hasOlderMessages(sessionID: sessionID, directory: worktree)
            state.isLoadingOlder = false
            notifyChange()
        } catch {
            state.isLoadingOlder = false
            notifyChange()
        }
    }

    func beginOptimisticSend(_ rows: [SessionChatRow]) {
        mergePendingWhileSending = true
        pendingOptimisticRows = rows
        pendingBaselineIDs = Set(lastLoadedDisplayMessages.map(\.message.id))
        let displayed = SessionChatTimelineProjector.displayedOptimisticSendRows(rows)
        let hadPriorRows = !state.messages.isEmpty || !timelineFrame.rows.isEmpty
        if displayed.isEmpty {
            state.errorMessage = nil
            if SessionChatTimelineProjector.shouldMarkTimelineReadyAfterOptimisticSend(
                displayedRows: displayed,
                hadPriorDisplayedRows: hadPriorRows
            ) {
                state.timeline = .ready
                publishSyntheticTimelineFrame(
                    messages: state.messages,
                    insertedRowIDs: [],
                    changedRowIDs: [],
                    removedRowIDs: [],
                    layoutChangedRowIDs: []
                )
            }
            notifyChange()
            return
        }
        state.messages.append(contentsOf: displayed)
        state.timeline = .ready
        state.errorMessage = nil
        let inserted = Set(displayed.map(\.id))
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
        pendingBaselineIDs = []
        // An echo already shown under the same client id stays: the server has it.
        let countBefore = state.messages.count
        state.messages.removeAll { $0.id == optimisticID && $0.isPending }
        let removed = state.messages.count != countBefore
        state.errorMessage = error
        publishSyntheticTimelineFrame(
            messages: state.messages,
            insertedRowIDs: [],
            changedRowIDs: [],
            removedRowIDs: removed ? [optimisticID] : [],
            layoutChangedRowIDs: []
        )
        notifyChange()
    }

    func clearSendingState() {
        mergePendingWhileSending = false
        pendingOptimisticRows = []
        pendingBaselineIDs = []
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

    func pendingOptimisticItems() -> [SessionChatRow] {
        pendingOptimisticRows.isEmpty ? state.messages.filter(\.isPending) : pendingOptimisticRows
    }

    var pendingPromptPatchCount: Int { pendingPromptPatches.count }

    func applyOptimisticPendingPrompt(
        messageID: String,
        delivery: FollowUpDelivery? = nil,
        removed: Bool = false
    ) {
        pendingPromptPatches[messageID] = PendingPromptPatch(delivery: delivery, removed: removed)
        let overlaid = applyPendingPromptOverlay(
            lastLoadedDisplayMessages,
            expireAgainstAuthoritative: false
        )
        lastLoadedDisplayMessages = overlaid
        lastLoadedMessages = overlaid.map(\.message)
        pendingRequiredDisplayApplies.append(
            PendingRequiredDisplayApply(
                displayMessages: overlaid,
                isRefreshing: false,
                fromQuietRefresh: false,
                messagesFromCache: state.messagesFromCache,
                expirePendingPromptPatches: false,
                onPublished: nil
            )
        )
        kickDisplayApply()
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
            collapseConsecutiveToolCalls: collapseConsecutiveToolCalls,
            chipTimeline: chipTimeline,
            stepRail: stepRailTimeline,
            activityCard: activityCardTimeline,
            childSessions: childSessions
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
        expirePendingPromptPatches: Bool = true,
        onPublished: (() -> Void)? = nil
    ) async {
        let publish = onPublished
        defer { publish?() }
        let displayMessages = applyPendingPromptOverlay(
            displayMessages,
            expireAgainstAuthoritative: expirePendingPromptPatches
        )
        lastLoadedDisplayMessages = displayMessages
        lastLoadedMessages = displayMessages.map(\.message)
        scheduleChildSessionLookup()
        guard !Task.isCancelled else { return }

        let stateSnapshot = state
        let previousErrorMessage = fromQuietRefresh ? stateSnapshot.errorMessage : nil
        let previousByID = Dictionary(uniqueKeysWithValues: stateSnapshot.messages.map { ($0.id, $0) })
        let applyStartedAt = ContinuousClock.now

        displayFrameRevision &+= 1
        let frameRevision = displayFrameRevision
        if let gate = Self.applyTestGate {
            await gate()
        }
        let sessionKey = sessionID
        let commands = serverCommands
        let collapse = collapseConsecutiveToolCalls
        let chips = chipTimeline
        let stepRail = stepRailTimeline
        let activityCard = activityCardTimeline
        let children = childSessions
        let visibleDisplayMessages = Self.hidingReverted(displayMessages, from: revertBoundaryMessageID)

        let pendingRows: [TimelineRow]
        if mergePendingWhileSending {
            pendingRows = pendingOptimisticItems().map { TimelineRow.fromSessionChatRow($0) }
        } else {
            pendingRows = []
        }
        let optimistic = OptimisticInputs(
            pendingRows: pendingRows,
            baselineIDs: pendingBaselineIDs,
            mergePending: mergePendingWhileSending
        )
        let previousFrame = timelineFrame.frameRevision == 0 && timelineFrame.rows.isEmpty
            ? nil
            : timelineFrame

        // Match Android: structural DisplayFrame + TimelineFrame conversion off main.
        let previewCandidates = Self.previewWarmCandidates(visibleDisplayMessages)
        let projected = await Task.detached(priority: .userInitiated) {
            AttachmentPreviewCache.shared.warm(previewCandidates)
            let frame = DisplayFrameBuilder.build(
                sessionKey: sessionKey,
                frameRevision: frameRevision,
                sourceRevision: frameRevision,
                displayMessages: visibleDisplayMessages,
                serverCommands: commands,
                collapseConsecutiveToolCalls: collapse,
                chipTimeline: chips,
                stepRail: stepRail,
                activityCard: activityCard,
                childSessions: children
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
        state.isSyncingMessages = fromCache || catchUpSyncing
        state.hasOlder = messageRepository.hasOlderMessages(sessionID: sessionID, directory: worktree)
        state.errorMessage = resolvedErrorMessage
        clearOptimisticIfReconciled()
        notifyChange()
        SessionPerformanceProbe.emit("styled_timeline_published")
        MessagePipelineProbe.emit(.styledPaintCompleted)
    }

    /// Hidden pending-prompt rows leave the displayed frame before this runs, so
    /// reconciliation cannot use displayed ids. An optimistic send stays pending
    /// until every leftover item has a new server echo — the same match
    /// `mergeWithPending` uses to drop the optimistic row.
    private func clearOptimisticIfReconciled() {
        guard mergePendingWhileSending else { return }
        let pendingItems = pendingOptimisticItems()
        if pendingItems.isEmpty {
            mergePendingWhileSending = false
            pendingOptimisticRows = []
            pendingBaselineIDs = []
            return
        }
        let frame = DisplayFrameBuilder.build(
            sessionKey: sessionID,
            frameRevision: 0,
            sourceRevision: 0,
            displayMessages: lastLoadedDisplayMessages,
            serverCommands: serverCommands,
            collapseConsecutiveToolCalls: collapseConsecutiveToolCalls,
            chipTimeline: chipTimeline,
            stepRail: stepRailTimeline,
            activityCard: activityCardTimeline,
            childSessions: childSessions
        )
        let stillUnacked = SessionChatTimelineProjector.unmatchedPending(
            pendingItems.map { TimelineRow.fromSessionChatRow($0) },
            serverRows: SessionChatTimelineProjector.serverRows(from: frame),
            baselineIDs: pendingBaselineIDs
        )
        guard stillUnacked.isEmpty else { return }
        mergePendingWhileSending = false
        pendingOptimisticRows = []
        pendingBaselineIDs = []
    }

    private func confirmCacheSync() {
        networkCacheSyncConfirmed = true
        state.messagesFromCache = false
        state.isSyncingMessages = catchUpSyncing
        state.hasOlder = messageRepository.hasOlderMessages(sessionID: sessionID, directory: worktree)
        notifyChange()
    }

    private func applyError(_ error: Error) {
        mergePendingWhileSending = false
        pendingOptimisticRows = []
        pendingBaselineIDs = []
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
            isRequired: isRequired,
            listIDAliases: timelineFrame.listIDAliases
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

    private func errorMessage(_ error: Error) -> String {
        if let messageError = error as? MessageError {
            return messageError.localizedDescription
        }
        return error.localizedDescription
    }

    private func applyPendingPromptOverlay(
        _ incoming: [DisplayMessage],
        expireAgainstAuthoritative: Bool
    ) -> [DisplayMessage] {
        guard !pendingPromptPatches.isEmpty else { return incoming }
        if expireAgainstAuthoritative {
            expirePendingPromptPatches(against: incoming)
        }
        guard !pendingPromptPatches.isEmpty else { return incoming }
        return incoming.compactMap { display in
            guard let patch = pendingPromptPatches[display.message.id] else { return display }
            if patch.removed { return nil }
            return DisplayMessage(
                message: display.message.withPickupState(
                    isWaitingForPickup: patch.delivery != nil,
                    followUpDelivery: patch.delivery
                ),
                renderedMarkdown: display.renderedMarkdown,
                awaitingTextMateUpgrade: display.awaitingTextMateUpgrade,
                highlightEpoch: display.highlightEpoch
            )
        }
    }

    private func expirePendingPromptPatches(against authoritative: [DisplayMessage]) {
        guard !pendingPromptPatches.isEmpty else { return }
        let byID = Dictionary(uniqueKeysWithValues: authoritative.map { ($0.message.id, $0.message) })
        for (id, patch) in pendingPromptPatches {
            let incoming = byID[id]
            if patch.removed {
                if incoming == nil {
                    pendingPromptPatches.removeValue(forKey: id)
                }
            } else if incoming == nil || incoming?.isWaitingForPickup == false {
                pendingPromptPatches.removeValue(forKey: id)
            } else if incoming?.followUpDelivery == patch.delivery,
                      incoming?.isWaitingForPickup == (patch.delivery != nil)
            {
                pendingPromptPatches.removeValue(forKey: id)
            }
        }
    }

    private func notifyChange() {
        syncActivityCards()
        syncStepTiming(nowMs: Self.stepTimingNowMs())
        onChange?()
    }

    /// Step timing (#779): the entry debounce hides steps seen in flight for
    /// less than 300 ms (step rail and activity card), and the trailing
    /// activity card's footer hold latches what its band shows. Re-runs itself
    /// at the next due time; a card that is no longer trailing drops its hold.
    private func syncStepTiming(nowMs: Int64) {
        stepTimingTask?.cancel()
        stepTimingTask = nil
        guard stepRailTimeline || activityCardTimeline else {
            footerHolds = [:]
            if !state.debouncedStepIDs.isEmpty { state.debouncedStepIDs = [] }
            if !state.activityCardFooters.isEmpty { state.activityCardFooters = [:] }
            return
        }
        var steps: [SessionChatStep] = []
        for row in state.messages where row.role == .assistant {
            for block in row.blocks {
                switch block {
                case .stepRail(_, let rail): steps.append(contentsOf: rail.steps)
                case .activityCard(_, let card): steps.append(contentsOf: card.steps)
                default: break
                }
            }
        }
        let hidden = stepEntryDebounce.submit(steps, nowMs: nowMs)
        var footers: [String: SessionChatActivityFooter] = [:]
        var holds: [String: SessionChatActivityFooterHold] = [:]
        // Liveness is decided at render time; the trailing card of the last row is the only one that can be live.
        if activityCardTimeline,
           let trailingID = SessionChatTimelineBlocks.liveStepRunID(in: state.messages, sessionWorking: true),
           let trailing = SessionChatActivityCardPresentation.card(id: trailingID, in: state.messages)
        {
            var hold = footerHolds[trailingID] ?? SessionChatActivityFooterHold()
            footers[trailingID] = hold.submit(trailing.card.steps.filter { !hidden.contains($0.id) }, nowMs: nowMs)
            holds[trailingID] = hold
        }
        footerHolds = holds
        if state.debouncedStepIDs != hidden { state.debouncedStepIDs = hidden }
        if state.activityCardFooters != footers { state.activityCardFooters = footers }
        let due = ([stepEntryDebounce.nextDueMs] + holds.values.map(\.expiresAtMs)).compactMap { $0 }.min()
        guard let due else { return }
        stepTimingTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .milliseconds(max(0, due - nowMs)))
            guard let self, !Task.isCancelled else { return }
            self.stepTimingTask = nil
            let before = (self.state.debouncedStepIDs, self.state.activityCardFooters)
            // Never earlier than due, so a coarse sleep still ends the hold.
            self.syncStepTiming(nowMs: max(Self.stepTimingNowMs(), due))
            if before != (self.state.debouncedStepIDs, self.state.activityCardFooters) { self.onChange?() }
        }
    }

    /// Activity card (#779): observe each running child session a card shows,
    /// through the same repository and live-event path its own Session Chat
    /// uses, and stop once it finishes. Drops a presented card that is gone.
    private func syncActivityCards() {
        var running: Set<String> = []
        var cardIDs: Set<String> = []
        var presentedStepIDs: Set<String> = []
        if activityCardTimeline {
            for row in state.messages where row.role == .assistant {
                for case .activityCard(_, let card) in row.blocks {
                    cardIDs.insert(card.id)
                    if card.id == state.presentedActivityCard {
                        presentedStepIDs.formUnion(card.steps.map(\.id))
                    }
                    for step in card.runningChildren {
                        if let id = step.childSession?.childSessionId { running.insert(id) }
                    }
                }
            }
        }
        if let presented = state.presentedActivityCard, !cardIDs.contains(presented), state.timeline == .ready {
            state.presentedActivityCard = nil
        }
        if let step = state.activityCardDetailStepId,
           state.presentedActivityCard == nil || (!presentedStepIDs.contains(step) && state.timeline == .ready)
        {
            state.activityCardDetailStepId = nil
        }
        for (id, task) in childActivityTasks where !running.contains(id) {
            task.cancel()
            childActivityTasks[id] = nil
            state.childActivities[id] = nil
        }
        for id in running where childActivityTasks[id] == nil {
            childActivityTasks[id] = observeChildActivity(id)
        }
    }

    private func observeChildActivity(_ childID: String) -> Task<Void, Never> {
        let repository = messageRepository
        let directory = worktree
        let throttle = ChildActivityThrottle()
        return Task { @MainActor [weak self] in
            for await update in repository.observeMessages(sessionID: childID, directory: directory) {
                guard !Task.isCancelled else { return }
                guard case .messages(let messages, _) = update else { continue }
                let activity = await Task.detached(priority: .userInitiated) {
                    SessionChatTimelineBlocks.childActivity(from: messages)
                }.value
                guard let self, !Task.isCancelled, self.childActivityTasks[childID] != nil else { return }
                guard self.state.childActivities[childID] != activity else { continue }
                throttle.submit(activity) { [weak self] latest in
                    guard let self, self.childActivityTasks[childID] != nil else { return }
                    guard self.state.childActivities[childID] != latest else { return }
                    self.state.childActivities[childID] = latest
                    self.onChange?()
                }
            }
        }
    }
}

/// Holds a child session's current action on screen for a minimum time before
/// the next one replaces it, always settling on the latest value, so fast tool
/// bursts read as a smooth update instead of flicker.
@MainActor
private final class ChildActivityThrottle {
    static let hold: Duration = .milliseconds(800)

    private var lastApplied: ContinuousClock.Instant?
    private var pending: SessionChatChildActivity?
    private var flush: Task<Void, Never>?

    func submit(_ activity: SessionChatChildActivity, apply: @escaping @MainActor (SessionChatChildActivity) -> Void) {
        let now = ContinuousClock.now
        if let lastApplied, now - lastApplied < Self.hold {
            pending = activity
            guard flush == nil else { return }
            let wait = Self.hold - (now - lastApplied)
            flush = Task { @MainActor [weak self] in
                try? await Task.sleep(for: wait)
                guard let self, !Task.isCancelled, let latest = self.pending else { return }
                self.pending = nil
                self.flush = nil
                self.lastApplied = ContinuousClock.now
                apply(latest)
            }
            return
        }
        pending = nil
        lastApplied = now
        apply(activity)
    }
}

private struct PendingPromptPatch: Equatable {
    var delivery: FollowUpDelivery?
    var removed: Bool
}

private struct PendingRequiredDisplayApply {
    let displayMessages: [DisplayMessage]
    let isRefreshing: Bool
    let fromQuietRefresh: Bool
    let messagesFromCache: Bool
    let expirePendingPromptPatches: Bool
    let onPublished: (() -> Void)?
}
