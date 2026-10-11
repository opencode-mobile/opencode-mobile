import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct FilesViewModelTests {
    @Test func startLoadsRootSortedWithHiddenFiltered() async {
        let repository = FakeProjectFilesRepository()
        repository.listings[""] = [
            entry("README.md"), entry(".git", dir: true), entry("src", dir: true), entry(".env"),
        ]
        let viewModel = makeViewModel(repository)

        viewModel.start()
        viewModel.start()
        await waitUntil { viewModel.state.rootPhase == .loaded }

        #expect(names(viewModel) == ["src", "README.md"])
        #expect(viewModel.state.projectName == "repo")
        #expect(repository.listCalls == [""])
    }

    @Test func toggleHiddenShowsDotEntries() async {
        let repository = FakeProjectFilesRepository()
        repository.listings[""] = [entry("README.md"), entry(".github", dir: true)]
        let viewModel = makeViewModel(repository)
        viewModel.start()
        await waitUntil { viewModel.state.rootPhase == .loaded }

        viewModel.toggleHidden()

        #expect(names(viewModel) == [".github", "README.md"])
        viewModel.toggleHidden()
        #expect(names(viewModel) == ["README.md"])
    }

    @Test func expandLoadsChildrenLazilyOnceAndKeepsNestedState() async {
        let repository = FakeProjectFilesRepository()
        repository.listings[""] = [entry("src", dir: true)]
        repository.listings["src"] = [entry("src/ui", dir: true), entry("src/a.swift")]
        repository.listings["src/ui"] = [entry("src/ui/View.swift")]
        let viewModel = makeViewModel(repository)
        viewModel.start()
        await waitUntil { viewModel.state.rootPhase == .loaded }

        repository.listGate = FilesAsyncGate()
        viewModel.toggleDirectory("src")
        #expect(viewModel.state.treeRows.first == .entry(entry("src", dir: true), depth: 0, isExpanded: true, isLoading: true))
        repository.listGate?.open()
        await waitUntil { names(viewModel).count == 3 }
        repository.listGate = nil
        #expect(names(viewModel) == ["src", "ui", "a.swift"])

        viewModel.toggleDirectory("src/ui")
        await waitUntil { names(viewModel).count == 4 }
        #expect(depths(viewModel) == [0, 1, 2, 1])

        viewModel.toggleDirectory("src")
        #expect(names(viewModel) == ["src"])
        viewModel.toggleDirectory("src")
        #expect(names(viewModel) == ["src", "ui", "View.swift", "a.swift"])
        #expect(repository.listCalls == ["", "src", "src/ui"])
    }

    @Test func emptyFolderAndChildFailureRows() async {
        let repository = FakeProjectFilesRepository()
        repository.listings[""] = [entry("empty", dir: true), entry("broken", dir: true)]
        repository.listings["empty"] = []
        repository.failingDirectories = ["broken"]
        let viewModel = makeViewModel(repository)
        viewModel.start()
        await waitUntil { viewModel.state.rootPhase == .loaded }

        viewModel.toggleDirectory("empty")
        viewModel.toggleDirectory("broken")
        await waitUntil { viewModel.state.treeRows.count == 4 }

        #expect(viewModel.state.treeRows.contains(.folderError(parentPath: "broken", depth: 1)))
        #expect(viewModel.state.treeRows.contains(.emptyFolder(parentPath: "empty", depth: 1)))

        repository.failingDirectories = []
        repository.listings["broken"] = [entry("broken/a.txt")]
        viewModel.retryFolder("broken")
        await waitUntil { names(viewModel).contains("a.txt") }
        #expect(!viewModel.state.treeRows.contains(.folderError(parentPath: "broken", depth: 1)))
    }

    @Test func rootFailureThenRetry() async {
        let repository = FakeProjectFilesRepository()
        repository.failingDirectories = [""]
        let viewModel = makeViewModel(repository)
        viewModel.start()
        await waitUntil { viewModel.state.rootPhase == .failed }

        repository.failingDirectories = []
        repository.listings[""] = [entry("a.txt")]
        viewModel.retryRoot()
        await waitUntil { viewModel.state.rootPhase == .loaded }
        #expect(names(viewModel) == ["a.txt"])
    }

    @Test func findDebouncesAndClearingRestoresTree() async {
        let repository = FakeProjectFilesRepository()
        repository.listings[""] = [entry("src", dir: true)]
        repository.listings["src"] = [entry("src/a.swift")]
        repository.findResults = [entry("src/a.swift")]
        let viewModel = makeViewModel(repository)
        viewModel.start()
        await waitUntil { viewModel.state.rootPhase == .loaded }
        viewModel.toggleDirectory("src")
        await waitUntil { names(viewModel).count == 2 }

        viewModel.setQuery("a")
        viewModel.setQuery("ab")
        #expect(viewModel.state.isFinding)
        #expect(viewModel.state.find == .searching)
        await waitUntil { viewModel.state.find == .results([entry("src/a.swift")]) }
        #expect(repository.findQueries == ["ab"])
        #expect(repository.findLimits == [50])

        viewModel.setQuery("")
        #expect(viewModel.state.find == .idle)
        #expect(!viewModel.state.isFinding)
        #expect(names(viewModel) == ["src", "a.swift"])
    }

    @Test func findWithNoResults() async {
        let repository = FakeProjectFilesRepository()
        repository.findResults = []
        let viewModel = makeViewModel(repository)
        viewModel.setQuery("zzz")
        await waitUntil { viewModel.state.find == .results([]) }
        #expect(viewModel.state.query == "zzz")
    }

    @Test func tabsOpenSelectAndClose() async {
        let repository = FakeProjectFilesRepository()
        let viewModel = makeViewModel(repository)

        viewModel.openFile("a.txt")
        viewModel.openFile("src/b.txt")
        viewModel.openFile("c.txt")
        #expect(viewModel.state.tabs.map(\.relativePath) == ["a.txt", "src/b.txt", "c.txt"])
        #expect(viewModel.state.tabs.map(\.name) == ["a.txt", "b.txt", "c.txt"])
        #expect(viewModel.state.activePath == "c.txt")

        viewModel.openFile("a.txt")
        #expect(viewModel.state.tabs.count == 3)
        #expect(viewModel.state.activePath == "a.txt")

        viewModel.selectTab("src/b.txt")
        #expect(viewModel.state.activePath == "src/b.txt")

        viewModel.closeTab("a.txt")
        #expect(viewModel.state.activePath == "src/b.txt")
        viewModel.closeTab("src/b.txt")
        #expect(viewModel.state.activePath == "c.txt")
        viewModel.closeTab("c.txt")
        #expect(viewModel.state.activePath == nil)
        #expect(viewModel.state.tabs.isEmpty)
        #expect(viewModel.state.viewers.isEmpty)
    }

    @Test func openFileCachesContentForInstantTabSwitch() async {
        let repository = FakeProjectFilesRepository()
        repository.contents["a.swift"] = .text("let a = 1\n", isTruncated: false, totalBytes: 10)
        repository.contents["b.txt"] = .text("b", isTruncated: false, totalBytes: 1)
        let viewModel = makeViewModel(repository)

        viewModel.openFile("a.swift")
        await waitUntil { viewModel.state.viewers["a.swift"]?.textDocument != nil }
        viewModel.openFile("b.txt")
        await waitUntil { viewModel.state.viewers["b.txt"]?.textDocument != nil }
        viewModel.selectTab("a.swift")
        viewModel.openFile("a.swift")

        #expect(repository.readCalls == ["a.swift", "b.txt"])
        let document = viewModel.state.activeViewer?.textDocument
        #expect(document?.lines == ["let a = 1"])
        #expect(document?.metaText == "Swift · 1 line")
    }

    @Test func viewerBodiesForBinaryImageEmptyAndMarkdown() async {
        let repository = FakeProjectFilesRepository()
        repository.contents["a.bin"] = .binary(mimeType: "application/zip", byteCount: 4)
        repository.contents["a.png"] = .image(Data([1, 2]), mimeType: "image/png")
        repository.contents["empty.txt"] = .empty
        repository.contents["README.md"] = .text("# Title\n\nBody", isTruncated: false, totalBytes: 13)
        let viewModel = makeViewModel(repository)

        for path in ["a.bin", "a.png", "empty.txt", "README.md"] {
            viewModel.openFile(path)
        }
        await waitUntil { viewModel.state.viewers["README.md"]?.markdownPreview != nil }
        await waitUntil { viewModel.state.viewers.values.allSatisfy { $0.body != .loading } }

        #expect(viewModel.state.viewers["a.bin"]?.body == .binary(mimeType: "application/zip"))
        #expect(viewModel.state.viewers["a.png"]?.body == .image(Data([1, 2]), mimeType: "image/png"))
        #expect(viewModel.state.viewers["empty.txt"]?.body == .empty)
        let markdown = viewModel.state.viewers["README.md"]
        #expect(markdown?.isMarkdown == true)
        #expect(markdown?.showsMarkdownPreview == true)

        viewModel.toggleMarkdownSource("README.md")
        #expect(viewModel.state.viewers["README.md"]?.showsMarkdownPreview == false)
        #expect(viewModel.state.viewers["README.md"]?.showsMarkdownSource == true)
    }

    @Test func fileErrorThenRetry() async {
        let repository = FakeProjectFilesRepository()
        repository.failingFiles = ["a.txt"]
        let viewModel = makeViewModel(repository)

        viewModel.openFile("a.txt")
        await waitUntil { viewModel.state.viewers["a.txt"]?.body == .failed }

        repository.failingFiles = []
        repository.contents["a.txt"] = .text("ok", isTruncated: false, totalBytes: 2)
        viewModel.retryFile("a.txt")
        await waitUntil { viewModel.state.viewers["a.txt"]?.textDocument != nil }
        #expect(viewModel.state.viewers["a.txt"]?.textDocument?.lines == ["ok"])
    }

    @Test func truncatedTextKeepsFlag() async {
        let repository = FakeProjectFilesRepository()
        repository.contents["big.log"] = .text("a\nb", isTruncated: true, totalBytes: 5_000_000)
        let viewModel = makeViewModel(repository)
        viewModel.openFile("big.log")
        await waitUntil { viewModel.state.viewers["big.log"]?.textDocument != nil }
        #expect(viewModel.state.viewers["big.log"]?.textDocument?.isTruncated == true)
    }

    @Test func wrapToggleAndCopyPath() async {
        var copied: [String] = []
        let viewModel = FilesViewModel(
            worktree: "/repo",
            repository: FakeProjectFilesRepository(),
            highlighter: FakeHighlighter(),
            findDebounce: .milliseconds(10),
            copyToPasteboard: { copied.append($0) }
        )
        #expect(!viewModel.state.wrapLines)
        viewModel.toggleWrap()
        #expect(viewModel.state.wrapLines)

        viewModel.copyPath("src/a.swift")
        #expect(copied == ["src/a.swift"])
        #expect(viewModel.state.snackbar?.text == "Path copied")
        viewModel.consumeSnackbar()
        #expect(viewModel.state.snackbar == nil)
    }

    @Test func visibleLinesHighlightOncePerChunk() async {
        let repository = FakeProjectFilesRepository()
        repository.contents["a.swift"] = .text("let a = 1\nlet b = 2", isTruncated: false, totalBytes: 19)
        let highlighter = FakeHighlighter()
        let viewModel = makeViewModel(repository, highlighter: highlighter)
        viewModel.openFile("a.swift")
        await waitUntil { viewModel.state.viewers["a.swift"]?.textDocument != nil }

        viewModel.codeLineAppeared("a.swift", line: 0)
        viewModel.codeLineAppeared("a.swift", line: 1)
        await waitUntil { viewModel.state.viewers["a.swift"]?.lineSpans.count == 2 }

        #expect(highlighter.requests.count == 1)
        #expect(highlighter.requests.first?.language == "swift")
        #expect(highlighter.requests.first?.source == "let a = 1\nlet b = 2")
        #expect(viewModel.state.viewers["a.swift"]?.lineSpans[0] == [NativeCodeSpan(start: 0, end: 3, role: .keyword)])
        #expect(viewModel.state.viewers["a.swift"]?.lineSpans[1] == [NativeCodeSpan(start: 0, end: 3, role: .keyword)])
    }

    @Test func textMateOffUsesSemanticFallbackInsteadOfEngine() async {
        let repository = FakeProjectFilesRepository()
        repository.contents["a.kt"] = .text("val a = 1\nval b = 2", isTruncated: false, totalBytes: 19)
        let highlighter = FakeHighlighter()
        let fallbackCalls = FallbackRecorder()
        let viewModel = makeViewModel(
            repository,
            highlighter: highlighter,
            textMateEnabled: false,
            fallbackHighlighter: { language, source in
                fallbackCalls.record(language: language, source: source)
                return [NativeCodeSpan(start: 0, end: 3, role: .keyword), NativeCodeSpan(start: 10, end: 13, role: .keyword)]
            }
        )
        viewModel.openFile("a.kt")
        await waitUntil { viewModel.state.viewers["a.kt"]?.textDocument != nil }

        viewModel.codeLineAppeared("a.kt", line: 0)
        await waitUntil { viewModel.state.viewers["a.kt"]?.lineSpans.count == 2 }

        #expect(highlighter.requests.isEmpty)
        #expect(fallbackCalls.calls.count == 1)
        #expect(fallbackCalls.calls.first?.language == "kotlin")
        #expect(fallbackCalls.calls.first?.source == "val a = 1\nval b = 2")
        #expect(viewModel.state.viewers["a.kt"]?.lineSpans[0] == [NativeCodeSpan(start: 0, end: 3, role: .keyword)])
        #expect(viewModel.state.viewers["a.kt"]?.lineSpans[1] == [NativeCodeSpan(start: 0, end: 3, role: .keyword)])
    }

    @Test func plainTextIsNeverSentToHighlighter() async {
        let repository = FakeProjectFilesRepository()
        repository.contents["LICENSE"] = .text("MIT", isTruncated: false, totalBytes: 3)
        let highlighter = FakeHighlighter()
        let viewModel = makeViewModel(repository, highlighter: highlighter)
        viewModel.openFile("LICENSE")
        await waitUntil { viewModel.state.viewers["LICENSE"]?.textDocument != nil }

        viewModel.codeLineAppeared("LICENSE", line: 0)
        await Task.yield()
        #expect(highlighter.requests.isEmpty)
    }

    // MARK: Helpers

    private func makeViewModel(
        _ repository: FakeProjectFilesRepository,
        highlighter: FakeHighlighter = FakeHighlighter(),
        textMateEnabled: Bool = true,
        fallbackHighlighter: @escaping @Sendable (String, String) -> [NativeCodeSpan] = { _, _ in [] }
    ) -> FilesViewModel {
        FilesViewModel(
            worktree: "/Users/demo/repo",
            repository: repository,
            highlighter: highlighter,
            textMateEnabled: { textMateEnabled },
            fallbackHighlighter: fallbackHighlighter,
            findDebounce: .milliseconds(20),
            copyToPasteboard: { _ in }
        )
    }

    private func entry(_ path: String, dir: Bool = false, ignored: Bool = false) -> ProjectEntry {
        ProjectEntry(name: ProjectPaths.name(of: path), relativePath: path, isDirectory: dir, isIgnored: ignored)
    }

    private func names(_ viewModel: FilesViewModel) -> [String] {
        viewModel.state.treeRows.compactMap { row in
            if case let .entry(entry, _, _, _) = row { return entry.name }
            return nil
        }
    }

    private func depths(_ viewModel: FilesViewModel) -> [Int] {
        viewModel.state.treeRows.compactMap { row in
            if case let .entry(_, depth, _, _) = row { return depth }
            return nil
        }
    }

    private func waitUntil(_ condition: @MainActor () -> Bool) async {
        for _ in 0..<400 {
            if condition() { return }
            try? await Task.sleep(for: .milliseconds(5))
        }
        Issue.record("condition not met in time")
    }
}

/// One-shot gate a fake call awaits until the test opens it.
private final class FilesAsyncGate: @unchecked Sendable {
    private let lock = NSLock()
    private var isOpen = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func wait() async {
        await withCheckedContinuation { continuation in
            lock.lock()
            if isOpen {
                lock.unlock()
                continuation.resume()
            } else {
                waiters.append(continuation)
                lock.unlock()
            }
        }
    }

    func open() {
        lock.lock()
        isOpen = true
        let pending = waiters
        waiters = []
        lock.unlock()
        pending.forEach { $0.resume() }
    }
}

private final class FakeProjectFilesRepository: ProjectFilesRepository, @unchecked Sendable {
    var listings: [String: [ProjectEntry]] = [:]
    var failingDirectories: Set<String> = []
    var findResults: [ProjectEntry] = []
    var contents: [String: ProjectFileContent] = [:]
    var failingFiles: Set<String> = []
    var listGate: FilesAsyncGate?

    private(set) var listCalls: [String] = []
    private(set) var findQueries: [String] = []
    private(set) var findLimits: [Int] = []
    private(set) var readCalls: [String] = []

    func listEntries(worktree _: String, relativeDirectory: String) async throws -> [ProjectEntry] {
        await MainActor.run { listCalls.append(relativeDirectory) }
        if let listGate { await listGate.wait() }
        if failingDirectories.contains(relativeDirectory) { throw ProjectFilesError.server(statusCode: 500) }
        return listings[relativeDirectory] ?? []
    }

    func findFiles(worktree _: String, query: String, limit: Int) async throws -> [ProjectEntry] {
        await MainActor.run {
            findQueries.append(query)
            findLimits.append(limit)
        }
        return findResults
    }

    func readFile(worktree _: String, relativePath: String) async throws -> ProjectFileContent {
        await MainActor.run { readCalls.append(relativePath) }
        if failingFiles.contains(relativePath) { throw ProjectFilesError.notFound }
        return contents[relativePath] ?? .empty
    }
}

private final class FallbackRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: [(language: String, source: String)] = []

    var calls: [(language: String, source: String)] {
        lock.lock()
        defer { lock.unlock() }
        return stored
    }

    func record(language: String, source: String) {
        lock.lock()
        stored.append((language, source))
        lock.unlock()
    }
}

private final class FakeHighlighter: TextMateHighlighting, @unchecked Sendable {
    private let lock = NSLock()
    private var storedRequests: [TextMateHighlightRequest] = []

    var requests: [TextMateHighlightRequest] {
        lock.lock()
        defer { lock.unlock() }
        return storedRequests
    }

    func highlight(_ request: TextMateHighlightRequest) async throws -> TextMateHighlightResult {
        lock.lock()
        storedRequests.append(request)
        lock.unlock()
        // Color every `let` keyword.
        var spans: [NativeCodeSpan] = []
        let utf16 = Array(request.source.utf16)
        let keyword = Array("let".utf16)
        var index = 0
        while index + keyword.count <= utf16.count {
            if Array(utf16[index..<index + keyword.count]) == keyword {
                spans.append(NativeCodeSpan(start: index, end: index + keyword.count, role: .keyword))
            }
            index += 1
        }
        return TextMateHighlightResult(
            key: request.key,
            revision: request.revision,
            source: request.source,
            canonicalLanguage: request.language,
            policyEpoch: request.policyEpoch,
            spans: spans,
            outcome: "Highlighted",
            diagnostics: [],
            observation: NativeTextMateParseObservation(
                outcome: "Highlighted",
                messageCharacters: request.messageCharacters,
                fenceCharacters: request.source.utf16.count,
                fenceLines: 1,
                fenceIndex: request.fenceIndex,
                fenceCount: request.fenceCount,
                spanCount: spans.count,
                totalNanoseconds: 1,
                stages: [],
                diagnosticKinds: []
            )
        )
    }
}
