import Foundation
import OpenCodeMarkdownCore

/// Session Chat and File Viewer code fences colored by highlight-core (#872), the app's only
/// highlighter. Each code block key keeps a `HighlightSession`; a new revision of a fence is
/// applied as an edit from the common prefix, so a streamed fence re-lexes from its changed line
/// only. A language highlight-core does not know (or no language) comes back with no spans and
/// an `Unsupported` outcome: plain text, never an error.
///
/// Sessions are single-threaded: every call runs on `queue`, and only the cancellation flag is
/// touched from another thread.
nonisolated final class HighlightCoreHighlightingEngine: TextMateHighlighting, @unchecked Sendable {
    static let shared = HighlightCoreHighlightingEngine()

    /// Per-request deadline (`HighlightCoreLimits`, shared with Android). A fence that misses it
    /// attaches as Partial: highlighted prefix, plain rest; the next request continues it.
    static let defaultDeadlineMilliseconds = HighlightCoreLimits.shared.CHAT_DEADLINE_MILLIS

    private let sessions: HighlightCoreFenceSessions
    private let deadlineMilliseconds: Double
    private let queue = DispatchQueue(label: "dev.opencode.mobile.highlight-core", qos: .userInitiated)

    init(
        maxSessions: Int = HighlightCoreFenceSessions.defaultMaxSessions,
        maxCharacters: Int = HighlightCoreFenceSessions.defaultMaxCharacters,
        deadlineMilliseconds: Double = defaultDeadlineMilliseconds
    ) {
        sessions = HighlightCoreFenceSessions(maxSessions: maxSessions, maxCharacters: maxCharacters)
        self.deadlineMilliseconds = deadlineMilliseconds
    }

    static func supports(_ language: String?) -> Bool {
        guard let language else { return false }
        return HighlightCore.shared.supports(language: language)
    }

    func highlight(_ request: TextMateHighlightRequest) async throws -> TextMateHighlightResult {
        guard let language = request.language, Self.supports(language) else {
            return Self.unsupported(request)
        }
        let cancel = HighlightCoreCancelBox()
        if Task.isCancelled { cancel.flag.cancel() }
        let sessions = sessions
        let deadlineMilliseconds = deadlineMilliseconds
        return await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                queue.async {
                    let update = sessions.update(
                        key: request.key,
                        language: language,
                        source: request.source,
                        cancel: cancel.flag,
                        deadline: HighlightDeadline.companion.after(milliseconds: deadlineMilliseconds)
                    )
                    continuation.resume(returning: Self.result(request: request, update: update))
                }
            }
        } onCancel: {
            cancel.flag.cancel()
        }
    }

    /// Moderate keeps the newer half of the sessions and of their text; critical drops all of them.
    func trim(_ level: TextMateMemoryPressure) async {
        let sessions = sessions
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            queue.async {
                sessions.trim(level)
                continuation.resume()
            }
        }
    }

    /// Open sessions and their total UTF-16 length, for tests.
    func sessionStats() async -> (count: Int, characters: Int) {
        let sessions = sessions
        return await withCheckedContinuation { continuation in
            queue.async { continuation.resume(returning: (sessions.count, sessions.totalCharacters)) }
        }
    }

    /// No spans: the fence stays plain text.
    private static func unsupported(_ request: TextMateHighlightRequest) -> TextMateHighlightResult {
        TextMateHighlightResult(
            key: request.key,
            revision: request.revision,
            source: request.source,
            canonicalLanguage: nil,
            policyEpoch: request.policyEpoch,
            spans: [],
            outcome: "Unsupported",
            diagnostics: []
        )
    }

    private static func result(
        request: TextMateHighlightRequest,
        update: HighlightCoreFenceUpdate
    ) -> TextMateHighlightResult {
        let outcome = switch update.status {
        case .complete: update.spans.isEmpty ? "NoSpans" : "Highlighted"
        case .partial: "Partial"
        case .cancelled: "Cancelled"
        case .failed: "Failed"
        }
        return TextMateHighlightResult(
            key: request.key,
            revision: request.revision,
            source: request.source,
            canonicalLanguage: update.canonicalLanguage,
            policyEpoch: request.policyEpoch,
            spans: update.status == .cancelled || update.status == .failed ? [] : update.spans,
            outcome: outcome,
            diagnostics: []
        )
    }
}

/// The flag is set from the cancelling task's thread and read by the session on `queue`.
nonisolated private final class HighlightCoreCancelBox: @unchecked Sendable {
    let flag = HighlightCancelFlag()
}

nonisolated enum HighlightCoreFenceStatus: Equatable, Sendable {
    case complete
    case partial
    case cancelled
    case failed
}

nonisolated struct HighlightCoreFenceUpdate: Sendable {
    let status: HighlightCoreFenceStatus
    /// Spans over the whole fence, in UTF-16 offsets. Partial: text past the last span's line is plain.
    let spans: [NativeCodeSpan]
    let canonicalLanguage: String
    let lineCount: Int
    /// The first line this update lexed; the fence's line count when nothing changed.
    let relexedFromLine: Int
}

/// highlight-core sessions by code block key, bounded by count and total text length, least
/// recently used first out. Each keeps the spans of its colored prefix, so an edit only replaces
/// the spans of the lines it re-lexed. Not thread-safe: the engine confines it to its queue.
nonisolated final class HighlightCoreFenceSessions: @unchecked Sendable {
    /// Limits shared with Android (`HighlightCoreLimits`).
    static let defaultMaxSessions = Int(HighlightCoreLimits.shared.CHAT_MAX_SESSIONS)
    /// UTF-16 code units across all sessions (each holds its text twice: Kotlin and Swift).
    static let defaultMaxCharacters = Int(HighlightCoreLimits.shared.CHAT_MAX_CHARACTERS)
    /// Moderate memory pressure keeps one part in this many, of sessions and of characters.
    static let moderatePressureKeepDivisor = Int(HighlightCoreLimits.shared.MODERATE_PRESSURE_KEEP_DIVISOR)

    private final class Entry {
        let session: HighlightSession
        var source: String
        var length: Int
        /// Packed `start, length, role` spans of `[0, coloredEnd)`.
        var packed: [Int32] = []
        /// A line start or the text length: the text before it is colored.
        var coloredEnd = 0
        var lastUse: UInt64

        init(session: HighlightSession, lastUse: UInt64) {
            self.session = session
            source = ""
            length = 0
            self.lastUse = lastUse
        }
    }

    let maxSessions: Int
    let maxCharacters: Int
    private var entries: [TextMateCodeBlockKey: Entry] = [:]
    private var useCounter: UInt64 = 0

    private(set) var totalCharacters = 0

    var count: Int { entries.count }

    init(maxSessions: Int = defaultMaxSessions, maxCharacters: Int = defaultMaxCharacters) {
        self.maxSessions = max(1, maxSessions)
        self.maxCharacters = maxCharacters
    }

    func contains(_ key: TextMateCodeBlockKey) -> Bool {
        entries[key] != nil
    }

    /// Highlights `source` for `key`: opens a session the first time (or when the language
    /// changes), otherwise applies the change from the previous source as one edit.
    func update(
        key: TextMateCodeBlockKey,
        language: String,
        source: String,
        cancel: HighlightCancelFlag,
        deadline: HighlightDeadline
    ) -> HighlightCoreFenceUpdate {
        useCounter += 1
        let canonical = HighlightCore.shared.canonicalLanguage(language: language) ?? language
        if cancel.isCancelled {
            return HighlightCoreFenceUpdate(
                status: .cancelled,
                spans: [],
                canonicalLanguage: canonical,
                lineCount: 0,
                relexedFromLine: 0
            )
        }
        let entry: Entry
        let relexedFrom: Int
        if let existing = entries[key], existing.session.language == canonical {
            entry = existing
            entry.lastUse = useCounter
            totalCharacters -= entry.length
            relexedFrom = apply(source, to: entry, cancel: cancel, deadline: deadline)
        } else {
            if let replaced = entries.removeValue(forKey: key) { totalCharacters -= replaced.length }
            entry = Entry(
                session: HighlightSession(language: language, limits: HighlightLimits.companion.standard),
                lastUse: useCounter
            )
            entries[key] = entry
            relexedFrom = open(source, in: entry, cancel: cancel, deadline: deadline)
        }
        totalCharacters += entry.length
        let status: HighlightCoreFenceStatus
        if relexedFrom == Self.cancelledMarker {
            status = .cancelled
        } else if relexedFrom == Self.failedMarker {
            status = .failed
        } else {
            status = entry.coloredEnd >= entry.length ? .complete : .partial
        }
        let update = HighlightCoreFenceUpdate(
            status: status,
            spans: HighlightCoreSpans.codeSpans(entry.packed),
            canonicalLanguage: canonical,
            lineCount: Int(entry.session.lineCount),
            relexedFromLine: max(relexedFrom, 0)
        )
        evict(keeping: key)
        return update
    }

    /// Moderate drops the least recently used sessions until both the session count and the
    /// total text are at most their share (`moderatePressureKeepDivisor`) of what was held;
    /// critical drops every session.
    func trim(_ level: TextMateMemoryPressure) {
        switch level {
        case .normal:
            return
        case .moderate:
            let divisor = max(1, Self.moderatePressureKeepDivisor)
            let sessionLimit = entries.count / divisor
            let characterLimit = totalCharacters / divisor
            let oldestFirst = entries.sorted { $0.value.lastUse < $1.value.lastUse }
            for (key, entry) in oldestFirst {
                guard entries.count > sessionLimit || totalCharacters > characterLimit else { break }
                entries[key] = nil
                totalCharacters -= entry.length
            }
        case .critical:
            removeAll()
        }
    }

    func removeAll() {
        entries.removeAll()
        totalCharacters = 0
    }

    private static let cancelledMarker = -1
    private static let failedMarker = -2

    /// Returns the first line lexed, or a marker.
    private func open(
        _ source: String,
        in entry: Entry,
        cancel: HighlightCancelFlag,
        deadline: HighlightDeadline
    ) -> Int {
        entry.source = source
        entry.length = source.utf16.count
        let result = entry.session.open(text: source, endLine: Int32.max, cancel: cancel, deadline: deadline)
        entry.packed = []
        entry.coloredEnd = 0
        return absorb(result, into: entry, keepingBefore: 0, from: 0)
    }

    private func apply(
        _ source: String,
        to entry: Entry,
        cancel: HighlightCancelFlag,
        deadline: HighlightDeadline
    ) -> Int {
        let session = entry.session
        let oldLength = entry.length
        let newLength = source.utf16.count
        let prefix = Self.commonPrefixUTF16(entry.source, source)
        var relexedFrom = Int(session.lineCount)
        if prefix < oldLength || prefix < newLength {
            let tail = Self.suffix(of: source, fromUTF16Offset: prefix)
            let result = session.replace(
                start: Int32(prefix),
                end: Int32(oldLength),
                text: tail,
                cancel: cancel,
                deadline: deadline
            )
            entry.source = source
            entry.length = newLength
            let editStart = Int(session.lineStart(line: result.startLine))
            relexedFrom = Int(result.startLine)
            switch result.status {
            case .cancelled, .failed:
                entry.coloredEnd = min(entry.coloredEnd, editStart)
                entry.packed = HighlightCoreSpans.prefix(entry.packed, before: entry.coloredEnd)
                return result.status == .cancelled ? Self.cancelledMarker : Self.failedMarker
            default:
                if entry.coloredEnd < editStart {
                    // The colored prefix stopped above the edit (an earlier partial result):
                    // continue from there below.
                    entry.packed = HighlightCoreSpans.prefix(entry.packed, before: entry.coloredEnd)
                    relexedFrom = Int(session.lineAt(offset: Int32(entry.coloredEnd)))
                } else {
                    let delta = newLength - oldLength
                    let rangeEnd = result.endLine >= session.lineCount
                        ? newLength
                        : Int(session.lineStart(line: result.endLine))
                    let settled = Self.settledEnd(of: result, in: session)
                    var packed = HighlightCoreSpans.prefix(entry.packed, before: editStart)
                    packed += HighlightCoreSpans.prefix(HighlightCoreSpans.packedInts(result.spans), before: settled)
                    if result.status == .complete, rangeEnd < newLength, entry.coloredEnd >= rangeEnd - delta {
                        // Converged: the old spans after the range still hold, shifted by the edit.
                        packed += HighlightCoreSpans.shifted(
                            HighlightCoreSpans.prefix(entry.packed, before: entry.coloredEnd),
                            from: rangeEnd - delta,
                            by: delta
                        )
                        entry.coloredEnd += delta
                    } else {
                        entry.coloredEnd = settled
                    }
                    entry.packed = packed
                }
            }
        } else if entry.coloredEnd >= newLength {
            return relexedFrom
        }
        // Color whatever an earlier partial result or the edit left plain.
        if entry.coloredEnd < newLength, !cancel.isCancelled, !deadline.hasPassed {
            let line = session.lineAt(offset: Int32(entry.coloredEnd))
            let result = session.highlight(startLine: line, endLine: Int32.max, cancel: cancel, deadline: deadline)
            let absorbed = absorb(result, into: entry, keepingBefore: entry.coloredEnd, from: Int(line))
            return absorbed < 0 ? absorbed : min(relexedFrom, absorbed)
        }
        return relexedFrom
    }

    /// Appends a highlight or open result that starts at `keepingBefore`.
    private func absorb(
        _ result: HighlightCallResult,
        into entry: Entry,
        keepingBefore start: Int,
        from line: Int
    ) -> Int {
        switch result.status {
        case .cancelled:
            return Self.cancelledMarker
        case .failed:
            return Self.failedMarker
        default:
            let settled = Self.settledEnd(of: result, in: entry.session)
            entry.packed = HighlightCoreSpans.prefix(entry.packed, before: start)
                + HighlightCoreSpans.prefix(HighlightCoreSpans.packedInts(result.spans), before: settled)
            entry.coloredEnd = settled
            return line
        }
    }

    /// Where a complete or partial result's fully colored lines end: a line start or the length.
    private static func settledEnd(of result: HighlightCallResult, in session: HighlightSession) -> Int {
        let line = result.status == .complete ? result.endLine : result.highlightedEndLine
        return line >= session.lineCount ? Int(session.length) : Int(session.lineStart(line: line))
    }

    private func evict(keeping key: TextMateCodeBlockKey) {
        while entries.count > maxSessions || (totalCharacters > maxCharacters && entries.count > 1) {
            guard let oldest = entries.filter({ $0.key != key }).min(by: { $0.value.lastUse < $1.value.lastUse })
            else { return }
            entries[oldest.key] = nil
            totalCharacters -= oldest.value.length
        }
    }

    /// Shared UTF-16 prefix, never ending between the two halves of a surrogate pair.
    static func commonPrefixUTF16(_ lhs: String, _ rhs: String) -> Int {
        var count = 0
        var last: UInt16 = 0
        for (left, right) in zip(lhs.utf16, rhs.utf16) {
            guard left == right else { break }
            last = left
            count += 1
        }
        if count > 0, UTF16.isLeadSurrogate(last) { count -= 1 }
        return count
    }

    /// The text from a UTF-16 offset on a scalar boundary. Unicode scalars, not Characters, so a
    /// `\r\n` split by the offset is not rounded back to the `\r`.
    static func suffix(of text: String, fromUTF16Offset offset: Int) -> String {
        let index = text.utf16.index(text.utf16.startIndex, offsetBy: offset)
        return String(Substring(text.unicodeScalars[index...]))
    }
}

/// One-shot highlight-core coloring for text that is highlighted once, such as tool output
/// (`OpenCodeHighlightCoreCodeHighlighter` in the shared markdown core: per-call deadline, past
/// it the rest stays plain). An unsupported language comes back with no spans.
nonisolated enum HighlightCoreOneShotHighlighter {
    static func spans(language: String, source: String) -> [NativeCodeSpan] {
        OpenCodeHighlightCoreCodeHighlighter.shared
            .highlight(languageOrAlias: language, code: source)
            .spans
            .compactMap { span in
                guard let role = NativeCodeTokenRole(rawValue: span.role.name) else { return nil }
                return NativeCodeSpan(start: Int(span.start), end: Int(span.end), role: role)
            }
    }
}

/// highlight-core's packed spans (`start, length, role code` per span) on the Swift side.
nonisolated enum HighlightCoreSpans {
    /// `NativeCodeTokenRole` by `HighlightRole.code`; the names match one to one.
    static let roles: [NativeCodeTokenRole] = [
        .plain, .comment, .keyword, .string, .number, .type, .function, .property, .variable,
        .constant, .operatorToken, .punctuation, .tag, .attribute, .inserted, .deleted, .warning,
        .critical,
    ]

    static func role(code: Int32) -> NativeCodeTokenRole {
        roles.indices.contains(Int(code)) ? roles[Int(code)] : .plain
    }

    /// All spans in one `writeInts(to:)` copy.
    static func packedInts(_ spans: HighlightSpans) -> [Int32] {
        let count = Int(spans.intCount)
        guard count > 0 else { return [] }
        return [Int32](unsafeUninitializedCapacity: count) { buffer, initialized in
            spans.writeInts(to: buffer.baseAddress!)
            initialized = count
        }
    }

    static func codeSpans(_ packed: [Int32], offsetBy shift: Int = 0) -> [NativeCodeSpan] {
        var spans: [NativeCodeSpan] = []
        spans.reserveCapacity(packed.count / 3)
        var index = 0
        while index + 2 < packed.count {
            let start = Int(packed[index]) + shift
            spans.append(
                NativeCodeSpan(start: start, end: start + Int(packed[index + 1]), role: role(code: packed[index + 2]))
            )
            index += 3
        }
        return spans
    }

    /// Spans that start before `offset`. Spans never cross a line end, so at a line start this
    /// keeps whole spans.
    static func prefix(_ packed: [Int32], before offset: Int) -> [Int32] {
        Array(packed[0..<(3 * firstSpan(in: packed, atOrAfter: offset))])
    }

    /// Spans that start at or after `offset`, moved by `delta`.
    static func shifted(_ packed: [Int32], from offset: Int, by delta: Int) -> [Int32] {
        var result = Array(packed[(3 * firstSpan(in: packed, atOrAfter: offset))...])
        var index = 0
        while index < result.count {
            result[index] += Int32(delta)
            index += 3
        }
        return result
    }

    private static func firstSpan(in packed: [Int32], atOrAfter offset: Int) -> Int {
        var low = 0
        var high = packed.count / 3
        while low < high {
            let mid = (low + high) / 2
            if Int(packed[3 * mid]) < offset { low = mid + 1 } else { high = mid }
        }
        return low
    }
}
