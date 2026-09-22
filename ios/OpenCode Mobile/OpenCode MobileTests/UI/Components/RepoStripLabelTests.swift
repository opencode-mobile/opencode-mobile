import Foundation
import Testing
@testable import OpenCode_Mobile

struct RepoStripLabelTests {
    private let files: [OpenCodeRepoStripFile] = [
        OpenCodeRepoStripFile(path: "a/b/HealthApi.kt", status: .modified, additions: 42, deletions: 7),
        OpenCodeRepoStripFile(path: "HealthProbeTest.kt", status: .added, additions: 61, deletions: 0),
        OpenCodeRepoStripFile(path: "docs/health-probe.md", status: .deleted, additions: 0, deletions: 19),
    ]

    @Test func summarySumsFilesAndLines() {
        let summary = formatRepoStripSummary(files: files)
        #expect(summary.fileLabel == "3 files")
        #expect(summary.additionLabel == "+103")
        #expect(summary.deletionLabel == "−26")
        #expect(!summary.isClean)
    }

    @Test func summarySingleFileIsSingular() {
        #expect(formatRepoStripSummary(files: Array(files.prefix(1))).fileLabel == "1 file")
    }

    @Test func summaryCleanTreeHasNoLineLabels() {
        let summary = formatRepoStripSummary(files: [])
        #expect(summary.isClean)
        #expect(summary.fileLabel == "clean")
        #expect(summary.additionLabel == nil)
        #expect(summary.deletionLabel == nil)
    }

    @Test func accessibilityLabelDirtyIncludesDisclosure() {
        #expect(
            formatRepoStripAccessibilityLabel(branch: "fix/probe", files: files, expanded: true)
                == "Branch fix/probe, 3 files changed, 103 additions, 26 deletions, expanded"
        )
        #expect(
            formatRepoStripAccessibilityLabel(branch: "fix/probe", files: files, expanded: false)
                == "Branch fix/probe, 3 files changed, 103 additions, 26 deletions, collapsed"
        )
    }

    @Test func accessibilityLabelCleanHasNoDisclosure() {
        #expect(formatRepoStripAccessibilityLabel(branch: "main", files: [], expanded: true) == "Branch main, clean working tree")
    }

    @Test func statusLetters() {
        #expect(repoStripStatusLetter(.added) == "A")
        #expect(repoStripStatusLetter(.modified) == "M")
        #expect(repoStripStatusLetter(.deleted) == "D")
    }
}
