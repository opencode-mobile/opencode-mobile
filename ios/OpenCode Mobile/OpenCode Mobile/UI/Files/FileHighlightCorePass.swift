import Foundation
import OpenCodeMarkdownCore

/// The lines one highlight-core call colored in an open file.
nonisolated struct FileHighlightChunk: Sendable {
    let status: HighlightCoreFenceStatus
    let startLine: Int
    /// Lines `[startLine, settledEndLine)` are colored; a colored line with no span maps to `[]`.
    let settledEndLine: Int
    let lineSpans: [Int: [NativeCodeSpan]]
}

/// One open file's highlight-core session (#872): the display text, its line starts, and the
/// end state of every line lexed so far. Calls run one at a time on the file's own queue, apart
/// from chat's, so a large file never delays a chat fence.
nonisolated final class HighlightCoreFileSession: @unchecked Sendable {
    let lineCount: Int
    private let session: HighlightSession
    private let lines: [String]
    private let cancelFlag = HighlightCancelFlag()
    private let queue = DispatchQueue(label: "dev.opencode.mobile.highlight-core.file")

    /// nil when highlight-core has no lexer for `language`.
    init?(document: FileTextDocument, language: String) {
        guard HighlightCore.shared.supports(language: language), !document.lines.isEmpty else { return nil }
        session = HighlightSession(language: language, limits: HighlightLimits.companion.standard)
        lines = document.lines
        lineCount = document.lines.count
    }

    /// Stops the call in progress and every later one.
    func cancel() {
        cancelFlag.cancel()
    }

    /// Indexes the display text and colors lines `[0, endLine)`.
    func open(endLine: Int, deadlineMilliseconds: Double) async -> FileHighlightChunk {
        await run(qos: .userInitiated) { [self] in
            let text = lines.joined(separator: "\n")
            return chunk(
                session.open(
                    text: text,
                    endLine: Int32(clamping: endLine),
                    cancel: cancelFlag,
                    deadline: HighlightDeadline.companion.after(milliseconds: deadlineMilliseconds)
                )
            )
        }
    }

    /// Colors `lines`. Lines below what earlier calls reached first get their start state from
    /// the state-only pass, which emits no spans.
    func highlight(_ range: Range<Int>, deadlineMilliseconds: Double, qos: DispatchQoS) async -> FileHighlightChunk {
        await run(qos: qos) { [self] in
            chunk(
                session.highlight(
                    startLine: Int32(clamping: range.lowerBound),
                    endLine: Int32(clamping: range.upperBound),
                    cancel: cancelFlag,
                    deadline: HighlightDeadline.companion.after(milliseconds: deadlineMilliseconds)
                )
            )
        }
    }

    private func run(qos: DispatchQoS, _ body: @escaping @Sendable () -> FileHighlightChunk) async -> FileHighlightChunk {
        await withCheckedContinuation { continuation in
            queue.async(qos: qos) { continuation.resume(returning: body()) }
        }
    }

    private func chunk(_ result: HighlightCallResult) -> FileHighlightChunk {
        let start = Int(result.startLine)
        let status: HighlightCoreFenceStatus
        let end: Int
        switch result.status {
        case .complete:
            status = .complete
            end = Int(result.endLine)
        case .partial:
            status = .partial
            end = Int(result.highlightedEndLine)
        case .cancelled:
            return FileHighlightChunk(status: .cancelled, startLine: start, settledEndLine: start, lineSpans: [:])
        default:
            return FileHighlightChunk(status: .failed, startLine: start, settledEndLine: start, lineSpans: [:])
        }
        guard end > start else {
            return FileHighlightChunk(status: status, startLine: start, settledEndLine: start, lineSpans: [:])
        }
        let base = Int(session.lineStart(line: Int32(start)))
        let spans = HighlightCoreSpans.codeSpans(HighlightCoreSpans.packedInts(result.spans), offsetBy: -base)
        return FileHighlightChunk(
            status: status,
            startLine: start,
            settledEndLine: end,
            lineSpans: FileCodeHighlightMapper.lineSpans(spans, lines: lines[start..<end], firstLine: start)
        )
    }
}

/// Colors one open file with highlight-core: the first screen on open, then the rows the lazy
/// stack shows (a jump far down colors at once, through the state-only pass), then the rest in
/// background chunks with a deadline each. Visible rows are served before the next chunk.
@MainActor
final class FileHighlightCorePass {
    nonisolated struct Configuration: Sendable {
        var firstScreenLines = 80
        /// Lines colored on each side of a row that appears uncolored.
        var visibleLinesAround = 64
        var chunkLines = 2_000
        var visibleDeadlineMilliseconds = 16.0
        var chunkDeadlineMilliseconds = 8.0
        /// Starts with the background fill held; tests release it with `holdsBackgroundFill`.
        var holdsBackgroundFill = false

        static let standard = Configuration()
    }

    nonisolated struct Served: Equatable, Sendable {
        enum Kind: Equatable, Sendable {
            case firstScreen
            case visible
            case background
        }

        let kind: Kind
        let lines: Range<Int>
        let settledEndLine: Int
    }

    let session: HighlightCoreFileSession
    let configuration: Configuration
    private let apply: @MainActor ([Int: [NativeCodeSpan]]) -> Void
    private var colored = IndexSet()
    private var visibleRequests: [Int] = []
    private var fillLine = 0
    private var task: Task<Void, Never>?
    private var idle: CheckedContinuation<Void, Never>?
    private var started = ContinuousClock.now

    /// Calls in the order they ran, for tests and timing logs.
    private(set) var served: [Served] = []
    private(set) var firstColorDuration: Duration?
    private(set) var fullColorDuration: Duration?
    private(set) var isFinished = false

    /// While true only the first screen and visible rows are colored; tests use it to look at
    /// what visible rows color on their own.
    var holdsBackgroundFill: Bool {
        didSet { if !holdsBackgroundFill { wake() } }
    }

    init(
        session: HighlightCoreFileSession,
        configuration: Configuration = .standard,
        apply: @escaping @MainActor ([Int: [NativeCodeSpan]]) -> Void
    ) {
        self.session = session
        self.configuration = configuration
        self.apply = apply
        holdsBackgroundFill = configuration.holdsBackgroundFill
    }

    func start() {
        guard task == nil else { return }
        started = .now
        task = Task { [weak self] in await self?.run() }
    }

    func cancel() {
        session.cancel()
        task?.cancel()
        wake()
    }

    func isColored(_ line: Int) -> Bool {
        colored.contains(line)
    }

    /// A row appeared in the lazy stack; an uncolored one is colored before the next chunk.
    func lineAppeared(_ line: Int) {
        guard !isFinished, !colored.contains(line) else { return }
        visibleRequests.append(line)
        if visibleRequests.count > 256 { visibleRequests.removeFirst(visibleRequests.count - 256) }
        wake()
    }

    private func wake() {
        idle?.resume()
        idle = nil
    }

    private func run() async {
        let lineCount = session.lineCount
        let first = await session.open(
            endLine: configuration.firstScreenLines,
            deadlineMilliseconds: configuration.visibleDeadlineMilliseconds
        )
        guard record(first, kind: .firstScreen, lines: 0..<min(configuration.firstScreenLines, lineCount)) else { return }
        while !Task.isCancelled, colored.count < lineCount {
            if let line = nextVisibleRequest() {
                let lines = max(0, line - configuration.visibleLinesAround)
                    ..< min(lineCount, line + configuration.visibleLinesAround)
                let chunk = await session.highlight(
                    lines,
                    deadlineMilliseconds: configuration.visibleDeadlineMilliseconds,
                    qos: .userInitiated
                )
                guard record(chunk, kind: .visible, lines: lines) else { return }
                if !colored.contains(line) { visibleRequests.append(line) }
            } else if holdsBackgroundFill {
                await withCheckedContinuation { idle = $0 }
            } else {
                while colored.contains(fillLine) { fillLine += 1 }
                let lines = fillLine..<min(lineCount, fillLine + configuration.chunkLines)
                let chunk = await session.highlight(
                    lines,
                    deadlineMilliseconds: configuration.chunkDeadlineMilliseconds,
                    qos: .utility
                )
                guard record(chunk, kind: .background, lines: lines) else { return }
                await Task.yield()
            }
        }
        guard !Task.isCancelled else { return }
        isFinished = true
        let duration = ContinuousClock.now - started
        fullColorDuration = duration
        DiagnosticLog.info(
            .session,
            "highlight-core file: \(lineCount) lines fully colored in \(Self.milliseconds(duration)) ms, \(served.count) calls"
        )
    }

    /// false when the pass should stop: cancelled, or the lexer failed (the rest stays plain).
    private func record(_ chunk: FileHighlightChunk, kind: Served.Kind, lines: Range<Int>) -> Bool {
        guard !Task.isCancelled, chunk.status != .cancelled, chunk.status != .failed else { return false }
        served.append(Served(kind: kind, lines: lines, settledEndLine: chunk.settledEndLine))
        guard chunk.settledEndLine > chunk.startLine else { return true }
        colored.insert(integersIn: chunk.startLine..<chunk.settledEndLine)
        apply(chunk.lineSpans)
        let duration = ContinuousClock.now - started
        if firstColorDuration == nil {
            firstColorDuration = duration
            DiagnosticLog.info(
                .session,
                "highlight-core file: first \(chunk.settledEndLine) lines colored in \(Self.milliseconds(duration)) ms"
            )
        } else if kind == .visible {
            DiagnosticLog.info(
                .session,
                "highlight-core file: visible lines \(chunk.startLine)..<\(chunk.settledEndLine) colored at \(Self.milliseconds(duration)) ms"
            )
        }
        return true
    }

    private func nextVisibleRequest() -> Int? {
        while let line = visibleRequests.popLast() {
            if !colored.contains(line) { return line }
        }
        return nil
    }

    private static func milliseconds(_ duration: Duration) -> String {
        let value = Double(duration.components.seconds) * 1_000 + Double(duration.components.attoseconds) / 1e15
        return String(format: "%.1f", value)
    }
}
