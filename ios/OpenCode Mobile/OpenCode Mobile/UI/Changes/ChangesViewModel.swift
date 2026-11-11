import Foundation
import Observation

enum ChangeSource: String, CaseIterable, Sendable, Equatable, Hashable {
    case workingTree
    case lastTurn

    var displayName: OpenCodeText {
        switch self {
        case .workingTree: "changes.source.working_tree.name"
        case .lastTurn: "changes.source.last_turn.name"
        }
    }

    var emptyTitle: OpenCodeText {
        switch self {
        case .workingTree: "changes.source.working_tree.empty_title"
        case .lastTurn: "changes.source.last_turn.empty_title"
        }
    }

    var emptyDetail: OpenCodeText {
        switch self {
        case .workingTree: "changes.source.working_tree.empty_message"
        case .lastTurn: "changes.source.last_turn.empty_message"
        }
    }

    var logValue: String {
        switch self {
        case .workingTree: "git-changes"
        case .lastTurn: "last-turn"
        }
    }
}

enum ChangesPhase: Equatable {
    case loading
    case noTurn
    case noChanges
    case content(ChangesContentState)
    case error(String)
}

struct ChangesState: Equatable {
    var phase: ChangesPhase = .loading
    var selectedSource: ChangeSource = .lastTurn
    var availableSources: [ChangeSource] = [.lastTurn]
    var isRefreshing = false
    var refreshError: String?
    /// Set while the selected source is empty but the other source has files.
    var otherSourceHint: ChangesOtherSourceHint?
}

/// Points from an empty source to the other one, which has `fileCount` changed files.
struct ChangesOtherSourceHint: Equatable {
    let source: ChangeSource
    let fileCount: Int

    var message: OpenCodeText {
        switch source {
        case .workingTree:
            OpenCodeText(LocalizedStringResource(
                "changes.other_source.working_tree",
                defaultValue: "Git changes has \(fileCount) changed files."
            ))
        case .lastTurn:
            OpenCodeText(LocalizedStringResource(
                "changes.other_source.last_turn",
                defaultValue: "The last turn changed \(fileCount) files."
            ))
        }
    }

    var actionTitle: OpenCodeText {
        OpenCodeText(LocalizedStringResource("changes.other_source.show", defaultValue: "Show \(source.displayName.resolved)"))
    }

    /// The empty source's detail followed by the hint, as one sentence pair translators can reorder.
    static func emptyDetail(_ detail: OpenCodeText, hint: ChangesOtherSourceHint) -> OpenCodeText {
        OpenCodeText(LocalizedStringResource(
            "changes.empty_detail_with_hint",
            defaultValue: "\(detail.resolved) \(hint.message.resolved)"
        ))
    }
}

struct ChangesContentState: Equatable {
    let files: [FileDiff]
    var filter = ""

    var filteredFiles: [FileDiff] {
        let query = filter.trimmingCharacters(in: .whitespacesAndNewlines)
        return query.isEmpty ? files : files.filter { $0.path.localizedCaseInsensitiveContains(query) }
    }

    var fileGroups: [ChangedFileGroup] { groupChangedFiles(filteredFiles, prefixSource: files) }

    var additions: Int { filteredFiles.reduce(0) { $0 + $1.additions } }
    var deletions: Int { filteredFiles.reduce(0) { $0 + $1.deletions } }

    var summary: String {
        let count = filteredFiles.count
        return String(localized: "changes.summary.changed_files", defaultValue: "\(count) changed files")
    }

    var aggregateAccessibilityLabel: String {
        let filesLabel = summary
        let addedLabel = Self.additionsLabel(additions)
        let deletedLabel = Self.deletionsLabel(deletions)
        return String(
            localized: "changes.accessibility.summary",
            defaultValue: "\(filesLabel), \(addedLabel), \(deletedLabel)"
        )
    }

    func fileAccessibilityLabel(_ file: FileDiff) -> String {
        let path = file.path
        let status = file.status.displayName
        let position = max(0, filteredFiles.firstIndex { $0.path == file.path } ?? 0) + 1
        let total = filteredFiles.count
        let addedLabel = Self.additionsLabel(file.additions)
        let deletedLabel = Self.deletionsLabel(file.deletions)
        return String(
            localized: "changes.accessibility.file",
            defaultValue: "\(path), \(status), file \(position) of \(total), \(addedLabel), \(deletedLabel)"
        )
    }

    private static func additionsLabel(_ count: Int) -> String {
        String(localized: "changes.count.additions", defaultValue: "\(count) additions")
    }

    private static func deletionsLabel(_ count: Int) -> String {
        String(localized: "changes.count.deletions", defaultValue: "\(count) deletions")
    }
}

struct FileViewerState: Equatable {
    let file: FileDiff
    let document: UnifiedDiffDocument
    var contextMode: UnifiedDiffContextMode = .collapsed
    var selectedHunkID: Int?

    init(file: FileDiff) {
        self.file = file
        document = UnifiedDiffParser.parse(file.patch ?? "")
        selectedHunkID = document.hunks.first?.id
    }

    var hasPreview: Bool { !document.hunks.isEmpty }
    var canExpandContext: Bool { document.visibleRows(contextMode: .collapsed).count < document.rows.count }
    var canSelectPreviousHunk: Bool { selectedHunkIndex.map { $0 > 0 } ?? false }
    var canSelectNextHunk: Bool { selectedHunkIndex.map { $0 < document.hunks.count - 1 } ?? false }

    mutating func selectPreviousHunk() { moveHunk(by: -1) }
    mutating func selectNextHunk() { moveHunk(by: 1) }
    mutating func toggleContextMode() {
        guard canExpandContext else { return }
        contextMode = contextMode == .collapsed ? .expanded : .collapsed
    }

    private var selectedHunkIndex: Int? {
        document.hunks.firstIndex { $0.id == selectedHunkID }
    }

    private mutating func moveHunk(by offset: Int) {
        guard let selectedHunkIndex else { return }
        let target = selectedHunkIndex + offset
        guard document.hunks.indices.contains(target) else { return }
        selectedHunkID = document.hunks[target].id
    }
}

@MainActor @Observable
final class ChangesViewModel {
    private let sessionID: String
    private let worktree: String
    private let messageID: String?
    private let repository: SessionRepository
    private let capabilitiesProvider: () -> ServerCapabilities
    private let lastTurnReviewChangesStore: LastTurnReviewChangesStore
    private let foregroundEventStream: (any ForegroundEventStream)?
    private let vcsSettleDelay: Duration
    private var availableSources: [ChangeSource]
    private var capabilities: ServerCapabilities { capabilitiesProvider() }
    private var filter = ""
    private var loadRevision = 0

    private(set) var state: ChangesState

    var content: ChangesContentState? {
        guard case let .content(content) = state.phase else { return nil }
        return content
    }

    init(
        sessionID: String,
        worktree: String,
        messageID: String?,
        initialSource: ChangeSource = .lastTurn,
        repository: SessionRepository,
        capabilities: ServerCapabilities = .forProtocol(.v1),
        capabilitiesProvider: (() -> ServerCapabilities)? = nil,
        lastTurnReviewChangesStore: LastTurnReviewChangesStore? = nil,
        foregroundEventStream: (any ForegroundEventStream)? = nil,
        vcsSettleDelay: Duration = ChangesViewModel.defaultVcsSettleDelay
    ) {
        self.sessionID = sessionID
        self.worktree = worktree
        self.messageID = messageID
        self.repository = repository
        let provider = capabilitiesProvider ?? { capabilities }
        self.capabilitiesProvider = provider
        self.lastTurnReviewChangesStore = lastTurnReviewChangesStore ?? LastTurnReviewChangesStore()
        self.foregroundEventStream = foregroundEventStream
        self.vcsSettleDelay = vcsSettleDelay
        let sources = Self.capabilitySources(provider())
        self.availableSources = sources
        state = ChangesState(
            selectedSource: Self.capabilitySelectedSource(initialSource, capabilities: provider()),
            availableSources: sources
        )
    }

    #if DEBUG
    init(sessionID: String, worktree: String, messageID: String?, initialSource: ChangeSource = .lastTurn, files: [FileDiff]) {
        self.sessionID = sessionID
        self.worktree = worktree
        self.messageID = messageID
        repository = PreviewChangesRepository(files: files)
        capabilitiesProvider = { .forProtocol(.v1) }
        lastTurnReviewChangesStore = LastTurnReviewChangesStore()
        foregroundEventStream = nil
        vcsSettleDelay = .zero
        let sources = Self.capabilitySources(.forProtocol(.v1))
        availableSources = sources
        state = ChangesState(selectedSource: initialSource, availableSources: sources)
    }
    #endif

    func load() async { await load(refreshAvailability: true) }
    func retry() async { await load(refreshAvailability: true) }
    func refresh() async { await load(refreshAvailability: true, preserveContent: true) }

    /// Neither protocol pushes a diff for the finished turn, so Last turn refetches when its
    /// session goes idle. A turn still running compares against the working copy until then.
    /// Runs until the calling task is cancelled.
    func observeSessionIdle() async {
        guard let foregroundEventStream else { return }
        let sessionID = sessionID
        // Filter off the main actor: the stream also carries every streaming delta.
        let idle = AsyncStream<Void> { continuation in
            let task = Task.detached {
                for await event in foregroundEventStream.events()
                    where event.isSessionRunEnd && event.sessionID == sessionID {
                    continuation.yield()
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
        for await _ in idle {
            guard messageID != nil, state.selectedSource == .lastTurn else { continue }
            await refresh()
        }
    }


    func selectSource(_ source: ChangeSource) async {
        guard source != state.selectedSource, availableSources.contains(source) else { return }
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.changes,
            actionId: ProductAnalytics.Action.changesSelectSource,
            parameters: [ProductAnalytics.Param.changeSource: source.rawValue]
        )
        state = ChangesState(phase: .loading, selectedSource: source, availableSources: availableSources)
        await load(refreshAvailability: false)
    }

    func updateFilter(_ value: String) {
        filter = value
        updateContent { content in
            var next = content
            next.filter = value
            return next
        }
    }

    func clearFilter() { updateFilter("") }

    private func load(refreshAvailability: Bool, preserveContent: Bool = false) async {
        loadRevision += 1
        let revision = loadRevision
        let requestedSource = state.selectedSource
        var activeSource = requestedSource
        let preservedPhase: ChangesPhase? = if preserveContent, case .content = state.phase { state.phase } else { nil }
        state = ChangesState(
            phase: preservedPhase ?? .loading,
            selectedSource: requestedSource,
            availableSources: availableSources,
            isRefreshing: preservedPhase != nil
        )
        let startedAt = Date()
        do {
            if refreshAvailability { availableSources = try await resolveAvailableSources() }
            guard revision == loadRevision else { return }
            if availableSources.isEmpty {
                log(source: requestedSource, fileCount: 0, startedAt: startedAt, result: "empty")
                state = ChangesState(phase: .noChanges, selectedSource: requestedSource, availableSources: availableSources)
                return
            }
            var selectedSource = preferredSource(from: requestedSource)
            if selectedSource != requestedSource {
                log(source: requestedSource, fileCount: 0, startedAt: startedAt, result: "unsupported-fallback")
            }
            activeSource = selectedSource
            if preservedPhase == nil {
                state = ChangesState(phase: .loading, selectedSource: selectedSource, availableSources: availableSources)
            }
            let loadedFiles: [FileDiff]
            do {
                loadedFiles = try await loadFiles(source: selectedSource)
            } catch let SessionError.http(statusCode, message) where statusCode == 404 && selectedSource == .workingTree {
                // No fallback source: show the server's own error, as Android does.
                guard capabilities.supportsSessionMessageDiff else { throw SessionError.http(statusCode: statusCode, message: message) }
                log(source: selectedSource, fileCount: 0, startedAt: startedAt, result: "unsupported-fallback")
                availableSources = [.lastTurn]
                selectedSource = .lastTurn
                activeSource = selectedSource
                if preservedPhase == nil {
                    state = ChangesState(phase: .loading, selectedSource: selectedSource, availableSources: availableSources)
                }
                loadedFiles = try await loadFiles(source: selectedSource)
            }
            let files = loadedFiles.sorted { $0.path < $1.path }
            guard revision == loadRevision else { return }
            guard !files.isEmpty else {
                log(source: selectedSource, fileCount: 0, startedAt: startedAt, result: "empty")
                publishLastTurnSummary(source: selectedSource, files: [], failed: false)
                let phase: ChangesPhase = selectedSource == .lastTurn && messageID == nil ? .noTurn : .noChanges
                state = ChangesState(
                    phase: phase,
                    selectedSource: selectedSource,
                    availableSources: availableSources
                )
                if phase == .noChanges { await publishOtherSourceHint(emptySource: selectedSource, revision: revision) }
                return
            }
            log(source: selectedSource, fileCount: files.count, startedAt: startedAt, result: "success")
            publishLastTurnSummary(source: selectedSource, files: files, failed: false)
            state = ChangesState(
                phase: .content(ChangesContentState(files: files, filter: filter)),
                selectedSource: selectedSource,
                availableSources: availableSources
            )
        } catch is CancellationError {
            if revision == loadRevision { state.isRefreshing = false }
        } catch {
            guard revision == loadRevision else { return }
            log(source: activeSource, fileCount: 0, startedAt: startedAt, result: "failure")
            if activeSource == .lastTurn {
                publishLastTurnSummary(source: .lastTurn, files: [], failed: true)
            }
            if let preservedPhase {
                var restoredPhase = preservedPhase
                if case var .content(content) = restoredPhase {
                    content.filter = filter
                    restoredPhase = .content(content)
                }
                state = ChangesState(
                    phase: restoredPhase,
                    selectedSource: requestedSource,
                    availableSources: availableSources,
                    refreshError: changesErrorMessage(error)
                )
            } else {
                state = ChangesState(
                    phase: .error(changesErrorMessage(error)),
                    selectedSource: activeSource,
                    availableSources: availableSources
                )
            }
        }
    }

    private func resolveAvailableSources() async throws -> [ChangeSource] {
        var sources: [ChangeSource] = []
        do {
            if try await settledVcsInfo().supportsGit {
                sources.append(.workingTree)
            }
        } catch let SessionError.http(statusCode, _) where statusCode == 404 {
            // Git is unavailable; last-turn may still be.
        }
        if capabilities.supportsSessionMessageDiff {
            sources.append(.lastTurn)
        }
        return sources
    }

    /// A cold project settles in well under a second; give it about 1.2 s.
    nonisolated static let defaultVcsSettleDelay: Duration = .milliseconds(300)
    private static let vcsSettleRetries = 4

    /// A v2 server answers without a branch for a moment after it loads (or reloads) a project.
    /// Ask again a few times before treating the folder as having no Git, so Git changes stays
    /// available. A folder that is not a Git repository never settles and ends up without it.
    private func settledVcsInfo() async throws -> VcsInfo {
        var info = try await repository.getVcsInfo(directory: worktree)
        for _ in 0..<Self.vcsSettleRetries {
            if info.isSettled || info.supportsGit { return info }
            try await Task.sleep(for: vcsSettleDelay)
            info = try await repository.getVcsInfo(directory: worktree)
        }
        return info
    }

    /// When the selected source is empty, counts the other source's files so the empty state can
    /// point there. Lookup failures leave the plain empty state.
    private func publishOtherSourceHint(emptySource: ChangeSource, revision: Int) async {
        guard let other = availableSources.first(where: { $0 != emptySource }) else { return }
        let count: Int
        do {
            switch other {
            case .workingTree:
                count = try await repository.getVcsStatus(directory: worktree).count
            case .lastTurn:
                guard let messageID else { return }
                count = try await repository.getSessionDiff(id: sessionID, messageID: messageID, directory: worktree).count
            }
        } catch {
            OpenCodeLog.changes.info("hint source=\(other.logValue) session=\(self.sessionID) result=failure")
            return
        }
        guard count > 0, revision == loadRevision, state.phase == .noChanges, state.selectedSource == emptySource else { return }
        state.otherSourceHint = ChangesOtherSourceHint(source: other, fileCount: count)
    }

    private func preferredSource(from requested: ChangeSource) -> ChangeSource {
        if availableSources.contains(requested) { return requested }
        if availableSources.contains(.lastTurn) { return .lastTurn }
        return availableSources[0]
    }

    private static func capabilitySources(_ capabilities: ServerCapabilities) -> [ChangeSource] {
        capabilities.supportsSessionMessageDiff ? [.lastTurn] : [.workingTree]
    }

    private static func capabilitySelectedSource(
        _ requested: ChangeSource,
        capabilities: ServerCapabilities
    ) -> ChangeSource {
        if requested == .lastTurn, !capabilities.supportsSessionMessageDiff {
            return .workingTree
        }
        return requested
    }

    private func publishLastTurnSummary(source: ChangeSource, files: [FileDiff], failed: Bool) {
        guard source == .lastTurn, let messageID else { return }
        lastTurnReviewChangesStore.publish(
            sessionID: sessionID,
            messageID: messageID,
            summary: failed ? nil : ReviewChangesSummary(files: files, messageID: messageID)
        )
    }

    private func loadFiles(source: ChangeSource) async throws -> [FileDiff] {
        switch source {
        case .lastTurn:
            guard capabilities.supportsSessionMessageDiff, let messageID else { return [] }
            return try await repository.getSessionDiff(id: sessionID, messageID: messageID, directory: worktree)
        case .workingTree:
            return try await repository.getVcsDiff(mode: .workingTree, directory: worktree, context: 12)
        }
    }

    private func updateContent(_ transform: (ChangesContentState) -> ChangesContentState) {
        guard case let .content(content) = state.phase else { return }
        state.phase = .content(transform(content))
    }

    private func log(source: ChangeSource, fileCount: Int, startedAt: Date, result: String) {
        let duration = max(0, Int(Date().timeIntervalSince(startedAt) * 1_000))
        OpenCodeLog.changes.info(
            "load source=\(source.logValue) session=\(self.sessionID) directory=\(self.worktree) files=\(fileCount) result=\(result) durationMs=\(duration)"
        )
    }
}

#if DEBUG
private struct PreviewChangesRepository: SessionRepository {
    let files: [FileDiff]

    func getSessionDiff(id: String, messageID: String?, directory: String?) async throws -> [FileDiff] { files }
    func getVcsInfo(directory: String?) async throws -> VcsInfo { VcsInfo(currentBranch: "feature/review", defaultBranch: "main") }
    func getVcsDiff(mode: VcsDiffMode, directory: String?, context: Int) async throws -> [FileDiff] { files }
    func listSessions(directory: String?) async throws -> [Session] { [] }
    func createSession(parentID: String?, title: String?, directory: String?) async throws -> Session { fatalError() }
    func getAllSessionStatuses(directory: String?) async throws -> [SessionStatus] { [] }
    func getSession(id: String, directory: String?) async throws -> Session { fatalError() }
    func updateSession(id: String, title: String?, directory: String?) async throws -> Session { fatalError() }
    func archiveSession(id: String, directory: String?) async throws -> Session { fatalError() }
    func deleteSession(id: String, directory: String?) async throws -> Bool { false }
    func listChildSessions(id: String) async throws -> [Session] { [] }
    func listSessionTodos(id: String, directory: String?) async throws -> [SessionTodo] { [] }
    func initializeSessionAgents(id: String, messageID: String, providerID: String, modelID: String) async throws -> Bool { false }
    func forkSession(id: String, atMessageID: String?) async throws -> Session { fatalError() }
    func abortSession(id: String, directory: String?) async throws -> Bool { false }
    func shareSession(id: String) async throws -> Session { fatalError() }
    func unshareSession(id: String) async throws -> Session { fatalError() }
    func summarizeSession(id: String, providerID: String, modelID: String) async throws -> Bool { false }
    func revertSession(id: String, messageID: String, partID: String?) async throws -> Bool { false }
    func unrevertSession(id: String) async throws -> Bool { false }
    func respondToPermission(sessionID: String, permissionID: String, decision: PermissionDecision, directory: String?, message: String?) async throws -> Bool { false }
}
#endif

private extension FileDiffStatus {
    var displayName: String {
        switch self {
        case .added: String(localized: "changes.file_status.added", defaultValue: "Added")
        case .deleted: String(localized: "changes.file_status.deleted", defaultValue: "Deleted")
        case .modified: String(localized: "changes.file_status.modified", defaultValue: "Modified")
        }
    }
}

/// The moved-folders text, or the error's own description (usually the server's message) verbatim.
private func changesErrorMessage(_ error: Error) -> String {
    if TurnDiffUnavailable.userFacing(error) != nil {
        return String(
            localized: "changes.error.turn_moved_folders",
            defaultValue: "This turn moved folders, so its changes can't be shown together."
        )
    }
    return error.localizedDescription
}
