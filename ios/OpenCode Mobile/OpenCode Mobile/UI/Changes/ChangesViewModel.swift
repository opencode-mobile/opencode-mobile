import Foundation
import Observation
import OSLog

enum ChangesState: Equatable {
    case loading
    case noTurn
    case noChanges
    case content(ChangesContentState)
    case error(String)
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
    private let loadFiles: () async throws -> [FileDiff]

    private(set) var state: ChangesState = .loading

    var content: ChangesContentState? {
        guard case let .content(content) = state else { return nil }
        return content
    }

    init(sessionID: String, worktree: String, messageID: String?, repository: SessionRepository) {
        self.sessionID = sessionID
        self.worktree = worktree
        self.messageID = messageID
        loadFiles = {
            try await repository.getSessionDiff(id: sessionID, messageID: messageID, directory: worktree)
        }
    }

    #if DEBUG
    init(sessionID: String, worktree: String, messageID: String?, files: [FileDiff]) {
        self.sessionID = sessionID
        self.worktree = worktree
        self.messageID = messageID
        loadFiles = { files }
    }
    #endif

    func load() async {
        guard messageID != nil else {
            state = .noTurn
            return
        }
        state = .loading
        let startedAt = Date()
        do {
            let files = try await loadFiles()
                .sorted { $0.path < $1.path }
            guard let first = files.first else {
                log(fileCount: 0, startedAt: startedAt, result: "empty")
                state = .noChanges
                return
            }
            log(fileCount: files.count, startedAt: startedAt, result: "success")
            let firstHunkID = UnifiedDiffParser.parse(first.patch ?? "").hunks.first?.id
            state = .content(ChangesContentState(files: files, selectedPath: first.path, selectedHunkID: firstHunkID))
        } catch is CancellationError {
            return
        } catch {
            log(fileCount: 0, startedAt: startedAt, result: "failure")
            state = .error(error.localizedDescription)
        }
    }

    func retry() async { await load() }
    func refresh() async { await load() }

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
        guard case let .content(content) = state else { return }
        state = .content(transform(content))
    }

    private func log(fileCount: Int, startedAt: Date, result: String) {
        let duration = max(0, Int(Date().timeIntervalSince(startedAt) * 1_000))
        OpenCodeLog.changes.info(
            "load source=last-turn session=\(self.sessionID, privacy: .public) directory=\(self.worktree, privacy: .private) files=\(fileCount) result=\(result, privacy: .public) durationMs=\(duration)"
        )
    }
}

private extension FileDiffStatus {
    var displayName: String { rawValue.capitalized }
}

private func plural(_ count: Int, singular: String, plural: String) -> String {
    count == 1 ? singular : plural
}
