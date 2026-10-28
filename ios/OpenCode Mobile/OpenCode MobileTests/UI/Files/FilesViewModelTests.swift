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

    @Test func tooLargeContentShowsTooLargeBody() async {
        let repository = FakeProjectFilesRepository()
        repository.contents["huge.json"] = .tooLarge(byteCount: nil)
        repository.contents["photo.jpg"] = .tooLarge(byteCount: 40_000_000)
        let viewModel = makeViewModel(repository)

        viewModel.openFile("huge.json")
        viewModel.openFile("photo.jpg")
        await waitUntil { viewModel.state.viewers.values.allSatisfy { $0.body != .loading } }

        #expect(viewModel.state.viewers["huge.json"]?.body == .tooLarge(byteCount: nil))
        #expect(viewModel.state.viewers["photo.jpg"]?.body == .tooLarge(byteCount: 40_000_000))
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

    @Test func plainTextShowsFirstThenOneWholeFilePassColorsIt() async {
        let repository = FakeProjectFilesRepository()
        repository.contents["a.swift"] = .text("let a = 1\nlet b = 2", isTruncated: false, totalBytes: 19)
        let highlighter = FakeHighlighter()
        let gate = FilesAsyncGate()
        highlighter.gate = gate
        let viewModel = makeViewModel(repository, highlighter: highlighter)
        viewModel.openFile("a.swift")
        await waitUntil { viewModel.state.viewers["a.swift"]?.textDocument != nil }
        await waitUntil { highlighter.requests.count == 1 }

        // Plain text is on screen while the pass runs.
        #expect(viewModel.state.viewers["a.swift"]?.lineSpans.isEmpty == true)
        gate.open()
        await waitUntil { viewModel.state.viewers["a.swift"]?.lineSpans.count == 2 }

        #expect(highlighter.requests.count == 1)
        #expect(highlighter.requests.first?.language == "swift")
        #expect(highlighter.requests.first?.source == "let a = 1\nlet b = 2")
        #expect(highlighter.requests.first?.intent == .interactiveFinal)
        #expect(viewModel.state.viewers["a.swift"]?.lineSpans[0] == [NativeCodeSpan(start: 0, end: 3, role: .keyword)])
        #expect(viewModel.state.viewers["a.swift"]?.lineSpans[1] == [NativeCodeSpan(start: 0, end: 3, role: .keyword)])
    }

    @Test func blockCommentAcrossTheOld200LineChunkBoundaryStaysCommentThroughout() async {
        var lines = (0..<195).map { "let v\($0) = \($0)" }
        lines.append("/* starts at line 195")
        lines += (196..<210).map { "   still a comment \($0)" }
        lines.append("   ends at line 210 */")
        lines += (211..<230).map { "let w\($0) = \($0)" }
        let text = lines.joined(separator: "\n")
        let repository = FakeProjectFilesRepository()
        repository.contents["big.swift"] = .text(text, isTruncated: false, totalBytes: text.utf8.count)
        let highlighter = FakeHighlighter()
        let viewModel = makeViewModel(repository, highlighter: highlighter)

        viewModel.openFile("big.swift")
        await waitUntil { viewModel.state.viewers["big.swift"]?.lineSpans.count == lines.count }

        #expect(highlighter.requests.count == 1)
        #expect(highlighter.requests.first?.source == text)
        let spans = viewModel.state.viewers["big.swift"]?.lineSpans ?? [:]
        for line in 195...210 {
            #expect(spans[line] == [NativeCodeSpan(start: 0, end: lines[line].utf16.count, role: .comment)], "line \(line)")
        }
        #expect(spans[211] == [NativeCodeSpan(start: 0, end: 3, role: .keyword)])
    }

    @Test(arguments: ["BudgetExceeded", "Failed", "Cancelled"])
    func unattachableTextMateOutcomeFallsBackForTheWholeFile(outcome: String) async {
        let repository = FakeProjectFilesRepository()
        repository.contents["a.kt"] = .text("val a = 1\nval b = 2", isTruncated: false, totalBytes: 19)
        let highlighter = FakeHighlighter()
        highlighter.outcome = outcome
        let fallbackCalls = FallbackRecorder()
        let viewModel = makeViewModel(
            repository,
            highlighter: highlighter,
            fallbackHighlighter: { language, source in
                fallbackCalls.record(language: language, source: source)
                return [NativeCodeSpan(start: 0, end: 3, role: .keyword), NativeCodeSpan(start: 10, end: 13, role: .keyword)]
            }
        )

        viewModel.openFile("a.kt")
        await waitUntil { viewModel.state.viewers["a.kt"]?.lineSpans.count == 2 }

        #expect(highlighter.requests.count == 1)
        #expect(fallbackCalls.calls.count == 1)
        #expect(fallbackCalls.calls.first?.language == "kotlin")
        #expect(fallbackCalls.calls.first?.source == "val a = 1\nval b = 2")
        #expect(viewModel.state.viewers["a.kt"]?.lineSpans[1] == [NativeCodeSpan(start: 0, end: 3, role: .keyword)])
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
        await waitUntil { viewModel.state.viewers["a.kt"]?.lineSpans.count == 2 }

        #expect(highlighter.requests.isEmpty)
        #expect(fallbackCalls.calls.count == 1)
        #expect(fallbackCalls.calls.first?.language == "kotlin")
        #expect(fallbackCalls.calls.first?.source == "val a = 1\nval b = 2")
        #expect(viewModel.state.viewers["a.kt"]?.lineSpans[0] == [NativeCodeSpan(start: 0, end: 3, role: .keyword)])
        #expect(viewModel.state.viewers["a.kt"]?.lineSpans[1] == [NativeCodeSpan(start: 0, end: 3, role: .keyword)])
    }

    @Test func closingTheTabCancelsThePendingPass() async {
        let repository = FakeProjectFilesRepository()
        repository.contents["a.swift"] = .text("let a = 1", isTruncated: false, totalBytes: 9)
        let highlighter = FakeHighlighter()
        highlighter.outcome = "BudgetExceeded"
        let gate = FilesAsyncGate()
        highlighter.gate = gate
        let fallbackCalls = FallbackRecorder()
        let viewModel = makeViewModel(
            repository,
            highlighter: highlighter,
            fallbackHighlighter: { language, source in
                fallbackCalls.record(language: language, source: source)
                return []
            }
        )
        viewModel.openFile("a.swift")
        await waitUntil { highlighter.requests.count == 1 }

        viewModel.closeTab("a.swift")
        gate.open()
        try? await Task.sleep(for: .milliseconds(50))

        #expect(viewModel.state.viewers["a.swift"] == nil)
        #expect(fallbackCalls.calls.isEmpty)
    }

    @Test func megabyteFileStaysResponsiveWhileHighlighting() async {
        let line = String(repeating: "x", count: 99)
        let text = Array(repeating: "let a = \(line)", count: 9_500).joined(separator: "\n") // ~1 MiB
        let repository = FakeProjectFilesRepository()
        repository.contents["big.swift"] = .text(text, isTruncated: false, totalBytes: text.utf8.count)
        let highlighter = FakeHighlighter()
        let gate = FilesAsyncGate()
        highlighter.gate = gate
        let viewModel = makeViewModel(repository, highlighter: highlighter)

        viewModel.openFile("big.swift")
        await waitUntil { viewModel.state.viewers["big.swift"]?.textDocument != nil }
        // The main actor keeps serving the screen while the pass is pending.
        viewModel.toggleWrap()
        #expect(viewModel.state.wrapLines)
        #expect(viewModel.state.viewers["big.swift"]?.textDocument?.lines.count == 9_500)

        gate.open()
        await waitUntil(iterations: 4_000) { viewModel.state.viewers["big.swift"]?.lineSpans.count == 9_500 }
        #expect(viewModel.state.viewers["big.swift"]?.lineSpans[9_499] == [NativeCodeSpan(start: 0, end: 3, role: .keyword)])
    }

    @Test func plainTextIsNeverSentToHighlighter() async {
        let repository = FakeProjectFilesRepository()
        repository.contents["LICENSE"] = .text("MIT", isTruncated: false, totalBytes: 3)
        let highlighter = FakeHighlighter()
        let viewModel = makeViewModel(repository, highlighter: highlighter)
        viewModel.openFile("LICENSE")
        await waitUntil { viewModel.state.viewers["LICENSE"]?.textDocument != nil }

        try? await Task.sleep(for: .milliseconds(20))
        #expect(highlighter.requests.isEmpty)
        #expect(viewModel.state.viewers["LICENSE"]?.lineSpans.isEmpty == true)
    }

    // MARK: Helpers

    private func makeViewModel(
        _ repository: FakeProjectFilesRepository,
        highlighter: FakeHighlighter = FakeHighlighter(),
        textMateEnabled: Bool = true,
        fallbackHighlighter: @escaping @Sendable (String, String) -> [NativeCodeSpan] = { _, _ in [] }
    ) -> FilesViewModel {
        let viewModel = FilesViewModel(
            worktree: "/Users/demo/repo",
            repository: repository,
            highlighter: highlighter,
            textMateEnabled: { textMateEnabled },
            fallbackHighlighter: fallbackHighlighter,
            findDebounce: .milliseconds(20),
            copyToPasteboard: { _ in }
        )
        #if HIGHLIGHT_LAB
        viewModel.highlightCoreEnabled = { false }
        #endif
        return viewModel
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

    #if HIGHLIGHT_LAB
    // MARK: highlight-core (#872)

    /// A TypeScript file whose block comment runs from line 10 to line 2985, so a line far down
    /// colors right only from the state carried down from the top.
    private static let longTypeScript: String = {
        var lines = (0..<10).map { "const value\($0) = \($0); // head" }
        lines.append("/* opened here")
        lines += (11..<2985).map { "comment body \($0) const not = code" }
        lines.append("closed */ const after = \"text\";")
        lines += (2986..<3000).map { "export function f\($0)(x: number) { return x + \($0) }" }
        return lines.joined(separator: "\n")
    }()

    private func makeHighlightCoreViewModel(
        _ repository: FakeProjectFilesRepository,
        highlighter: FakeHighlighter = FakeHighlighter(),
        holdsBackgroundFill: Bool
    ) -> FilesViewModel {
        let viewModel = makeViewModel(repository, highlighter: highlighter)
        viewModel.highlightCoreEnabled = { true }
        var configuration = FileHighlightCorePass.Configuration()
        configuration.firstScreenLines = 40
        configuration.visibleLinesAround = 20
        configuration.chunkLines = 250
        configuration.holdsBackgroundFill = holdsBackgroundFill
        viewModel.highlightCoreConfiguration = configuration
        return viewModel
    }

    /// The whole file colored in one highlight-core call, split per line.
    private func referenceLineSpans(_ source: String, language: String) -> [Int: [NativeCodeSpan]] {
        let lines = FileTextDocument.displayLines(source)
        let spans = HighlightCoreHighlightingEngineTests.oneShot(language, lines.joined(separator: "\n"))
        return FileCodeHighlightMapper.lineSpans(spans, lines: lines[...], firstLine: 0)
    }

    @Test func highlightCoreColorsTheFirstScreenFirstThenFillsTheRestInChunks() async {
        let repository = FakeProjectFilesRepository()
        let source = Self.longTypeScript
        repository.contents["big.ts"] = .text(source, isTruncated: false, totalBytes: source.utf8.count)
        let highlighter = FakeHighlighter()
        let viewModel = makeHighlightCoreViewModel(repository, highlighter: highlighter, holdsBackgroundFill: true)
        viewModel.openFile("big.ts")
        await waitUntil { viewModel.state.viewers["big.ts"]?.lineSpans.count == 40 }
        let pass = viewModel.highlightCorePass("big.ts")
        let reference = referenceLineSpans(source, language: "typescript")

        #expect(pass?.served.first == FileHighlightCorePass.Served(kind: .firstScreen, lines: 0..<40, settledEndLine: 40))
        #expect(viewModel.state.viewers["big.ts"]?.lineSpans[39] == reference[39])
        #expect(viewModel.state.viewers["big.ts"]?.lineSpans[40] == nil)
        #expect(highlighter.requests.isEmpty)

        pass?.holdsBackgroundFill = false
        await waitUntil(iterations: 1_000) { pass?.isFinished == true }
        #expect(viewModel.state.viewers["big.ts"]?.lineSpans == reference)
        let kinds = pass?.served.dropFirst().map(\.kind) ?? []
        #expect(!kinds.isEmpty && kinds.allSatisfy { $0 == .background })
        #expect(pass?.served[1].lines == 40..<290)
        #expect(pass?.firstColorDuration != nil && pass?.fullColorDuration != nil)
        #expect(highlighter.requests.isEmpty)
    }

    @Test func highlightCoreColorsAJumpFarDownBeforeTheBackgroundFillGetsThere() async {
        let repository = FakeProjectFilesRepository()
        let source = Self.longTypeScript
        repository.contents["big.ts"] = .text(source, isTruncated: false, totalBytes: source.utf8.count)
        let viewModel = makeHighlightCoreViewModel(repository, holdsBackgroundFill: true)
        viewModel.openFile("big.ts")
        await waitUntil { viewModel.state.viewers["big.ts"]?.lineSpans.count == 40 }
        let reference = referenceLineSpans(source, language: "typescript")

        // Rows near the end appear after a scroll to the bottom.
        for line in 2980..<3000 { viewModel.lineAppeared("big.ts", line: line) }
        await waitUntil { viewModel.state.viewers["big.ts"]?.lineSpans[2999] != nil }
        let spans = viewModel.state.viewers["big.ts"]?.lineSpans ?? [:]

        #expect(viewModel.highlightCorePass("big.ts")?.served.last?.kind == .visible)
        // Inside the comment that opened at line 10, then the code after it closes.
        #expect(spans[2984] == reference[2984])
        #expect(spans[2984]?.first?.role == .comment)
        #expect(spans[2985] == reference[2985])
        #expect(spans[2999] == reference[2999])
        #expect(spans[1500] == nil)
        #expect(spans.count < 200)
    }

    @Test func highlightCoreLeavesUnsupportedLanguagesToTextMate() async {
        let repository = FakeProjectFilesRepository()
        repository.contents["a.rb"] = .text("let a = 1", isTruncated: false, totalBytes: 9)
        let highlighter = FakeHighlighter()
        let viewModel = makeHighlightCoreViewModel(repository, highlighter: highlighter, holdsBackgroundFill: false)
        viewModel.openFile("a.rb")
        await waitUntil { viewModel.state.viewers["a.rb"]?.lineSpans.count == 1 }

        #expect(viewModel.highlightCorePass("a.rb") == nil)
        #expect(highlighter.requests.map(\.language) == ["ruby"])
    }

    @Test func switchingTheEngineColorsOpenFilesAgainAndClosingCancels() async {
        let repository = FakeProjectFilesRepository()
        repository.contents["a.py"] = .text("def f():\n    return 1", isTruncated: false, totalBytes: 21)
        let highlighter = FakeHighlighter()
        let viewModel = makeViewModel(repository, highlighter: highlighter)
        var usesHighlightCore = false
        viewModel.highlightCoreEnabled = { usesHighlightCore }
        viewModel.openFile("a.py")
        await waitUntil { highlighter.requests.count == 1 }
        #expect(viewModel.highlightCorePass("a.py") == nil)

        usesHighlightCore = true
        viewModel.rehighlightOpenFiles()
        await waitUntil { viewModel.highlightCorePass("a.py")?.isFinished == true }
        #expect(viewModel.state.viewers["a.py"]?.lineSpans == referenceLineSpans("def f():\n    return 1", language: "python"))

        usesHighlightCore = false
        viewModel.rehighlightOpenFiles()
        #expect(viewModel.highlightCorePass("a.py") == nil)
        await waitUntil { highlighter.requests.count == 2 }

        usesHighlightCore = true
        viewModel.rehighlightOpenFiles()
        #expect(viewModel.highlightCorePass("a.py") != nil)
        viewModel.closeTab("a.py")
        #expect(viewModel.highlightCorePass("a.py") == nil)
    }
    #endif

    private func waitUntil(iterations: Int = 400, _ condition: @MainActor () -> Bool) async {
        for _ in 0..<iterations {
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

/// Colors every `let` keyword and every `/* … */` block across the whole source, the way a
/// grammar carries comment state from line to line.
private final class FakeHighlighter: TextMateHighlighting, @unchecked Sendable {
    private let lock = NSLock()
    private var storedRequests: [TextMateHighlightRequest] = []
    var outcome = "Highlighted"
    var gate: FilesAsyncGate?

    var requests: [TextMateHighlightRequest] {
        lock.lock()
        defer { lock.unlock() }
        return storedRequests
    }

    func highlight(_ request: TextMateHighlightRequest) async throws -> TextMateHighlightResult {
        lock.lock()
        storedRequests.append(request)
        lock.unlock()
        if let gate { await gate.wait() }
        let utf16 = Array(request.source.utf16)
        var spans: [NativeCodeSpan] = []
        var index = 0
        while index < utf16.count {
            if Self.matches(utf16, at: index, "/*") {
                var end = index + 2
                while end < utf16.count, !Self.matches(utf16, at: end, "*/") { end += 1 }
                end = min(end + 2, utf16.count)
                spans.append(NativeCodeSpan(start: index, end: end, role: .comment))
                index = end
            } else if Self.matches(utf16, at: index, "let") {
                spans.append(NativeCodeSpan(start: index, end: index + 3, role: .keyword))
                index += 3
            } else {
                index += 1
            }
        }
        if outcome != "Highlighted" { spans = [] }
        return TextMateHighlightResult(
            key: request.key,
            revision: request.revision,
            source: request.source,
            canonicalLanguage: request.language,
            policyEpoch: request.policyEpoch,
            spans: spans,
            outcome: outcome,
            diagnostics: [],
            observation: NativeTextMateParseObservation(
                outcome: outcome,
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

    private static func matches(_ utf16: [UInt16], at index: Int, _ token: String) -> Bool {
        let pattern = Array(token.utf16)
        guard index + pattern.count <= utf16.count else { return false }
        return Array(utf16[index..<index + pattern.count]) == pattern
    }
}
