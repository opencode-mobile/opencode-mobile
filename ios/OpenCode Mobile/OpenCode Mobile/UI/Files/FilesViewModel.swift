import Foundation
import Observation
import UIKit

enum FilesRootPhase: Equatable {
    case loading
    case loaded
    case failed
}

/// One visible row of the flattened tree.
enum FilesTreeRow: Identifiable, Equatable {
    case entry(ProjectEntry, depth: Int, isExpanded: Bool, isLoading: Bool)
    case emptyFolder(parentPath: String, depth: Int)
    case folderError(parentPath: String, depth: Int)

    var id: String {
        switch self {
        case let .entry(entry, _, _, _): "entry:\(entry.relativePath)"
        case let .emptyFolder(parentPath, _): "empty:\(parentPath)"
        case let .folderError(parentPath, _): "error:\(parentPath)"
        }
    }
}

enum FilesFindPhase: Equatable {
    case idle
    case searching
    case results([ProjectEntry])
    case failed
}

struct FilesViewerTab: Identifiable, Equatable {
    var id: String { relativePath }
    let relativePath: String
    let name: String
}

/// Parsed markdown preview, compared by identity.
final class FileMarkdownPreview: Equatable {
    let id = UUID()
    let document: NativeMarkdownDocument

    init(document: NativeMarkdownDocument) {
        self.document = document
    }

    static func == (lhs: FileMarkdownPreview, rhs: FileMarkdownPreview) -> Bool {
        lhs.id == rhs.id
    }
}

enum ProjectFileViewerBody: Equatable {
    case loading
    case text(FileTextDocument)
    case image(Data, mimeType: String)
    case binary(mimeType: String?)
    case tooLarge(byteCount: Int?)
    case empty
    case failed
}

/// File Viewer copy shared with Android.
nonisolated enum FileViewerCopy {
    static let tooLargeTitle = "Too large to preview"
    static let tooLargeMessage = "This file is bigger than the app previews."

    /// The message plus the file size on a second line when known.
    static func tooLargeDetail(byteCount: Int?) -> String {
        [tooLargeMessage, byteCount.map { fileSize($0) }].compactMap { $0 }.joined(separator: "\n")
    }

    /// Decimal file-size units (`1.5 KB`, `40 MB`), like Android's platform formatter.
    static func fileSize(_ bytes: Int) -> String {
        Int64(bytes).formatted(.byteCount(style: .file))
    }
}

struct ProjectFileViewerState: Equatable {
    let relativePath: String
    let name: String
    var body: ProjectFileViewerBody = .loading
    /// Markdown files open on the rendered preview; Source shows the text viewer.
    var showsMarkdownSource = false
    var markdownPreview: FileMarkdownPreview?
    /// Highlight spans by 0-based line, filled in one pass after the plain text shows.
    /// A line present with `[]` is settled as plain.
    var lineSpans: [Int: [NativeCodeSpan]] = [:]

    var isMarkdown: Bool { ProjectPaths.isMarkdown(name: name) }

    var showsMarkdownPreview: Bool {
        guard isMarkdown, !showsMarkdownSource, case .text = body else { return false }
        return true
    }

    var textDocument: FileTextDocument? {
        if case let .text(document) = body { return document }
        return nil
    }
}

struct FilesUiState: Equatable {
    let projectName: String
    var rootPhase: FilesRootPhase = .loading
    var treeRows: [FilesTreeRow] = []
    var query = ""
    var find: FilesFindPhase = .idle
    var showHidden = false
    var wrapLines = false
    var tabs: [FilesViewerTab] = []
    var activePath: String?
    var viewers: [String: ProjectFileViewerState] = [:]
    var snackbar: OpenCodeSnackbarMessage?

    var isFinding: Bool { !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    var activeViewer: ProjectFileViewerState? { activePath.flatMap { viewers[$0] } }
}

/// Files Screen Model: one project worktree, a lazily loaded tree, server find, and a cache of
/// open files (iPad tabs switch instantly). The tree and open files live as long as the screen.
@Observable
@MainActor
final class FilesViewModel {
    static let copiedPathMessage = "Path copied"

    private(set) var state: FilesUiState

    let worktree: String
    @ObservationIgnored private let repository: any ProjectFilesRepository
    @ObservationIgnored private let highlighter: any TextMateHighlighting
    @ObservationIgnored private let textMateEnabled: () -> Bool
    @ObservationIgnored private let fallbackHighlighter: @Sendable (String, String) -> [NativeCodeSpan]
    @ObservationIgnored private let findDebounce: Duration
    @ObservationIgnored private let copyToPasteboard: (String) -> Void

    @ObservationIgnored private var folders: [String: FolderLoad] = [:]
    @ObservationIgnored private var expanded: Set<String> = []
    @ObservationIgnored private var folderTasks: [String: Task<Void, Never>] = [:]
    @ObservationIgnored private var findTask: Task<Void, Never>?
    @ObservationIgnored private var fileTasks: [String: Task<Void, Never>] = [:]
    @ObservationIgnored private var highlightTasks: [String: Task<Void, Never>] = [:]
    @ObservationIgnored private var highlightRevision: Int64 = 0
    @ObservationIgnored private var started = false

    private enum FolderLoad: Equatable {
        case loading
        case loaded([ProjectEntry])
        case failed
    }

    init(
        worktree: String,
        repository: any ProjectFilesRepository,
        highlighter: any TextMateHighlighting = KMPTextMateHighlightingEngine.shared,
        textMateEnabled: @escaping () -> Bool = { MarkdownHighlightPreferences.currentTextMateEnabled() },
        fallbackHighlighter: @escaping @Sendable (String, String) -> [NativeCodeSpan] = FileFallbackCodeHighlighter.spans,
        findDebounce: Duration = .milliseconds(250),
        copyToPasteboard: @escaping (String) -> Void = { UIPasteboard.general.string = $0 }
    ) {
        self.worktree = worktree
        self.repository = repository
        self.highlighter = highlighter
        self.textMateEnabled = textMateEnabled
        self.fallbackHighlighter = fallbackHighlighter
        self.findDebounce = findDebounce
        self.copyToPasteboard = copyToPasteboard
        state = FilesUiState(projectName: ProjectPaths.projectName(worktree: worktree))
    }

    // MARK: Tree

    /// Loads root children once per screen.
    func start() {
        guard !started else { return }
        started = true
        loadFolder("")
    }

    func toggleDirectory(_ relativePath: String) {
        if expanded.contains(relativePath) {
            expanded.remove(relativePath)
        } else {
            expanded.insert(relativePath)
            switch folders[relativePath] {
            case nil, .failed?: loadFolder(relativePath)
            default: break
            }
        }
        rebuildTree()
    }

    func retryRoot() {
        loadFolder("")
    }

    func retryFolder(_ relativePath: String) {
        loadFolder(relativePath)
    }

    func toggleHidden() {
        state.showHidden.toggle()
        rebuildTree()
    }

    /// Reloads the root and every expanded folder in place, then re-runs an active find.
    func refresh() {
        let paths = [""] + expanded.sorted()
        for path in paths {
            if case .loaded = folders[path] {
                loadFolder(path, silently: true)
            } else {
                loadFolder(path)
            }
        }
        if state.isFinding {
            runFind(query: state.query, debounce: false)
        }
    }

    func isExpanded(_ relativePath: String) -> Bool {
        expanded.contains(relativePath)
    }

    private func loadFolder(_ path: String, silently: Bool = false) {
        folderTasks[path]?.cancel()
        if !silently {
            folders[path] = .loading
            if path.isEmpty { state.rootPhase = .loading }
            rebuildTree()
        }
        let worktree = worktree
        let repository = repository
        folderTasks[path] = Task { [weak self] in
            do {
                let entries = try await repository.listEntries(worktree: worktree, relativeDirectory: path)
                guard !Task.isCancelled, let self else { return }
                self.folders[path] = .loaded(entries)
                if path.isEmpty { self.state.rootPhase = .loaded }
            } catch {
                guard !Task.isCancelled, let self else { return }
                if case .loaded = self.folders[path], silently {
                    // Keep the visible listing on a failed refresh.
                } else {
                    self.folders[path] = .failed
                    if path.isEmpty { self.state.rootPhase = .failed }
                }
            }
            self?.folderTasks[path] = nil
            self?.rebuildTree()
        }
    }

    private func rebuildTree() {
        var rows: [FilesTreeRow] = []
        appendRows(parent: "", depth: 0, into: &rows)
        state.treeRows = rows
    }

    private func appendRows(parent: String, depth: Int, into rows: inout [FilesTreeRow]) {
        guard case let .loaded(entries) = folders[parent] else { return }
        for entry in ProjectFilesListingPolicy.visible(entries, showHidden: state.showHidden) {
            let path = entry.relativePath
            let isOpen = entry.isDirectory && expanded.contains(path)
            let load = folders[path]
            rows.append(.entry(entry, depth: depth, isExpanded: isOpen, isLoading: isOpen && load == .loading))
            guard isOpen else { continue }
            switch load {
            case let .loaded(children):
                if ProjectFilesListingPolicy.visible(children, showHidden: state.showHidden).isEmpty {
                    rows.append(.emptyFolder(parentPath: path, depth: depth + 1))
                } else {
                    appendRows(parent: path, depth: depth + 1, into: &rows)
                }
            case .failed:
                rows.append(.folderError(parentPath: path, depth: depth + 1))
            case .loading, nil:
                break
            }
        }
    }

    // MARK: Find

    /// Debounced server find. Clearing the query returns to the tree with its expanded state.
    func setQuery(_ query: String) {
        guard query != state.query else { return }
        state.query = query
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            findTask?.cancel()
            findTask = nil
            state.find = .idle
            return
        }
        runFind(query: query, debounce: true)
    }

    func retryFind() {
        guard state.isFinding else { return }
        runFind(query: state.query, debounce: false)
    }

    private func runFind(query: String, debounce: Bool) {
        findTask?.cancel()
        state.find = .searching
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let worktree = worktree
        let repository = repository
        let delay = debounce ? findDebounce : .zero
        findTask = Task { [weak self] in
            if delay > .zero {
                try? await Task.sleep(for: delay)
            }
            guard !Task.isCancelled else { return }
            do {
                let results = try await repository.findFiles(
                    worktree: worktree,
                    query: trimmed,
                    limit: ProjectFileLimits.findLimit
                )
                guard !Task.isCancelled, let self else { return }
                self.state.find = .results(results)
            } catch {
                guard !Task.isCancelled, let self else { return }
                self.state.find = .failed
            }
        }
    }

    // MARK: Viewer

    /// Opens (or activates) the file's tab and loads its content once.
    func openFile(_ relativePath: String) {
        let name = ProjectPaths.name(of: relativePath)
        if !state.tabs.contains(where: { $0.relativePath == relativePath }) {
            state.tabs.append(FilesViewerTab(relativePath: relativePath, name: name))
        }
        state.activePath = relativePath
        if let viewer = state.viewers[relativePath], viewer.body != .failed { return }
        loadFile(relativePath)
    }

    func selectTab(_ relativePath: String) {
        guard state.tabs.contains(where: { $0.relativePath == relativePath }) else { return }
        state.activePath = relativePath
    }

    /// Closing the active tab activates the last remaining tab.
    func closeTab(_ relativePath: String) {
        state.tabs.removeAll { $0.relativePath == relativePath }
        state.viewers[relativePath] = nil
        fileTasks[relativePath]?.cancel()
        fileTasks[relativePath] = nil
        highlightTasks[relativePath]?.cancel()
        highlightTasks[relativePath] = nil
        if state.activePath == relativePath {
            state.activePath = state.tabs.last?.relativePath
        }
    }

    func retryFile(_ relativePath: String) {
        loadFile(relativePath)
    }

    func toggleWrap() {
        state.wrapLines.toggle()
    }

    func toggleMarkdownSource(_ relativePath: String) {
        setMarkdownSource(relativePath, showsSource: !(state.viewers[relativePath]?.showsMarkdownSource ?? false))
    }

    func setMarkdownSource(_ relativePath: String, showsSource: Bool) {
        state.viewers[relativePath]?.showsMarkdownSource = showsSource
    }

    func copyPath(_ relativePath: String) {
        copyToPasteboard(relativePath)
        state.snackbar = OpenCodeSnackbarMessage(text: Self.copiedPathMessage)
    }

    func consumeSnackbar() {
        state.snackbar = nil
    }

    private func loadFile(_ path: String) {
        fileTasks[path]?.cancel()
        highlightTasks[path]?.cancel()
        highlightTasks[path] = nil
        let name = ProjectPaths.name(of: path)
        state.viewers[path] = ProjectFileViewerState(relativePath: path, name: name)
        let worktree = worktree
        let repository = repository
        fileTasks[path] = Task { [weak self] in
            let body: ProjectFileViewerBody
            var preview: FileMarkdownPreview?
            do {
                let content = try await repository.readFile(worktree: worktree, relativePath: path)
                body = await Self.viewerBody(for: content, fileName: name)
                if case let .text(document) = body, ProjectPaths.isMarkdown(name: name) {
                    preview = await Self.markdownPreview(document.text)
                }
            } catch {
                body = .failed
            }
            guard !Task.isCancelled, let self, self.state.viewers[path] != nil else { return }
            self.state.viewers[path]?.body = body
            self.state.viewers[path]?.markdownPreview = preview
            self.fileTasks[path] = nil
            if case let .text(document) = body {
                self.startHighlighting(path, document: document)
            }
        }
    }

    private static func viewerBody(for content: ProjectFileContent, fileName: String) async -> ProjectFileViewerBody {
        switch content {
        case .empty:
            return .empty
        case let .binary(mimeType, _):
            return .binary(mimeType: mimeType)
        case let .tooLarge(byteCount):
            return .tooLarge(byteCount: byteCount)
        case let .image(data, mimeType):
            return .image(data, mimeType: mimeType)
        case let .text(text, isTruncated, _):
            if text.isEmpty { return .empty }
            let document = await Task.detached(priority: .userInitiated) {
                FileTextDocument(text: text, fileName: fileName, isTruncated: isTruncated)
            }.value
            return .text(document)
        }
    }

    private static func markdownPreview(_ text: String) async -> FileMarkdownPreview {
        let document = await Task.detached(priority: .userInitiated) {
            OpenCodeRenderedMarkdownParser.parse(text, variant: .standalone)
        }.value
        return FileMarkdownPreview(document: document)
    }

    // MARK: Highlighting

    /// Colors the whole file in one pass off the main actor, after the plain text is showing:
    /// the shared TextMate engine (grammar state carries across the file), or the semantic
    /// fallback when TextMate is off or its result is not attachable. Closing the tab or
    /// reloading the file cancels the pass.
    private func startHighlighting(_ path: String, document: FileTextDocument) {
        guard let language = document.language else { return }
        highlightTasks[path]?.cancel()
        let textMate: (any TextMateHighlighting)? = textMateEnabled() ? highlighter : nil
        highlightRevision += 1
        let revision = highlightRevision
        let policyEpoch = Int64(MarkdownHighlightPreferences.currentEpoch())
        let fallbackHighlighter = fallbackHighlighter
        highlightTasks[path] = Task { [weak self] in
            let spans = await Self.lineSpans(
                document: document,
                language: language.id,
                textMate: textMate,
                revision: revision,
                policyEpoch: policyEpoch,
                fallback: fallbackHighlighter
            )
            guard let spans, !Task.isCancelled, let self,
                  self.state.viewers[path]?.textDocument == document
            else { return }
            self.state.viewers[path]?.lineSpans = spans
            self.highlightTasks[path] = nil
        }
    }

    /// Outcomes Session Chat code fences do not attach (`attachTextMateResult`).
    nonisolated static let unattachableOutcomes: Set<String> = ["Failed", "Cancelled", "BudgetExceeded"]

    /// nil when cancelled.
    @concurrent
    private nonisolated static func lineSpans(
        document: FileTextDocument,
        language: String,
        textMate: (any TextMateHighlighting)?,
        revision: Int64,
        policyEpoch: Int64,
        fallback: @Sendable (String, String) -> [NativeCodeSpan]
    ) async -> [Int: [NativeCodeSpan]]? {
        let source = document.lines.joined(separator: "\n")
        var spans: [NativeCodeSpan]?
        if let textMate {
            let request = TextMateHighlightRequest(
                key: TextMateCodeBlockKey(value: "files:\(document.id.uuidString)"),
                revision: TextMateHighlightRevision(value: revision),
                language: language,
                source: source,
                intent: .interactiveFinal,
                policyEpoch: policyEpoch,
                messageCharacters: source.utf16.count,
                fenceIndex: 0,
                fenceCount: 1
            )
            if let result = try? await textMate.highlight(request),
               result.source == source,
               !unattachableOutcomes.contains(result.outcome) {
                spans = result.spans
            }
        }
        guard !Task.isCancelled else { return nil }
        let resolved = spans ?? fallback(language, source)
        guard !Task.isCancelled else { return nil }
        return FileCodeHighlightMapper.lineSpans(resolved, lines: document.lines[...], firstLine: 0)
    }
}
