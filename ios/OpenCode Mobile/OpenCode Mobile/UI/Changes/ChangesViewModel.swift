import Foundation
import Observation
import OSLog

enum ChangeSource: String, CaseIterable, Sendable, Equatable, Hashable {
    case lastTurn
    case workingTree
    case mainBranch

    private var metadata: (displayName: String, emptyTitle: String, emptyDetail: String, logValue: String) {
        switch self {
        case .lastTurn:
            ("Last turn", "No changes", "The latest completed turn did not change any files.", "last-turn")
        case .workingTree:
            ("Working tree", "No Working tree changes", "The working tree has no uncommitted changes.", "working-tree")
        case .mainBranch:
            ("Main branch", "No Main branch changes", "The current branch has no changes relative to the default branch.", "main-branch")
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
}

struct ChangesContentState: Equatable {
    let files: [FileDiff]
    var selectedPath: String
    var filter = ""
    var contextMode: UnifiedDiffContextMode = .collapsed
    var reviewedPaths: Set<String> = []
    var selectedHunkID: Int?

    var filteredFiles: [FileDiff] {
        let query = filter.trimmingCharacters(in: .whitespacesAndNewlines)
        return query.isEmpty ? files : files.filter { $0.path.localizedCaseInsensitiveContains(query) }
    }

    var selectedFile: FileDiff {
        files.first { $0.path == selectedPath }!
    }

    var selectedDocument: UnifiedDiffDocument { UnifiedDiffParser.parse(selectedFile.patch ?? "") }
    var selectedFileHasPreview: Bool { !selectedDocument.hunks.isEmpty }
    var selectedFileReviewed: Bool { reviewedPaths.contains(selectedPath) }
    var selectedHunkPositionLabel: String {
        selectedHunkIndex.map { "Hunk \($0 + 1) of \(selectedDocument.hunks.count)" } ?? "No hunks"
    }
    var canSelectPreviousHunk: Bool { (selectedHunkIndex ?? -1) > 0 }
    var canSelectNextHunk: Bool {
        guard let selectedHunkIndex else { return false }
        return selectedHunkIndex < selectedDocument.hunks.count - 1
    }

    var additions: Int { files.reduce(0) { $0 + $1.additions } }
    var deletions: Int { files.reduce(0) { $0 + $1.deletions } }

    var aggregateAccessibilityLabel: String {
        "\(files.count) \(plural(files.count, singular: "changed file", plural: "changed files")), "
            + "\(additions) \(plural(additions, singular: "addition", plural: "additions")), "
            + "\(deletions) \(plural(deletions, singular: "deletion", plural: "deletions"))"
    }

    var selectedFileSummary: String {
        "\(selectedFile.status.displayName) · \(selectedPosition + 1) of \(positionFiles.count)"
    }

    var selectedFileAccessibilityLabel: String { fileAccessibilityLabel(selectedFile) }
    var canSelectPrevious: Bool { filteredSelectedIndex > 0 }
    var canSelectNext: Bool { filteredSelectedIndex >= 0 && filteredSelectedIndex < filteredFiles.count - 1 }

    func filePickerSummary(_ file: FileDiff) -> String {
        let reviewed = reviewedPaths.contains(file.path) ? "Reviewed · " : ""
        return "\(reviewed)\(file.status.displayName) · +\(file.additions) −\(file.deletions)"
    }

    func fileAccessibilityLabel(_ file: FileDiff) -> String {
        let position = max(0, positionFiles.firstIndex { $0.path == file.path } ?? 0)
        let reviewed = reviewedPaths.contains(file.path) ? "reviewed, " : ""
        return "\(file.path), \(reviewed)\(file.status.displayName), file \(position + 1) of \(positionFiles.count), "
            + "\(file.additions) \(plural(file.additions, singular: "addition", plural: "additions")), "
            + "\(file.deletions) \(plural(file.deletions, singular: "deletion", plural: "deletions"))"
    }

    private var filteredSelectedIndex: Int {
        filteredFiles.firstIndex { $0.path == selectedPath } ?? -1
    }

    private var positionFiles: [FileDiff] {
        filteredSelectedIndex >= 0 ? filteredFiles : files
    }

    private var selectedPosition: Int {
        max(0, positionFiles.firstIndex { $0.path == selectedPath } ?? 0)
    }

    private var selectedHunkIndex: Int? {
        selectedDocument.hunks.firstIndex { $0.id == selectedHunkID }
    }

    func selecting(path: String) -> ChangesContentState {
        guard let file = files.first(where: { $0.path == path }) else { return self }
        var next = self
        next.selectedPath = path
        next.selectedHunkID = UnifiedDiffParser.parse(file.patch ?? "").hunks.first?.id
        return next
    }

    func resettingViewerControls() -> ChangesContentState {
        var next = self
        next.contextMode = .collapsed
        next.reviewedPaths = []
        next.selectedHunkID = selectedDocument.hunks.first?.id
        return next
    }
}

@MainActor @Observable
final class ChangesViewModel {
    private let sessionID: String
    private let worktree: String
    private let messageID: String?
    private let repository: SessionRepository
    private var availableSources: [ChangeSource] = [.lastTurn]
    private var loadRevision = 0

    private(set) var state = ChangesState()

    var content: ChangesContentState? {
        guard case let .content(content) = state.phase else { return nil }
        return content
    }

    init(
        sessionID: String,
        worktree: String,
        messageID: String?,
        initialSource: ChangeSource = .lastTurn,
        repository: SessionRepository
    ) {
        self.sessionID = sessionID
        self.worktree = worktree
        self.messageID = messageID
        self.repository = repository
        state.selectedSource = initialSource
    }

    #if DEBUG
    init(sessionID: String, worktree: String, messageID: String?, initialSource: ChangeSource = .lastTurn, files: [FileDiff]) {
        self.sessionID = sessionID
        self.worktree = worktree
        self.messageID = messageID
        repository = PreviewChangesRepository(files: files)
        state.selectedSource = initialSource
    }
    #endif

    func load() async { await load(refreshAvailability: true) }
    func retry() async { await load(refreshAvailability: true) }
    func refresh() async { await load(refreshAvailability: true, preserveContent: true) }

    func selectSource(_ source: ChangeSource) async {
        guard source != state.selectedSource, availableSources.contains(source) else { return }
        state = ChangesState(phase: .loading, selectedSource: source, availableSources: availableSources)
        await load(refreshAvailability: false)
    }

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
            if refreshAvailability {
                availableSources = try await resolveAvailableSources()
            }
            guard revision == loadRevision else { return }
            var selectedSource = availableSources.contains(requestedSource) ? requestedSource : .lastTurn
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
            } catch let SessionError.http(statusCode, _) where statusCode == 404 && selectedSource != .lastTurn {
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
            guard let first = files.first else {
                log(source: selectedSource, fileCount: 0, startedAt: startedAt, result: "empty")
                state = ChangesState(
                    phase: selectedSource == .lastTurn && messageID == nil ? .noTurn : .noChanges,
                    selectedSource: selectedSource,
                    availableSources: availableSources
                )
                return
            }
            log(source: selectedSource, fileCount: files.count, startedAt: startedAt, result: "success")
            let firstHunkID = UnifiedDiffParser.parse(first.patch ?? "").hunks.first?.id
            state = ChangesState(
                phase: .content(ChangesContentState(files: files, selectedPath: first.path, selectedHunkID: firstHunkID)),
                selectedSource: selectedSource,
                availableSources: availableSources
            )
        } catch is CancellationError {
            if revision == loadRevision { state.isRefreshing = false }
            return
        } catch {
            guard revision == loadRevision else { return }
            log(source: activeSource, fileCount: 0, startedAt: startedAt, result: "failure")
            state = ChangesState(
                phase: .error(error.localizedDescription),
                selectedSource: activeSource,
                availableSources: availableSources
            )
        }
    }

    private func resolveAvailableSources() async throws -> [ChangeSource] {
        do {
            let info = try await repository.getVcsInfo(directory: worktree)
            var sources: [ChangeSource] = [.lastTurn]
            if info.supportsGit { sources.append(.workingTree) }
            if info.hasDistinctMainBranch { sources.append(.mainBranch) }
            return sources
        } catch let SessionError.http(statusCode, _) where statusCode == 404 {
            return [.lastTurn]
        }
    }

    private func loadFiles(source: ChangeSource) async throws -> [FileDiff] {
        switch source {
        case .lastTurn:
            guard let messageID else { return [] }
            return try await repository.getSessionDiff(id: sessionID, messageID: messageID, directory: worktree)
        case .workingTree:
            return try await repository.getVcsDiff(mode: .workingTree, directory: worktree, context: 12)
        case .mainBranch:
            return try await repository.getVcsDiff(mode: .mainBranch, directory: worktree, context: 12)
        }
    }

    func updateFilter(_ filter: String) {
        updateContent { content in
            var next = content
            next.filter = filter
            if !next.filteredFiles.contains(where: { $0.path == content.selectedPath }),
               let first = next.filteredFiles.first
            {
                next = next.selecting(path: first.path)
            }
            return next
        }
    }

    func clearFilter() { updateFilter("") }

    func selectFile(_ path: String) {
        updateContent { content in
            guard content.filteredFiles.contains(where: { $0.path == path }) else { return content }
            return content.selecting(path: path)
        }
    }

    func selectPreviousFile() { moveSelection(by: -1) }
    func selectNextFile() { moveSelection(by: 1) }
    func selectPreviousHunk() { moveHunk(by: -1) }
    func selectNextHunk() { moveHunk(by: 1) }

    func toggleContextMode() {
        updateContent { content in
            var next = content
            next.contextMode = content.contextMode == .collapsed ? .expanded : .collapsed
            return next
        }
    }

    func toggleSelectedFileReviewed() {
        updateContent { content in
            var next = content
            if !next.reviewedPaths.insert(content.selectedPath).inserted {
                next.reviewedPaths.remove(content.selectedPath)
            }
            return next
        }
    }

    func prepareForSourceSwitch() {
        updateContent { $0.resettingViewerControls() }
    }

    private func moveSelection(by offset: Int) {
        updateContent { content in
            guard let index = content.filteredFiles.firstIndex(where: { $0.path == content.selectedPath }) else { return content }
            let target = index + offset
            guard content.filteredFiles.indices.contains(target) else { return content }
            return content.selecting(path: content.filteredFiles[target].path)
        }
    }

    private func moveHunk(by offset: Int) {
        updateContent { content in
            guard let index = content.selectedDocument.hunks.firstIndex(where: { $0.id == content.selectedHunkID }) else { return content }
            let target = index + offset
            guard content.selectedDocument.hunks.indices.contains(target) else { return content }
            var next = content
            next.selectedHunkID = content.selectedDocument.hunks[target].id
            return next
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
    func getVcsInfo(directory: String?) async throws -> VcsInfo {
        VcsInfo(currentBranch: "feature/review", defaultBranch: "main")
    }
    func getVcsDiff(mode: VcsDiffMode, directory: String?, context: Int) async throws -> [FileDiff] { files }
    func listSessions(directory: String?) async throws -> [Session] { [] }
    func createSession(parentID: String?, title: String?, directory: String?) async throws -> Session { fatalError() }
    func getAllSessionStatuses(directory: String?) async throws -> [SessionStatus] { [] }
    func getSession(id: String, directory: String?) async throws -> Session { fatalError() }
    func updateSession(id: String, title: String?) async throws -> Session { fatalError() }
    func deleteSession(id: String) async throws -> Bool { false }
    func listChildSessions(id: String) async throws -> [Session] { [] }
    func listSessionTodos(id: String) async throws -> [SessionTodo] { [] }
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
