import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite(.serialized)
struct TextMateLogbookTests {
    @Test func observedBridgeReturnsFenceMetadataWithoutSourceContent() {
        let markdown = "Unicode 😀\n\n```missing-language\nsecret source\n```"

        let result = OpenCodeRenderedMarkdownParser.parseObserved(
            markdown,
            textMateEnabled: true
        )

        #expect(result.textMateObservations.count == 1)
        let observation = result.textMateObservations[0]
        #expect(observation.outcome == "Unsupported")
        #expect(observation.messageCharacters == markdown.utf16.count)
        #expect(observation.fenceCharacters == "secret source".utf16.count)
        #expect(!String(describing: observation).contains("secret source"))
    }

    @Test func storePersistsRecoversCorruptTailAndClears() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let file = directory.appendingPathComponent("textmate-logbook-v1.jsonl")
        let store = TextMateLogbook(fileURL: file)

        store.submit([observation(language: "swift")])
        let first = await store.snapshot()
        #expect(first.entries.count == 1)
        #expect(first.entries[0].observation.canonicalLanguage == "swift")

        let handle = try FileHandle(forWritingTo: file)
        try handle.seekToEnd()
        try handle.write(contentsOf: Data("{corrupt-tail".utf8))
        try handle.close()

        let reopened = TextMateLogbook(fileURL: file)
        #expect(await reopened.snapshot().entries.count == 1)

        await reopened.clear()
        #expect(await reopened.snapshot().entries.isEmpty)
        #expect(!FileManager.default.fileExists(atPath: file.path))
    }

    @Test func storeCompactsToNewestFiveHundredEntries() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let file = directory.appendingPathComponent("textmate-logbook-v1.jsonl")
        let store = TextMateLogbook(fileURL: file)
        let observations = (0..<560).map { observation(language: "language-\($0)") }

        store.submit(observations)
        let snapshot = await store.snapshot()

        #expect(snapshot.entries.count == TextMateLogbook.maxPersistedEntries)
        #expect(snapshot.entries.first?.observation.canonicalLanguage == "language-559")
        let persisted = try String(contentsOf: file, encoding: .utf8)
        #expect(!persisted.contains("secret source"))
        #expect(!persisted.contains("session-id"))
        #expect(!persisted.contains("diagnostic message"))
    }

    private func observation(language: String) -> NativeTextMateParseObservation {
        NativeTextMateParseObservation(
            schemaVersion: 1,
            requestedLanguage: language,
            canonicalLanguage: language,
            outcome: "Highlighted",
            messageCharacters: 120,
            fenceCharacters: 80,
            fenceLines: 4,
            fenceIndex: 1,
            fenceCount: 1,
            spanCount: 8,
            backend: "libonig",
            totalNanoseconds: 1_000_000,
            stages: [],
            diagnosticKinds: ["HighlighterFallback"]
        )
    }
}
