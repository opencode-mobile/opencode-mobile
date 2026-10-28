#if HIGHLIGHT_LAB
import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct HighlightLabDataSetsModelTests {
    @Test func filtersByLanguageSizeAndKind() {
        let model = HighlightLabDataSetsModel(service: FakeHighlightLabService())
        #expect(model.state.rows.map(\.id) == ["typical/kotlin/2k", "typical/kotlin/16k", "typical/json/2k", "fences/kotlin", "worst/deep"])

        model.setLanguage("kotlin")
        #expect(model.state.rows.map(\.id) == ["typical/kotlin/2k", "typical/kotlin/16k", "fences/kotlin"])
        model.setSizeClass("Medium")
        #expect(model.state.rows.map(\.id) == ["typical/kotlin/16k"])
        model.setSizeClass(nil)
        model.setKind(.chatFences)
        #expect(model.state.rows.map(\.id) == ["fences/kotlin"])
        model.setLanguage(nil)
        model.setKind(.worstCase)
        #expect(model.state.rows.map(\.id) == ["worst/deep"])
    }

    @Test func rowsShowCharacterCountsOnceCounted() async {
        let model = HighlightLabDataSetsModel(service: FakeHighlightLabService())
        #expect(model.state.rows.first?.characters == nil)
        #expect(model.state.rows.first?.subtitle == "Typical code · Counting characters…")

        await model.start()

        #expect(model.state.rows.first?.characters == 2_000)
        #expect(model.state.rows.first?.subtitle == "Typical code · 2,000 characters")
    }
}

@MainActor
struct HighlightLabViewerModelTests {
    private func makeModel(_ service: FakeHighlightLabService, _ input: HighlightLabInput = .catalog(id: "typical/kotlin/2k")) -> HighlightLabViewerModel {
        HighlightLabViewerModel(input: input, service: service, backgroundPoll: .milliseconds(10))
    }

    @Test func opensAsPlainTextWithoutRunningAnEngine() async throws {
        let service = FakeHighlightLabService()
        let model = makeModel(service)
        #expect(model.state.title == "kotlin 2K")
        #expect(model.state.phase == .loading)

        await model.start()

        #expect(model.state.phase == .plain)
        #expect(model.state.timing == nil)
        let rendered = try #require(model.state.rendered)
        #expect(rendered.lines == ["let a = 1", "let b = 2", ""])
        #expect(rendered.lineSpans.values.allSatisfy { $0.isEmpty })
        #expect(model.state.engines.map(\.id) == ["a", "b"])
        #expect(model.state.engines.allSatisfy { $0.isEnabled })
        #expect(service.calls.isEmpty)
    }

    @Test func aTappedEngineRendersAndShowsItsTiming() async throws {
        let service = FakeHighlightLabService()
        let model = makeModel(service)
        await model.start()

        model.select("a")
        #expect(model.state.phase == .rendering(engineID: "a"))
        try await until { model.state.phase == .highlighted(engineID: "a") }
        #expect(model.state.timing?.engineName == "Engine A")
        #expect(model.state.timing?.cold == true)
        #expect(model.state.rendered?.lineSpans[0] == [NativeCodeSpan(start: 0, end: 3, role: .keyword)])

        model.select("b")
        try await until { model.state.phase == .highlighted(engineID: "b") }
        #expect(model.state.timing?.engineName == "Engine B")

        model.select("a")
        try await until { model.state.phase == .highlighted(engineID: "a") }
        #expect(model.state.timing?.cold == false)
        #expect(service.calls == ["a:text", "b:text", "a:text"])
    }

    @Test func showPlainDropsTheResult() async throws {
        let model = makeModel(FakeHighlightLabService())
        await model.start()
        model.select("a")
        try await until { model.state.phase == .highlighted(engineID: "a") }

        model.showPlain()

        #expect(model.state.phase == .plain)
        #expect(model.state.timing == nil)
        #expect(model.state.rendered?.lineSpans.values.allSatisfy { $0.isEmpty } == true)
    }

    @Test func anUnsupportedEngineIsDisabledWithItsReason() async {
        let text = HighlightLabCustomText(id: "paste-1", displayName: "Pasted json", language: "json", source: "{}")
        let service = FakeHighlightLabService()
        let model = makeModel(service, .custom(text))
        await model.start()

        #expect(model.state.engines.first { $0.id == "b" }?.unsupportedReason == "No Engine B lexer for json")
        model.select("b")
        #expect(model.state.phase == .plain)
        #expect(service.calls.isEmpty)
    }

    @Test func cancelReturnsToPlainTextAtOnce() async throws {
        let service = FakeHighlightLabService(slowEngines: ["a"])
        let model = makeModel(service)
        await model.start()
        model.select("a")
        try await until { service.calls == ["a:text"] }

        model.cancel()

        #expect(model.state.phase == .plain)
        #expect(model.state.timing == nil)
        #expect(model.state.rendered?.lineSpans.values.allSatisfy { $0.isEmpty } == true)
        try await until { service.cancelledRuns == 1 }
        #expect(model.state.phase == .plain)
    }

    @Test func anotherEngineMidRenderCancelsTheRunningOne() async throws {
        let service = FakeHighlightLabService(slowEngines: ["a"])
        let model = makeModel(service)
        await model.start()
        model.select("a")

        model.select("b")

        #expect(model.state.phase == .rendering(engineID: "b"))
        try await until { model.state.phase == .highlighted(engineID: "b") }
        try await until { service.cancelledRuns == 1 }
        #expect(model.state.timing?.engineName == "Engine B")
    }

    @Test func aRunThatIgnoresCancelIsReportedUntilItReturns() async throws {
        let service = FakeHighlightLabService(slowEngines: ["a"], ignoresCancel: true)
        let model = makeModel(service)
        await model.start()
        model.select("a")
        try await until { service.calls == ["a:text"] }

        model.cancel()

        #expect(model.state.phase == .plain)
        try await until { model.state.backgroundCalls == 1 }
        service.finishBackgroundCalls()
        try await until { model.state.backgroundCalls == 0 }
    }

    @Test func customTextTitlesAfterItsName() {
        let text = HighlightLabCustomText(id: "paste-1", displayName: "Pasted kotlin", language: "kotlin", source: "val a = 1")
        let model = HighlightLabViewerModel(input: .custom(text), service: FakeHighlightLabService())
        #expect(model.state.title == "Pasted kotlin")
        #expect(model.state.subtitle == "kotlin · 9 characters")
    }
}

@MainActor
struct HighlightLabBenchmarkModelTests {
    @Test func enginesKeepPickOrderAndTheFirstIsTheBaseline() throws {
        let model = HighlightLabBenchmarkModel(service: FakeHighlightLabService(), storage: try Self.storage(), environment: { [] })
        #expect(model.state.engineOrder == ["a", "b"])
        model.reverseEngines()
        #expect(model.state.engineOrder == ["b", "a"])
        model.toggleEngine("b")
        #expect(model.state.engineOrder == ["a"])
        model.toggleEngine("b")
        #expect(model.state.engineOrder == ["a", "b"])
        model.toggleEngine("a")
        model.toggleEngine("b")
        #expect(!model.state.canRun)
    }

    @Test func selectedModeNeedsDataSets() throws {
        let model = HighlightLabBenchmarkModel(service: FakeHighlightLabService(), storage: try Self.storage(), environment: { [] })
        model.setMode(.selected)
        #expect(!model.state.canRun)
        model.toggleDataSet("worst/deep")
        #expect(model.state.canRun)
        model.toggleDataSet("worst/deep")
        #expect(!model.state.canRun)
    }

    @Test func runShowsProgressThenSavesTheReport() async throws {
        let service = FakeHighlightLabService()
        let storage = try Self.storage()
        let model = HighlightLabBenchmarkModel(service: service, storage: storage, environment: { ["Device: test"] })
        model.setMode(.selected)
        model.toggleDataSet("worst/deep")
        model.toggleDataSet("typical/kotlin/2k")

        model.start()

        let run = try #require(service.lastRun)
        #expect(service.lastRequest?.plan == .dataSets(["typical/kotlin/2k", "worst/deep"]))
        #expect(service.lastRequest?.engineIDs == ["a", "b"])
        #expect(service.lastRequest?.environment == ["Device: test"])
        #expect(model.state.phase.isRunning)
        run.sendProgress(step: 1)
        try await until { model.state.phase == .running(run.progress(step: 1)) }
        #expect(model.state.tables?.complete == false)

        run.complete()
        try await until { model.state.phase == .idle }
        #expect(model.state.report?.markdown == "# report")
        #expect(model.state.tables?.complete == true)
        #expect(model.state.savedFiles?.markdown.lastPathComponent == "latest.md")
        #expect(try String(contentsOf: storage.latestJSON, encoding: .utf8) == "{}")

        let reopened = HighlightLabBenchmarkModel(service: service, storage: storage, environment: { [] })
        #expect(reopened.state.report == model.state.report)
    }

    @Test func stopAsksTheRunToStop() async throws {
        let service = FakeHighlightLabService()
        let model = HighlightLabBenchmarkModel(service: service, storage: try Self.storage(), environment: { [] })
        model.start()
        model.stop()
        #expect(model.state.phase == .stopping)
        #expect(service.lastRun?.stopped == true)
        service.lastRun?.complete()
        try await until { model.state.phase == .idle }
        #expect(model.state.savedFiles?.stampedMarkdown.lastPathComponent.hasSuffix("-quick-stopped.md") == true)
    }

    static func storage() throws -> HighlightLabStorage {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("highlight-lab-tests-\(UUID().uuidString)")
        return HighlightLabStorage(directory: directory)
    }
}

nonisolated struct HighlightLabRenderedTextTests {
    @Test func splitsOnNewlinesAndKeepsSourceOffsets() {
        let source = "ab\r\ncd\n\nef"
        let rendered = HighlightLabRenderedText(
            source: source,
            spans: [HighlightLabSpan(start: 1, end: 6, role: "String"), HighlightLabSpan(start: 8, end: 10, role: "NotARole")]
        )
        #expect(rendered.lines == ["ab", "cd", "", "ef"])
        #expect(rendered.lineStarts == [0, 4, 7, 8])
        #expect(rendered.lineSpans[0] == [NativeCodeSpan(start: 1, end: 2, role: .string)])
        #expect(rendered.lineSpans[1] == [NativeCodeSpan(start: 0, end: 2, role: .string)])
        #expect(rendered.lineSpans[3] == [])
    }

    @Test func marksSplitAcrossLines() {
        let rendered = HighlightLabRenderedText(source: "abc\ndef\nghi", spans: nil)
        #expect(rendered.lineMarks([2..<5, 9..<11]) == [0: [2..<3], 1: [0..<1], 2: [1..<3]])
    }

    @Test func longLinesAreCutAndTheirSpansClipped() {
        let limit = FileTextDocument.maxDisplayLineLength
        let rendered = HighlightLabRenderedText(
            source: String(repeating: "x", count: limit + 10),
            spans: [HighlightLabSpan(start: limit - 2, end: limit + 5, role: "Keyword"), HighlightLabSpan(start: limit + 1, end: limit + 3, role: "Keyword")]
        )
        #expect(rendered.lines[0].utf16.count == limit)
        #expect(rendered.lineSpans[0] == [NativeCodeSpan(start: limit - 2, end: limit, role: .keyword)])
    }
}

nonisolated struct HighlightLabTimingTests {
    private func timing(_ status: String, ms: Double = 12_000) -> HighlightLabTiming {
        HighlightLabTiming(engineName: "highlight-core", elapsedMs: ms, characters: 100_000, status: status, outcome: nil, spanCount: 0, cold: true)
    }

    @Test func rateOnlyForCompletedCalls() {
        #expect(timing("Completed", ms: 10).charsPerMs == 10_000)
        #expect(timing("CancelHonored").charsPerMs == nil)
        #expect(timing("Abandoned").charsPerMs == nil)
    }

    @Test func callsGivenUpOnShowALowerBound() {
        #expect(timing("Abandoned").elapsedLabel == "> 12000 ms")
        #expect(timing("ForceStopped").elapsedLabel == "> 12000 ms")
        #expect(timing("CancelHonored", ms: 10_018).elapsedLabel == "10018 ms")
        #expect(timing("Completed", ms: 14.3).elapsedLabel == "14.30 ms")
    }
}

@MainActor
struct OpenCodeCodeFileTopLineTests {
    @Test func offsetMapsToTheLineAtTheTop() {
        #expect(OpenCodeCodeFile.topLine(offset: 0, lineCount: 100) == 0)
        #expect(OpenCodeCodeFile.topLine(offset: 8, lineCount: 100) == 0)
        #expect(OpenCodeCodeFile.topLine(offset: 8 + 12 * 20, lineCount: 100) == 12)
        #expect(OpenCodeCodeFile.topLine(offset: 8 + 12 * 20 + 4, lineCount: 100) == 12)
        #expect(OpenCodeCodeFile.topLine(offset: 100_000, lineCount: 100) == 99)
        #expect(OpenCodeCodeFile.topLine(offset: -50, lineCount: 100) == 0)
        #expect(OpenCodeCodeFile.topLine(offset: 40, lineCount: 0) == 0)
    }
}

nonisolated struct HighlightLabStorageTests {
    @Test func fileSafeLabels() {
        #expect(HighlightLabStorage.fileSafe("typical/kotlin/16k,worst/x:engines=highlight-core") == "typical_kotlin_16k_worst_x_engines_highlight-core")
        #expect(HighlightLabStorage.stamp(Date(timeIntervalSince1970: 0)) == "19700101-000000")
    }

    @Test func savesLatestAndStampedCopies() throws {
        let storage = HighlightLabStorage(directory: FileManager.default.temporaryDirectory.appendingPathComponent("highlight-lab-storage-\(UUID().uuidString)"))
        let report = FakeHighlightLabService.report(complete: true)
        let files = try storage.save(report, label: "quick", at: Date(timeIntervalSince1970: 60))
        #expect(files.stampedMarkdown.lastPathComponent == "19700101-000100-quick.md")
        #expect(files.stampedJSON.lastPathComponent == "19700101-000100-quick.json")
        #expect(try String(contentsOf: files.stampedMarkdown, encoding: .utf8) == "# report")
        #expect(storage.loadLatest() == report)
    }

    @Test func headlessValueComesAfterTheFlag() {
        #expect(HighlightLabHeadless.value(in: ["app", "-labRun", "quick:engines=highlight-core"]) == "quick:engines=highlight-core")
        #expect(HighlightLabHeadless.value(in: ["app", "-labRun"]) == nil)
        #expect(HighlightLabHeadless.value(in: ["app"]) == nil)
    }

    @Test func screenLaunchArguments() {
        #expect(HighlightLabScreenLaunch.parse(["app", "-labScreen", "benchmark", "-labAutoRun", "YES"]) == .benchmark(autoRun: true))
        #expect(HighlightLabScreenLaunch.parse(["app", "-labScreen", "compare"]) == nil)
        #expect(HighlightLabScreenLaunch.parse(["app", "-labScreen", "benchmark"]) == .benchmark(autoRun: false))
        #expect(HighlightLabScreenLaunch.parse(["app", "-labScreen", "viewer=typical/kotlin/16k"]) == .viewer("typical/kotlin/16k"))
        #expect(HighlightLabScreenLaunch.parse(["app", "-labScreen", "nope"]) == nil)
    }

    @Test func labServiceOffersHighlightCoreOnly() {
        #expect(KMPHighlightLabService.shared.engines.map(\.id) == ["highlight-core"])
    }
}

/// Waits for `condition`, yielding to queued main-actor work; fails after about two seconds.
@MainActor
func until(_ condition: () -> Bool) async throws {
    for _ in 0..<200 {
        if condition() { return }
        try await Task.sleep(for: .milliseconds(10))
    }
    Issue.record("Condition not met in time")
}

nonisolated final class FakeHighlightLabService: HighlightLabService, @unchecked Sendable {
    let engines = [HighlightLabEngineOption(id: "a", name: "Engine A"), HighlightLabEngineOption(id: "b", name: "Engine B")]
    let dataSets = [
        FakeHighlightLabService.info("typical/kotlin/2k", "kotlin 2K", "kotlin", ("Small", "2K"), .typicalCode),
        FakeHighlightLabService.info("typical/kotlin/16k", "kotlin 16K", "kotlin", ("Medium", "16K"), .typicalCode),
        FakeHighlightLabService.info("typical/json/2k", "json 2K", "json", ("Small", "2K"), .typicalCode),
        FakeHighlightLabService.info("fences/kotlin", "kotlin chat fences", "kotlin", ("Chat", "Chat fences"), .chatFences),
        FakeHighlightLabService.info("worst/deep", "deep nesting", "json", ("Huge", "1 MB"), .worstCase),
    ]
    let languages = ["kotlin", "json"]
    let sizeClasses = [HighlightLabSizeClass(id: "Small", label: "2K"), HighlightLabSizeClass(id: "Medium", label: "16K")]

    private let lock = NSLock()
    private var callLog: [String] = []
    private var usedEngines = Set<String>()
    /// Engines whose viewer calls wait until cancelled.
    private let slowEngines: Set<String>
    /// A cancelled slow call keeps running, like an engine that ignores cancellation.
    private let ignoresCancel: Bool
    private var cancelled = 0
    private var busy = 0

    init(slowEngines: Set<String> = [], ignoresCancel: Bool = false) {
        self.slowEngines = slowEngines
        self.ignoresCancel = ignoresCancel
    }

    var cancelledRuns: Int { lock.withLock { cancelled } }
    var backgroundCalls: Int { lock.withLock { busy } }

    func finishBackgroundCalls() {
        lock.withLock { busy = 0 }
    }
    private var runs: [FakeRun] = []
    private var requests: [HighlightLabBenchmarkRequest] = []

    var calls: [String] { lock.withLock { callLog } }
    var lastRun: FakeRun? { lock.withLock { runs.last } }
    var lastRequest: HighlightLabBenchmarkRequest? { lock.withLock { requests.last } }

    static let source = "let a = 1\nlet b = 2\n"

    func characterCounts() async -> [String: Int] {
        ["typical/kotlin/2k": 2_000, "typical/kotlin/16k": 16_000]
    }

    func highlight(_ input: HighlightLabInput, engineID: String) async -> HighlightLabProbeResult {
        let cold = lock.withLock {
            callLog.append("\(engineID):\(input.id.hasPrefix("custom/") ? input.id : "catalog/\(input.id)")")
            return usedEngines.insert(engineID).inserted
        }
        let secondRole = engineID == "a" ? "Keyword" : "Type"
        let spans = [HighlightLabSpan(start: 0, end: 3, role: "Keyword"), HighlightLabSpan(start: 10, end: 13, role: secondRole)]
        let name = engines.first { $0.id == engineID }?.name ?? engineID
        let timing = HighlightLabTiming(engineName: name, elapsedMs: 2, characters: Self.source.utf16.count, status: "Completed", outcome: "Highlighted", spanCount: spans.count, cold: cold)
        return HighlightLabProbeResult(source: Self.source, language: "kotlin", spans: spans, timing: timing)
    }

    func text(of input: HighlightLabInput) async -> HighlightLabText {
        switch input {
        case .catalog: HighlightLabText(language: "kotlin", source: Self.source)
        case let .custom(text): HighlightLabText(language: text.language, source: text.source)
        }
    }

    func highlight(_ text: HighlightLabText, engineID: String, cancellation: HighlightLabCancellation) async -> HighlightLabProbeResult {
        let (cold, slow) = lock.withLock {
            callLog.append("\(engineID):text")
            return (usedEngines.insert(engineID).inserted, slowEngines.contains(engineID))
        }
        let name = engines.first { $0.id == engineID }?.name ?? engineID
        if slow {
            while !cancellation.isCancelled {
                try? await Task.sleep(for: .milliseconds(5))
            }
            lock.withLock {
                cancelled += 1
                if ignoresCancel { busy += 1 }
            }
            let timing = HighlightLabTiming(engineName: name, elapsedMs: 0, characters: text.source.utf16.count, status: "Cancelled", outcome: nil, spanCount: 0, cold: cold)
            return HighlightLabProbeResult(source: text.source, language: text.language, spans: nil, timing: timing)
        }
        let secondRole = engineID == "a" ? "Keyword" : "Type"
        let spans = [HighlightLabSpan(start: 0, end: 3, role: "Keyword"), HighlightLabSpan(start: 10, end: 13, role: secondRole)]
        let timing = HighlightLabTiming(engineName: name, elapsedMs: 2, characters: text.source.utf16.count, status: "Completed", outcome: "Highlighted", spanCount: spans.count, cold: cold)
        return HighlightLabProbeResult(source: text.source, language: text.language, spans: spans, timing: timing)
    }

    func unsupportedReason(engineID: String, language: String) -> String? {
        engineID == "b" && language == "json" ? "No Engine B lexer for json" : nil
    }

    func runBenchmark(_ request: HighlightLabBenchmarkRequest, onProgress: @escaping @Sendable (HighlightLabProgress) -> Void) -> any HighlightLabBenchmarkRun {
        let run = FakeRun(onProgress: onProgress)
        lock.withLock {
            runs.append(run)
            requests.append(request)
        }
        return run
    }

    static func report(complete: Bool) -> HighlightLabReport {
        let table = HighlightLabReportTables.Table(
            id: "WorstCases",
            title: "Worst cases",
            headers: ["Case", "ms"],
            rows: [HighlightLabReportTables.Row(id: "timed/worst/deep|a", cells: ["deep nesting", "12.00"], status: "Completed")]
        )
        let tables = HighlightLabReportTables(title: "Highlighting Lab run", engines: ["Engine A"], complete: complete, sections: [table], comparison: nil)
        return HighlightLabReport(tables: tables, markdown: "# report", json: "{}")
    }

    static func info(
        _ id: String,
        _ name: String,
        _ language: String,
        _ size: (String, String),
        _ kind: HighlightLabDataKind
    ) -> HighlightLabDataSetInfo {
        HighlightLabDataSetInfo(id: id, displayName: name, language: language, sizeClass: HighlightLabSizeClass(id: size.0, label: size.1), kind: kind)
    }
}

nonisolated final class FakeRun: HighlightLabBenchmarkRun, @unchecked Sendable {
    private let onProgress: @Sendable (HighlightLabProgress) -> Void
    private let lock = NSLock()
    private var continuation: CheckedContinuation<HighlightLabReport, Never>?
    private var completed = false
    private var stopFlag = false

    init(onProgress: @escaping @Sendable (HighlightLabProgress) -> Void) {
        self.onProgress = onProgress
    }

    var stopped: Bool { lock.withLock { stopFlag } }

    func stop() {
        lock.withLock { stopFlag = true }
    }

    func progress(step: Int) -> HighlightLabProgress {
        HighlightLabProgress(caseNumber: 1, caseCount: 2, caseTitle: "deep nesting · WorstCases", engineName: "Engine A", completedSteps: step, totalSteps: 4, tables: FakeHighlightLabService.report(complete: false).tables)
    }

    func sendProgress(step: Int) {
        onProgress(progress(step: step))
    }

    func complete() {
        let waiting = lock.withLock {
            completed = true
            defer { continuation = nil }
            return continuation
        }
        waiting?.resume(returning: FakeHighlightLabService.report(complete: true))
    }

    func report() async -> HighlightLabReport {
        await withCheckedContinuation { continuation in
            let done = lock.withLock {
                if completed { return true }
                self.continuation = continuation
                return false
            }
            if done { continuation.resume(returning: FakeHighlightLabService.report(complete: true)) }
        }
    }
}
#endif
