import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct ProjectFileLanguageTests {
    private struct Case: Decodable {
        let path: String
        let language: String?
        let displayName: String
    }

    /// `shared/file-languages/file-language-cases.json`, shared with the Android suite. Simulator
    /// tests read it from the host checkout, found by walking up from this source file.
    private static func loadCases(from filePath: String = #filePath) throws -> [Case] {
        var url = URL(fileURLWithPath: filePath)
        while url.pathComponents.count > 1 {
            let candidate = url.appendingPathComponent("shared/file-languages/file-language-cases.json")
            if FileManager.default.fileExists(atPath: candidate.path) {
                return try JSONDecoder().decode([Case].self, from: Data(contentsOf: candidate))
            }
            url.deleteLastPathComponent()
        }
        Issue.record("shared/file-languages/file-language-cases.json not found above \(filePath)")
        return []
    }

    @Test func matchesSharedCases() throws {
        let cases = try Self.loadCases()
        #expect(!cases.isEmpty)
        for testCase in cases {
            let language = ProjectFileLanguage.forFileName(testCase.path)
            #expect(language?.id == testCase.language, "\(testCase.path)")
            #expect(
                (language?.displayName ?? ProjectFileLanguage.plainTextName) == testCase.displayName,
                "\(testCase.path)"
            )
        }
    }

    @Test func everyTableEntryIsCoveredBySharedCases() throws {
        let names = try Self.loadCases().map { ProjectPaths.name(of: $0.path).lowercased() }
        let extensions = Set(names.map { name in
            name.lastIndex(of: ".").map { String(name[name.index(after: $0)...]) } ?? ""
        })
        for ext in ProjectFileLanguage.extensions.keys {
            #expect(extensions.contains(ext), "missing shared case for .\(ext)")
        }
        for name in ProjectFileLanguage.fileNames.keys {
            #expect(names.contains(name), "missing shared case for \(name)")
        }
    }

    @Test func unknownIsPlainText() {
        #expect(ProjectFileLanguage.forFileName("LICENSE") == nil)
        #expect(ProjectFileLanguage.forFileName("data.xyz") == nil)
        #expect(ProjectFileLanguage.forFileName(".gitignore") == nil)
        #expect(FileTextDocument(text: "a\nb\n", fileName: "LICENSE", isTruncated: false).metaText == "Plain text · 2 lines")
    }
}
