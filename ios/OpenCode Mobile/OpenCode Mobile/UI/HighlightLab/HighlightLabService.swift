#if HIGHLIGHT_LAB
import Foundation
import OpenCodeMarkdownCore

/// The Lab's seam to the shared `highlight-bench` module: Screen Models see only Lab product
/// types, and every engine call runs off the main actor.
nonisolated protocol HighlightLabService: Sendable {
    var engines: [HighlightLabEngineOption] { get }
    var dataSets: [HighlightLabDataSetInfo] { get }
    /// Languages with bundled data sets, in catalog order.
    var languages: [String] { get }
    var sizeClasses: [HighlightLabSizeClass] { get }
    /// Characters per catalog data set, by id. Generates every text once, so it takes a moment.
    func characterCounts() async -> [String: Int]
    /// One timed call with spans. The first call per engine is cold; later ones are warm.
    func highlight(_ input: HighlightLabInput, engineID: String) async -> HighlightLabProbeResult
    func agreement(length: Int, baseline: [HighlightLabSpan], candidate: [HighlightLabSpan]) async -> HighlightLabAgreement
    /// Starts a run on its own thread; `onProgress` arrives on that thread.
    func runBenchmark(
        _ request: HighlightLabBenchmarkRequest,
        onProgress: @escaping @Sendable (HighlightLabProgress) -> Void
    ) -> any HighlightLabBenchmarkRun
}

nonisolated protocol HighlightLabBenchmarkRun: AnyObject, Sendable {
    /// Cancels the call in progress and skips the remaining cases.
    func stop()
    /// The final report, once the run returns (stopped runs return a partial one).
    func report() async -> HighlightLabReport
}

/// Runs work on a new thread with a main-thread-sized stack: deep worst cases overflow the
/// 512 KB default of secondary threads.
nonisolated enum HighlightLabThread {
    static let stackSizeBytes = 8 << 20

    static func run<T: Sendable>(_ name: String, _ work: @escaping @Sendable () -> T) async -> T {
        await withCheckedContinuation { continuation in
            start(name) { continuation.resume(returning: work()) }
        }
    }

    static func start(_ name: String, _ work: @escaping @Sendable () -> Void) {
        let thread = Thread(block: work)
        thread.name = name
        thread.stackSize = stackSizeBytes
        thread.qualityOfService = .userInitiated
        thread.start()
    }

    static func callGuard() -> HighlightBenchThreadGuard {
        HighlightBenchThreadGuard(stackSizeBytes: Int64(stackSizeBytes))
    }
}

nonisolated final class KMPHighlightLabService: HighlightLabService, @unchecked Sendable {
    static let shared = KMPHighlightLabService()

    let engines: [HighlightLabEngineOption]
    let dataSets: [HighlightLabDataSetInfo]
    let languages: [String]
    let sizeClasses: [HighlightLabSizeClass]

    private let lock = NSLock()
    /// Warm instances per engine id; taken out while a call uses one.
    private var warm: [String: any HighlightBenchHighlighter] = [:]
    private var counts: [String: Int]?

    init() {
        engines = HighlightBenchEngines.shared.available().map { HighlightLabEngineOption(id: $0.id, name: $0.displayName) }
        dataSets = HighlightBenchCatalog.shared.dataSets.map(Self.info)
        var seen = Set<String>()
        languages = dataSets.map(\.language).filter { seen.insert($0).inserted }
        sizeClasses = HighlightBenchSizeClass.entries.map { HighlightLabSizeClass(id: $0.name, label: $0.label) }
    }

    func characterCounts() async -> [String: Int] {
        if let cached = lock.withLock({ counts }) { return cached }
        let computed = await HighlightLabThread.run("highlight-lab-counts") {
            var result: [String: Int] = [:]
            for dataSet in HighlightBenchCatalog.shared.dataSets {
                result[dataSet.id] = (dataSet.generate() as NSString).length
            }
            return result
        }
        lock.withLock { counts = computed }
        return computed
    }

    func highlight(_ input: HighlightLabInput, engineID: String) async -> HighlightLabProbeResult {
        await HighlightLabThread.run("highlight-lab-probe") { [self] in
            let (language, source) = text(input)
            let engine = HighlightBenchEngines.shared.engine(id: engineID)
            let reused = lock.withLock { warm.removeValue(forKey: engineID) }
            guard let highlighter = reused ?? engine?.doNewHighlighter() else {
                let timing = HighlightLabTiming(engineName: engineID, elapsedMs: 0, characters: source.utf16.count, status: "Unknown engine", outcome: nil, spanCount: 0, cold: true)
                return HighlightLabProbeResult(source: source, language: language, spans: nil, timing: timing)
            }
            let call = HighlightBenchProbe.shared.highlight(
                highlighter: highlighter,
                language: language,
                source: source,
                settings: HighlightBenchSettings.companion.standard,
                callGuard: HighlightLabThread.callGuard()
            )
            // A call the guard gave up on still owns its instance.
            if call.status != .abandoned, call.status != .forcestopped {
                lock.withLock { if warm[engineID] == nil { warm[engineID] = highlighter } }
            }
            let timing = HighlightLabTiming(
                engineName: engine?.displayName ?? engineID,
                elapsedMs: call.elapsedMs,
                characters: source.utf16.count,
                status: call.status.name,
                outcome: call.outcome,
                spanCount: Int(call.spanCount),
                cold: reused == nil
            )
            let spans = call.spans?.map { HighlightLabSpan(start: Int($0.start), end: Int($0.end), role: $0.role) }
            return HighlightLabProbeResult(source: source, language: language, spans: spans, timing: timing)
        }
    }

    func agreement(length: Int, baseline: [HighlightLabSpan], candidate: [HighlightLabSpan]) async -> HighlightLabAgreement {
        await HighlightLabThread.run("highlight-lab-diff") {
            let comparison = HighlightBenchSpanDiff.shared.compare(
                length: Int32(length),
                baseline: baseline.map(Self.kotlinSpan),
                candidate: candidate.map(Self.kotlinSpan)
            )
            return HighlightLabAgreement(
                agreement: comparison.agreement,
                differingCharacters: Int(comparison.differingCharacters),
                differingRanges: comparison.differingRanges.map { Int($0.start)..<Int($0.end) }
            )
        }
    }

    func runBenchmark(
        _ request: HighlightLabBenchmarkRequest,
        onProgress: @escaping @Sendable (HighlightLabProgress) -> Void
    ) -> any HighlightLabBenchmarkRun {
        let available = HighlightBenchEngines.shared.available()
        let engines = request.engineIDs.compactMap { id in available.first { $0.id == id } }
        let cases: [HighlightBenchCase] =
            switch request.plan {
            case .quick: HighlightBenchPlan.shared.cases(tier: .quick)
            case .full: HighlightBenchPlan.shared.cases(tier: .full)
            case let .dataSets(ids): HighlightBenchPlan.shared.cases(dataSets: ids.compactMap { HighlightBenchCatalog.shared.dataSet(id: $0) })
            }
        let run = HighlightBenchRun(
            engines: engines.isEmpty ? available : engines,
            cases: cases,
            settings: HighlightBenchSettings.companion.standard,
            callGuard: HighlightLabThread.callGuard()
        )
        let handle = KMPHighlightLabBenchmarkRun(run: run)
        let title = request.title
        let environment = request.environment
        HighlightLabThread.start("highlight-lab-benchmark") {
            let report = run.run { progress in
                onProgress(HighlightLabReportConverter.progress(progress, title: title))
            }
            handle.finish(HighlightLabReportConverter.report(report, title: title, environment: environment, stopped: run.isStopped))
        }
        return handle
    }

    /// The text and language of an input, generated on the calling thread.
    private func text(_ input: HighlightLabInput) -> (language: String, source: String) {
        switch input {
        case let .catalog(id):
            guard let dataSet = HighlightBenchCatalog.shared.dataSet(id: id) else { return ("plaintext", "") }
            return (dataSet.language, dataSet.generate())
        case let .custom(text):
            return (text.language, text.source)
        }
    }

    private static func info(_ dataSet: HighlightBenchDataSet) -> HighlightLabDataSetInfo {
        HighlightLabDataSetInfo(
            id: dataSet.id,
            displayName: dataSet.displayName,
            language: dataSet.language,
            sizeClass: HighlightLabSizeClass(id: dataSet.sizeClass.name, label: dataSet.sizeClass.label),
            kind: kind(dataSet.kind)
        )
    }

    private static func kind(_ kind: HighlightBenchDataKind) -> HighlightLabDataKind {
        switch kind {
        case .typicalcode: .typicalCode
        case .chatfences: .chatFences
        case .worstcase: .worstCase
        default: .custom
        }
    }

    private static func kotlinSpan(_ span: HighlightLabSpan) -> HighlightBenchSpan {
        HighlightBenchSpan(start: Int32(span.start), end: Int32(span.end), role: span.role)
    }
}

private nonisolated final class KMPHighlightLabBenchmarkRun: HighlightLabBenchmarkRun, @unchecked Sendable {
    private let run: HighlightBenchRun
    private let lock = NSLock()
    private var finished: HighlightLabReport?
    private var waiters: [CheckedContinuation<HighlightLabReport, Never>] = []

    init(run: HighlightBenchRun) {
        self.run = run
    }

    func stop() {
        run.stop()
    }

    func report() async -> HighlightLabReport {
        await withCheckedContinuation { continuation in
            let ready: HighlightLabReport? = lock.withLock {
                if let finished { return finished }
                waiters.append(continuation)
                return nil
            }
            if let ready { continuation.resume(returning: ready) }
        }
    }

    func finish(_ report: HighlightLabReport) {
        let pending = lock.withLock {
            finished = report
            defer { waiters.removeAll() }
            return waiters
        }
        pending.forEach { $0.resume(returning: report) }
    }
}

/// Turns the shared report into Lab tables, Markdown, and JSON.
nonisolated enum HighlightLabReportConverter {
    static func progress(_ progress: HighlightBenchProgress, title: String) -> HighlightLabProgress {
        let engineCount = max(1, progress.report.engines.count)
        let index = Int(progress.index)
        let total = Int(progress.total)
        return HighlightLabProgress(
            caseNumber: index / engineCount + 1,
            caseCount: total / engineCount,
            caseTitle: "\(progress.case_.title) · \(progress.case_.section.name)",
            engineName: progress.engineName,
            completedSteps: index + (progress.result == nil ? 0 : 1),
            totalSteps: total,
            tables: tables(progress.report, title: title)
        )
    }

    /// A stopped run fills its remaining rows as skipped, so the shared report counts it complete.
    static func report(_ report: HighlightBenchReport, title: String, environment: [String], stopped: Bool) -> HighlightLabReport {
        HighlightLabReport(
            tables: tables(report, title: title, stopped: stopped),
            markdown: report.markdown(title: title, environment: environment),
            json: report.json()
        )
    }

    static func tables(_ report: HighlightBenchReport, title: String, stopped: Bool = false) -> HighlightLabReportTables {
        let sections = report.sections.map { section in
            HighlightLabReportTables.Table(
                id: section.name,
                title: report.sectionTitle(section: section),
                headers: report.headers(section: section),
                rows: report.results(section: section).map { result in
                    HighlightLabReportTables.Row(
                        id: "\(result.case_.id)|\(result.engineId)",
                        cells: result.cells,
                        status: result.status.name
                    )
                }
            )
        }
        return HighlightLabReportTables(
            title: title,
            engines: report.engines.map(\.displayName),
            complete: report.isComplete && !stopped,
            sections: sections,
            comparison: report.engines.count > 1 ? comparison(report) : nil
        )
    }

    private static func comparison(_ report: HighlightBenchReport) -> HighlightLabReportTables.Table {
        let baseline = report.engines.first?.displayName ?? "Baseline"
        let rows = report.comparisons().map { comparison in
            let label = comparison.baseline.measurement?.headlineLabel ?? "ms"
            return HighlightLabReportTables.Row(
                id: "\(comparison.case_.id)|\(comparison.candidate.engineId)",
                cells: [
                    comparison.case_.title,
                    report.sectionTitle(section: comparison.case_.section),
                    comparison.candidate.engineName,
                    label,
                    milliseconds(comparison.baselineMs),
                    milliseconds(comparison.candidateMs),
                    comparison.timeRatio.map { String(format: "%.2f×", $0.doubleValue) } ?? "–",
                ],
                status: comparison.candidate.status.name
            )
        }
        return HighlightLabReportTables.Table(
            id: "comparison",
            title: "Comparison (baseline \(baseline); ratio = candidate / baseline)",
            headers: ["Case", "Section", "Candidate", "Measure", baseline, "Candidate ms", "Ratio"],
            rows: rows
        )
    }

    private static func milliseconds(_ value: KotlinDouble?) -> String {
        value.map { String(format: "%.2f", $0.doubleValue) } ?? "–"
    }
}
#endif
