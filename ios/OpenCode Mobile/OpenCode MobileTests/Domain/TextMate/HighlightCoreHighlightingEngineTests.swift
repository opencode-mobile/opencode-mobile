#if HIGHLIGHT_LAB
import Foundation
import OpenCodeMarkdownCore
import Testing
@testable import OpenCode_Mobile

@MainActor
struct HighlightCoreHighlightingEngineTests {
    private static let samples: [(language: String, source: String)] = [
        ("typescript", "/** Docs */\nexport const greet = (name: string): string => `Hello ${name}!`;\nclass Box<T> { value?: T }\n"),
        ("kotlin", "/* block\n comment */\nfun main() {\n    val text = \"\"\"raw\n${1 + 2}\"\"\"\n    println(text)\n}\n"),
        ("swift", "struct Item: Codable {\n    let id: Int // id\n    var name = \"multi \\(id)\"\n}\n"),
        ("python", "def f(x):\n    \"\"\"Doc\n    string\"\"\"\n    return f\"{x!r:>10}\" + 'y'\n"),
        ("json", "{\n  \"key\": [1, 2.5e3, true, null],\n  \"nested\": {\"a\": \"b\"}\n}\n"),
        ("diff", "--- a/x\n+++ b/x\n@@ -1,2 +1,2 @@ func\n-old\n+new\n context\n"),
    ]

    @Test func streamedRevisionsEqualOneShotForEveryMinimumLanguage() {
        for sample in Self.samples {
            let sessions = HighlightCoreFenceSessions()
            let key = TextMateCodeBlockKey(value: "fence:\(sample.language)")
            var update: HighlightCoreFenceUpdate?
            var previous = ""
            for revision in Self.revisions(of: sample.source, step: 5) {
                let previousLines = previous.split(separator: "\n", omittingEmptySubsequences: false).count
                update = sessions.update(key: key, language: sample.language, source: revision, cancel: HighlightCancelFlag(), deadline: .companion.never)
                // An append re-lexes from the line the old text ended in, never from the top.
                #expect(update?.relexedFromLine == (previous.isEmpty ? 0 : previousLines - 1), "\(sample.language)")
                #expect(update?.spans == Self.oneShot(sample.language, revision), "\(sample.language) at \(revision.utf16.count)")
                previous = revision
            }
            #expect(update?.status == .complete)
            #expect(!(update?.spans.isEmpty ?? true), "\(sample.language)")
        }
    }

    @Test func midTextEditRelexesFromTheChangedLineAndMatchesOneShot() {
        let sessions = HighlightCoreFenceSessions()
        let key = TextMateCodeBlockKey(value: "edit")
        let lines = (0..<40).map { "let value\($0) = \($0) // note" }
        let original = lines.joined(separator: "\n")
        _ = sessions.update(key: key, language: "swift", source: original, cancel: HighlightCancelFlag(), deadline: .companion.never)
        var edited = lines
        edited[20] = "let changed = \"text\""
        let source = edited.joined(separator: "\n")
        let update = sessions.update(key: key, language: "swift", source: source, cancel: HighlightCancelFlag(), deadline: .companion.never)

        #expect(update.relexedFromLine == 20)
        #expect(update.spans == Self.oneShot("swift", source))
        // Opening a block comment changes every later line.
        edited[25] = "/* open"
        let commented = edited.joined(separator: "\n")
        let second = sessions.update(key: key, language: "swift", source: commented, cancel: HighlightCancelFlag(), deadline: .companion.never)
        #expect(second.relexedFromLine == 25)
        #expect(second.spans == Self.oneShot("swift", commented))
        #expect(second.spans.last?.role == .comment)
    }

    @Test func utf16OffsetsStayExactWithEmojiCrLfAndSplitSurrogates() {
        let sessions = HighlightCoreFenceSessions()
        let key = TextMateCodeBlockKey(value: "utf16")
        let revisions = [
            "let a = \"😀\"",
            "let a = \"😁\"\r",
            "let a = \"😁\"\r\nlet 👨‍👩‍👧 = 1\r\n",
            "let a = \"😁\"\r\nlet 👨‍👩‍👧 = 1\r\nlet b = \"é\" // ✓\r\n",
        ]
        for source in revisions {
            let update = sessions.update(key: key, language: "swift", source: source, cancel: HighlightCancelFlag(), deadline: .companion.never)
            #expect(update.spans == Self.oneShot("swift", source), "\(source.debugDescription)")
        }
        let final = revisions.last!
        let spans = Self.oneShot("swift", final)
        let ns = final as NSString
        let strings = spans.filter { $0.role == .string }.map { ns.substring(with: NSRange(location: $0.start, length: $0.end - $0.start)) }
        #expect(strings == ["\"😁\"", "\"é\""])
        let comments = spans.filter { $0.role == .comment }.map { ns.substring(with: NSRange(location: $0.start, length: $0.end - $0.start)) }
        #expect(comments == ["// ✓"])
    }

    @Test func commonPrefixNeverSplitsASurrogatePairOrCrLf() {
        // 😀 and 😁 share their lead surrogate.
        #expect(HighlightCoreFenceSessions.commonPrefixUTF16("a😀", "a😁") == 1)
        #expect(HighlightCoreFenceSessions.suffix(of: "a😁", fromUTF16Offset: 1) == "😁")
        #expect(HighlightCoreFenceSessions.commonPrefixUTF16("x\r", "x\r\ny") == 2)
        #expect(HighlightCoreFenceSessions.suffix(of: "x\r\ny", fromUTF16Offset: 2) == "\ny")
    }

    @Test func cancelledUpdateLeavesTheSessionUsable() {
        let sessions = HighlightCoreFenceSessions()
        let key = TextMateCodeBlockKey(value: "cancel")
        let source = Self.samples[0].source
        let flag = HighlightCancelFlag()
        flag.cancel()
        let cancelled = sessions.update(key: key, language: "typescript", source: source, cancel: flag, deadline: .companion.never)
        #expect(cancelled.status == .cancelled)
        #expect(cancelled.spans.isEmpty)
        let update = sessions.update(key: key, language: "typescript", source: source, cancel: HighlightCancelFlag(), deadline: .companion.never)
        #expect(update.status == .complete)
        #expect(update.spans == Self.oneShot("typescript", source))
    }

    @Test func passedDeadlineGivesPartialThenTheNextRevisionCompletes() {
        let sessions = HighlightCoreFenceSessions()
        let key = TextMateCodeBlockKey(value: "deadline")
        let source = Array(repeating: "const x = { \"k\": [1, 2, 3] }; // more", count: 20_000).joined(separator: "\n")
        let partial = sessions.update(key: key, language: "typescript", source: source, cancel: HighlightCancelFlag(), deadline: .companion.after(milliseconds: 0))
        #expect(partial.status == .partial)
        #expect(!partial.spans.isEmpty)
        let reference = Self.oneShot("typescript", source)
        #expect(Array(reference.prefix(partial.spans.count)) == partial.spans)

        let grown = source + "\nlet tail = 1"
        let complete = sessions.update(key: key, language: "typescript", source: grown, cancel: HighlightCancelFlag(), deadline: .companion.never)
        #expect(complete.status == .complete)
        #expect(complete.spans == Self.oneShot("typescript", grown))
    }

    @Test func leastRecentlyUsedSessionsAreEvictedAndTrimmed() {
        let sessions = HighlightCoreFenceSessions(maxSessions: 2, maxCharacters: 1_000)
        func touch(_ name: String, _ source: String = "let x = 1") {
            _ = sessions.update(key: TextMateCodeBlockKey(value: name), language: "swift", source: source, cancel: HighlightCancelFlag(), deadline: .companion.never)
        }
        touch("a")
        touch("b")
        touch("a")
        touch("c")
        #expect(sessions.count == 2)
        #expect(sessions.contains(TextMateCodeBlockKey(value: "a")))
        #expect(!sessions.contains(TextMateCodeBlockKey(value: "b")))

        // A fence over the character budget keeps only itself.
        touch("big", String(repeating: "let y = 2\n", count: 150))
        #expect(sessions.count == 1)
        #expect(sessions.contains(TextMateCodeBlockKey(value: "big")))
        #expect(sessions.totalCharacters == 1_500)

        let trimmed = HighlightCoreFenceSessions(maxSessions: 10)
        for index in 0..<6 {
            _ = trimmed.update(key: TextMateCodeBlockKey(value: "\(index)"), language: "json", source: "[\(index)]", cancel: HighlightCancelFlag(), deadline: .companion.never)
        }
        trimmed.trim(.moderate)
        #expect(trimmed.count == 3)
        #expect(trimmed.contains(TextMateCodeBlockKey(value: "5")))
        #expect(!trimmed.contains(TextMateCodeBlockKey(value: "0")))
        trimmed.trim(.critical)
        #expect(trimmed.count == 0)
        #expect(trimmed.totalCharacters == 0)
    }

    @Test func roleCodesMapToTheAppRolesByName() {
        for (code, role) in HighlightCoreSpans.roles.enumerated() {
            #expect(HighlightRole.companion.ofCode(code: Int32(code)).name == role.rawValue)
        }
        #expect(HighlightCoreSpans.role(code: 999) == .plain)
    }

    @Test func engineHighlightsSupportedFencesAndFallsBackForOthers() async throws {
        let fallback = RecordingTextMate()
        let engine = HighlightCoreHighlightingEngine(fallback: fallback)
        let source = "val answer = 42"
        let result = try await engine.highlight(Self.request(language: "kt", source: source))
        #expect(result.outcome == "Highlighted")
        #expect(result.canonicalLanguage == "kotlin")
        #expect(result.source == source)
        #expect(result.policyEpoch == 7)
        #expect(result.observation.backend == "highlight-core")
        #expect(result.spans == Self.oneShot("kotlin", source))
        #expect(fallback.languages.isEmpty)

        _ = try await engine.highlight(Self.request(language: "brainfuck", source: "++[>+<-]"))
        _ = try await engine.highlight(Self.request(language: nil, source: "plain"))
        #expect(fallback.languages == ["brainfuck", nil])

        // The result attaches to the fence like a TextMate result.
        let document = NativeMarkdownDocument(sourceHash: 1, blocks: [.codeBlock(language: "kotlin", code: source, spans: [])])
        let fence = document.textMateFences(ownerKey: "m", revision: TextMateHighlightRevision(value: 1), policyEpoch: 7)[0]
        let attached = try await engine.highlight(fence.request(intent: .interactiveFinal, messageCharacters: 20, fenceCount: 1))
        guard case .attached(let upgraded) = document.attachingTextMate(fence: fence, result: attached),
              case .codeBlock(_, _, let spans) = upgraded.blocks[0]
        else {
            Issue.record("highlight-core result should attach")
            return
        }
        #expect(spans == Self.oneShot("kotlin", source))

        await engine.trim(.critical)
        #expect(await engine.sessionStats().count == 0)
        #expect(fallback.trims == [.critical])
    }

    @Test func engineHonorsTaskCancellation() async throws {
        let engine = HighlightCoreHighlightingEngine(fallback: RecordingTextMate())
        let request = Self.request(language: "swift", source: "let a = 1")
        let task = Task {
            withUnsafeCurrentTask { $0?.cancel() }
            return try await engine.highlight(request)
        }
        let result = try await task.value
        #expect(result.outcome == "Cancelled")
        #expect(result.spans.isEmpty)
        let again = try await engine.highlight(request)
        #expect(again.outcome == "Highlighted")
    }

    @Test func developerSelectionRoutesEachRequestBySetting() async throws {
        let textMate = RecordingTextMate()
        let highlightCore = RecordingTextMate()
        let usesHighlightCore = LockedFlag()
        let engine = DeveloperSelectedHighlightingEngine(
            textMate: textMate,
            highlightCore: highlightCore,
            usesHighlightCore: { usesHighlightCore.value }
        )
        _ = try await engine.highlight(Self.request(language: "swift", source: "a"))
        usesHighlightCore.value = true
        _ = try await engine.highlight(Self.request(language: "swift", source: "b"))
        usesHighlightCore.value = false
        _ = try await engine.highlight(Self.request(language: "swift", source: "c"))
        #expect(textMate.sources == ["a", "c"])
        #expect(highlightCore.sources == ["b"])
        await engine.trim(.moderate)
        #expect(textMate.trims == [.moderate])
        #expect(highlightCore.trims == [.moderate])
    }

    @Test func enginePreferencePersistsAndInvalidatesHighlightingOnChange() {
        let defaults = UserDefaults(suiteName: "HighlightEnginePreferencesTests")!
        defaults.removePersistentDomain(forName: "HighlightEnginePreferencesTests")
        var invalidations = 0
        let center = NotificationCenter()
        var notifications = 0
        let observer = center.addObserver(forName: HighlightEnginePreferences.didChange, object: nil, queue: nil) { _ in
            notifications += 1
        }
        defer { center.removeObserver(observer) }
        let preferences = HighlightEnginePreferences(
            defaults: defaults,
            invalidateHighlighting: { invalidations += 1 },
            notificationCenter: center
        )
        #expect(preferences.engine == .textMate)
        preferences.setEngine(.highlightCore)
        preferences.setEngine(.highlightCore)
        #expect(HighlightEnginePreferences.current(defaults: defaults) == .highlightCore)
        #expect(HighlightEnginePreferences(defaults: defaults).engine == .highlightCore)
        #expect(invalidations == 1)
        #expect(notifications == 1)
        preferences.clear()
        #expect(HighlightEnginePreferences.current(defaults: defaults) == .textMate)
    }

    // MARK: Helpers

    /// Growing prefixes of `source`, `step` scalars apart, ending with the whole text.
    private static func revisions(of source: String, step: Int) -> [String] {
        let scalars = Array(source.unicodeScalars)
        var result: [String] = []
        var end = step
        while end < scalars.count {
            var view = String.UnicodeScalarView()
            view.append(contentsOf: scalars[0..<end])
            result.append(String(view))
            end += step
        }
        result.append(source)
        return result
    }

    static func oneShot(_ language: String, _ source: String) -> [NativeCodeSpan] {
        let result = HighlightCore.shared.highlight(
            language: language,
            text: source,
            cancel: HighlightCancellationNever.shared,
            deadline: .companion.never,
            limits: .companion.standard
        )
        return HighlightCoreSpans.codeSpans(HighlightCoreSpans.packedInts(result.spans))
    }

    private static func request(language: String?, source: String) -> TextMateHighlightRequest {
        TextMateHighlightRequest(
            key: TextMateCodeBlockKey(value: "request:\(language ?? "none")"),
            revision: TextMateHighlightRevision(value: 1),
            language: language,
            source: source,
            intent: .interactiveFinal,
            policyEpoch: 7,
            messageCharacters: source.utf16.count,
            fenceIndex: 0,
            fenceCount: 1
        )
    }
}

private final class LockedFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var stored = false

    var value: Bool {
        get { lock.withLock { stored } }
        set { lock.withLock { stored = newValue } }
    }
}

private final class RecordingTextMate: TextMateHighlighting, @unchecked Sendable {
    private let lock = NSLock()
    private var storedLanguages: [String?] = []
    private var storedSources: [String] = []
    private var storedTrims: [TextMateMemoryPressure] = []

    var languages: [String?] { lock.withLock { storedLanguages } }
    var sources: [String] { lock.withLock { storedSources } }
    var trims: [TextMateMemoryPressure] { lock.withLock { storedTrims } }

    func highlight(_ request: TextMateHighlightRequest) async throws -> TextMateHighlightResult {
        lock.withLock {
            storedLanguages.append(request.language)
            storedSources.append(request.source)
        }
        return TextMateHighlightResult(
            key: request.key,
            revision: request.revision,
            source: request.source,
            canonicalLanguage: request.language,
            policyEpoch: request.policyEpoch,
            spans: [],
            outcome: "NoSpans",
            diagnostics: [],
            observation: NativeTextMateParseObservation(
                outcome: "NoSpans",
                messageCharacters: request.messageCharacters,
                fenceCharacters: request.source.utf16.count,
                fenceLines: 1,
                fenceIndex: 0,
                fenceCount: 1,
                spanCount: 0,
                totalNanoseconds: 1,
                stages: [],
                diagnosticKinds: []
            )
        )
    }

    func trim(_ level: TextMateMemoryPressure) async {
        lock.withLock { storedTrims.append(level) }
    }
}
#endif
