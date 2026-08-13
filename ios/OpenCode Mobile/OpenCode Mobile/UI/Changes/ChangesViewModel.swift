import Foundation
import Observation
import OSLog

enum ChangeSource: String, CaseIterable, Sendable, Equatable, Hashable {
    case workingTree
    case lastTurn

    private var metadata: (displayName: String, emptyTitle: String, emptyDetail: String, logValue: String) {
        switch self {
        case .workingTree:
            ("Git changes", "No Git changes", "The working tree has no uncommitted changes.", "git-changes")
        case .lastTurn:
            ("Last turn changes", "No changes", "The latest completed turn did not change any files.", "last-turn")
        }
    }

    var displayName: String { metadata.displayName }
    var emptyTitle: String { metadata.emptyTitle }
    var emptyDetail: String { metadata.emptyDetail }
    var logValue: String { metadata.logValue }
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
}

struct ChangesContentState: Equatable {
    let files: [FileDiff]
    var filter = ""

    var filteredFiles: [FileDiff] {
        let query = filter.trimmingCharacters(in: .whitespacesAndNewlines)
        return query.isEmpty ? files : files.filter { $0.path.localizedCaseInsensitiveContains(query) }
    }

    var additions: Int { filteredFiles.reduce(0) { $0 + $1.additions } }
    var deletions: Int { filteredFiles.reduce(0) { $0 + $1.deletions } }

    var summary: String {
        "\(filteredFiles.count) \(plural(filteredFiles.count, singular: "changed file", plural: "changed files"))"
    }

    var aggregateAccessibilityLabel: String {
        "\(summary), "
            + "\(additions) \(plural(additions, singular: "addition", plural: "additions")), "
            + "\(deletions) \(plural(deletions, singular: "deletion", plural: "deletions"))"
    }

    func fileAccessibilityLabel(_ file: FileDiff) -> String {
        let position = max(0, filteredFiles.firstIndex { $0.path == file.path } ?? 0)
        return "\(file.path), \(file.status.displayName), file \(position + 1) of \(filteredFiles.count), "
            + "\(file.additions) \(plural(file.additions, singular: "addition", plural: "additions")), "
            + "\(file.deletions) \(plural(file.deletions, singular: "deletion", plural: "deletions"))"
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
    private let capabilities: ServerCapabilities
    private var availableSources: [ChangeSource]
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
        capabilities: ServerCapabilities = .forProtocol(.v1)
    ) {
        self.sessionID = sessionID
        self.worktree = worktree
        self.messageID = messageID
        self.repository = repository
        self.capabilities = capabilities
        let sources = Self.capabilitySources(capabilities)
        self.availableSources = sources
        state = ChangesState(
            selectedSource: Self.capabilitySelectedSource(initialSource, capabilities: capabilities),
            availableSources: sources
        )
    }

    #if DEBUG
    init(sessionID: String, worktree: String, messageID: String?, initialSource: ChangeSource = .lastTurn, files: [FileDiff]) {
        self.sessionID = sessionID
        self.worktree = worktree
        self.messageID = messageID
        repository = PreviewChangesRepository(files: files)
        capabilities = .forProtocol(.v1)
        let sources = Self.capabilitySources(capabilities)
        availableSources = sources
        state = ChangesState(selectedSource: initialSource, availableSources: sources)
    }
    #endif

    func load() async { await load(refreshAvailability: true) }
    func retry() async { await load(refreshAvailability: true) }
    func refresh() async { await load(refreshAvailability: true, preserveContent: true) }

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
            } catch let SessionError.http(statusCode, _) where statusCode == 404 && selectedSource == .workingTree {
                guard capabilities.supportsSessionMessageDiff else { throw SessionError.http(statusCode: statusCode, message: "missing") }
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
                state = ChangesState(
                    phase: selectedSource == .lastTurn && messageID == nil ? .noTurn : .noChanges,
                    selectedSource: selectedSource,
                    availableSources: availableSources
                )
                return
            }
            log(source: selectedSource, fileCount: files.count, startedAt: startedAt, result: "success")
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
                    refreshError: error.localizedDescription
                )
            } else {
                state = ChangesState(
                    phase: .error(error.localizedDescription),
                    selectedSource: activeSource,
                    availableSources: availableSources
                )
            }
        }
    }

    private func resolveAvailableSources() async throws -> [ChangeSource] {
        var sources: [ChangeSource] = []
        do {
            if try await repository.getVcsInfo(directory: worktree).supportsGit {
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
            "load source=\(source.logValue, privacy: .public) session=\(self.sessionID, privacy: .public) directory=\(self.worktree, privacy: .private) files=\(fileCount) result=\(result, privacy: .public) durationMs=\(duration)"
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
    func respondToPermission(sessionID: String, permissionID: String, decision: PermissionDecision, directory: String?) async throws -> Bool { false }
}
#endif

private extension FileDiffStatus {
    var displayName: String { rawValue.capitalized }
}

private func plural(_ count: Int, singular: String, plural: String) -> String {
    count == 1 ? singular : plural
}
