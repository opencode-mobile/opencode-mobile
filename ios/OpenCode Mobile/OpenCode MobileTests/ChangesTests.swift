import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor struct FileDiffAdapterTests {
    @Test func decodesServerShapeAndSynthesizesDomainPatchAndStatus() throws {
        let data = Data(#"{"file":"Sources/App.swift","before":"old","after":"new","additions":1,"deletions":1}"#.utf8)
        let dto = try JSONDecoder().decode(FileDiffDTO.self, from: data)
        let diff = SessionDomainAdapter().fileDiff(from: dto)

        #expect(diff.path == "Sources/App.swift")
        #expect(diff.status == .modified)
        #expect(diff.patch == "@@ -1,1 +1,1 @@\n-old\n+new")
        #expect(diff.additions == 1)
        #expect(diff.deletions == 1)
    }

    @Test func decodesPathPatchAndExplicitStatus() throws {
        let data = Data(#"{"path":"new.txt","patch":"@@ -0,0 +1,1 @@\n+hello","additions":1,"deletions":0,"status":"added"}"#.utf8)
        let diff = SessionDomainAdapter().fileDiff(from: try JSONDecoder().decode(FileDiffDTO.self, from: data))
        #expect(diff.path == "new.txt")
        #expect(diff.status == .added)
        #expect(diff.patch?.contains("+hello") == true)
    }

    @Test func synthesizesAddedAndDeletedFilesWithoutPhantomBlankLines() throws {
        let added = try JSONDecoder().decode(FileDiffDTO.self, from: Data(#"{"file":"new.txt","before":"","after":"hello","additions":1}"#.utf8))
        let deleted = try JSONDecoder().decode(FileDiffDTO.self, from: Data(#"{"file":"old.txt","before":"goodbye","after":"","deletions":1}"#.utf8))
        #expect(SessionDomainAdapter().fileDiff(from: added).patch == "@@ -0,0 +1,1 @@\n+hello")
        #expect(SessionDomainAdapter().fileDiff(from: deleted).patch == "@@ -1,1 +0,0 @@\n-goodbye")
    }
}

struct UnifiedDiffParserTests {
    @Test func parsesHunksAndAdvancesOldAndNewLineNumbers() {
        let document = UnifiedDiffParser.parse("@@ -2,3 +2,3 @@ first\n same\n-old\n\\ No newline at end of file\n+new\n next\n@@ -20 +20,2 @@ second\n+naïve 🚀\n tail")
        let rows = document.rows
        #expect(rows.prefix(6).map(\.kind) == [.hunk, .context, .deletion, .metadata, .addition, .context])
        #expect(rows[1].oldLine == 2 && rows[1].newLine == 2)
        #expect(rows[2].oldLine == 3 && rows[2].newLine == nil)
        #expect(rows[3].oldLine == nil && rows[3].newLine == nil)
        #expect(rows[4].oldLine == nil && rows[4].newLine == 3)
        #expect(rows[5].oldLine == 4 && rows[5].newLine == 4)
        #expect(document.hunks.count == 2)
        #expect(document.hunks[0].oldStart == 2 && document.hunks[0].oldCount == 3)
        #expect(document.hunks[0].newStart == 2 && document.hunks[0].newCount == 3)
        #expect(document.hunks[1].oldStart == 20 && document.hunks[1].oldCount == 1)
        #expect(document.hunks[1].newStart == 20 && document.hunks[1].newCount == 2)
        #expect(document.hunks[1].rows.contains { $0.text == "naïve 🚀" })
    }

    @Test func collapsedProjectionKeepsThreeContextLinesAroundChanges() {
        let contextBefore = (1...8).map { " before \($0)" }.joined(separator: "\n")
        let contextAfter = (1...8).map { " after \($0)" }.joined(separator: "\n")
        let document = UnifiedDiffParser.parse("@@ -1,17 +1,17 @@\n\(contextBefore)\n-old\n+new\n\(contextAfter)")

        #expect(document.visibleRows(contextMode: .expanded).count == 19)
        let collapsed = document.visibleRows(contextMode: .collapsed)
        #expect(collapsed.count == 9)
        #expect(!collapsed.contains { $0.text == "before 4" })
        #expect(collapsed.contains { $0.text == "before 6" })
        #expect(collapsed.contains { $0.text == "after 3" })
        #expect(!collapsed.contains { $0.text == "after 4" })
    }

    @Test func metadataEmptyAndBinaryPatchesDoNotCreatePhantomHunks() {
        #expect(UnifiedDiffParser.parse("").hunks.isEmpty)
        #expect(UnifiedDiffParser.parse("Binary files a/image.png and b/image.png differ").hunks.isEmpty)
        let document = UnifiedDiffParser.parse("--- a/file\n+++ b/file\n@@ -1 +1 @@\n-old\n+new")
        #expect(document.rows.prefix(2).allSatisfy { $0.kind == .metadata })
    }

    @Test func rejectsMalformedHunksAndIgnoresOneTrailingPatchNewline() {
        #expect(UnifiedDiffParser.parse("@@ -1,2 +1,2 @@\n-old\n+new").hunks.isEmpty)
        #expect(UnifiedDiffParser.parse("@@ -1 +1 @@\nbare row").hunks.isEmpty)
        #expect(UnifiedDiffParser.parse("@@ -1 +1 @@garbage\n-old\n+new").hunks.isEmpty)
        #expect(UnifiedDiffParser.parse("@@ -99999999999999999999 +1 @@\n-old\n+new").hunks.isEmpty)

        let trailingNewline = UnifiedDiffParser.parse("@@ -1 +1 @@\n-old\n+new\n")
        #expect(trailingNewline.hunks.count == 1)
        #expect(trailingNewline.rows.count == 3)
    }

    @Test func preservesLongSourceLinesExactly() {
        let longLine = "result = buildAnIntentionallyLongLineThatRequiresHorizontalScrollingToReviewSafely()"
        let document = UnifiedDiffParser.parse("@@ -0,0 +1 @@\n+\(longLine)")
        #expect(document.hunks.first?.rows.first?.text == longLine)
    }
}

@MainActor
struct ChangesViewModelTests {
    @Test func exposesSourceSpecificEmptyCopy() {
        #expect(ChangeSource.lastTurn.emptyTitle == "No changes")
        #expect(ChangeSource.workingTree.emptyDetail == "The working tree has no uncommitted changes.")
        #expect(ChangeSource.mainBranch.emptyTitle == "No Main branch changes")
    }

    @Test func loadsPathSortedChangeSetWithAggregateCountsAndScopesRequest() async {
        let repository = ChangesSessionRepository(results: [.success([
            Self.file("file2.swift", additions: 3, deletions: 2),
            Self.file("file10.swift", additions: 2, deletions: 1),
        ])])
        let viewModel = ChangesViewModel(sessionID: "ses-1", worktree: "/repo", messageID: "msg-1", repository: repository)
        await viewModel.load()

        guard case let .content(content) = viewModel.state.phase else { Issue.record("Expected content"); return }
        #expect(content.files.map(\.path) == ["file10.swift", "file2.swift"])
        #expect(content.selectedFile.path == "file10.swift")
        #expect(content.additions == 5)
        #expect(content.deletions == 3)
        #expect(content.aggregateAccessibilityLabel == "2 changed files, 5 additions, 3 deletions")
        #expect(repository.requests == [ChangesSessionRepository.Request(sessionID: "ses-1", messageID: "msg-1", directory: "/repo")])
    }

    @Test func filtersSelectsAndClearsWithExplicitNoMatchesState() async {
        let viewModel = await loadedViewModel("app/Main.swift", "docs/Plan.md", "test/MainTests.swift")

        viewModel.updateFilter("main")
        var content = try! #require(viewModel.content)
        #expect(content.filteredFiles.map(\.path) == ["app/Main.swift", "test/MainTests.swift"])
        #expect(content.selectedFile.path == "app/Main.swift")

        viewModel.selectFile("test/MainTests.swift")
        content = try! #require(viewModel.content)
        #expect(content.selectedFile.path == "test/MainTests.swift")

        viewModel.updateFilter("missing")
        content = try! #require(viewModel.content)
        #expect(content.filteredFiles.isEmpty)
        #expect(content.selectedFile.path == "test/MainTests.swift")

        viewModel.clearFilter()
        content = try! #require(viewModel.content)
        #expect(content.filteredFiles.count == 3)
        #expect(content.selectedFile.path == "test/MainTests.swift")
    }

    @Test func previousAndNextMoveWithinFilteredFilesAndExposeEnabledStates() async {
        let viewModel = await loadedViewModel("app/A.swift", "app/B.swift", "docs/C.md")
        viewModel.updateFilter("app/")

        var content = try! #require(viewModel.content)
        #expect(!content.canSelectPrevious)
        #expect(content.canSelectNext)

        viewModel.selectNextFile()
        content = try! #require(viewModel.content)
        #expect(content.selectedFile.path == "app/B.swift")
        #expect(content.canSelectPrevious)
        #expect(!content.canSelectNext)

        viewModel.selectPreviousFile()
        #expect(viewModel.content?.selectedFile.path == "app/A.swift")
    }

    @Test func exposesSelectedFileStatusPositionCountsAndAccessibilityLabel() async {
        let viewModel = await loadedViewModel("Sources/App.swift", "Sources/New.swift")
        viewModel.selectFile("Sources/New.swift")
        let content = try! #require(viewModel.content)

        #expect(content.selectedFileSummary == "Added · 2 of 2")
        #expect(content.filePickerSummary(content.selectedFile) == "Added · +1 −1")
        #expect(content.selectedFileAccessibilityLabel == "Sources/New.swift, Added, file 2 of 2, 1 addition, 1 deletion")
    }

    @Test func navigatesHunksTogglesContextAndKeepsReviewedFilesVisible() async {
        let patch = "@@ -1 +1 @@\n-old\n+new\n@@ -20 +20 @@\n-before\n+after"
        let files = [
            FileDiff(path: "A.swift", patch: patch, additions: 2, deletions: 2, status: .modified),
            Self.file("B.swift"),
        ]
        let viewModel = ChangesViewModel(
            sessionID: "s",
            worktree: "/w",
            messageID: "m",
            repository: ChangesSessionRepository(results: [.success(files)])
        )
        await viewModel.load()

        var content = try! #require(viewModel.content)
        #expect(content.selectedHunkPositionLabel == "Hunk 1 of 2")
        #expect(!content.canSelectPreviousHunk && content.canSelectNextHunk)
        #expect(content.contextMode == .collapsed)

        viewModel.selectNextHunk()
        viewModel.toggleContextMode()
        viewModel.toggleSelectedFileReviewed()
        content = try! #require(viewModel.content)
        #expect(content.selectedHunkPositionLabel == "Hunk 2 of 2")
        #expect(content.canSelectPreviousHunk && !content.canSelectNextHunk)
        #expect(content.contextMode == .expanded)
        #expect(content.selectedFileReviewed)
        #expect(content.files.count == 2)

        viewModel.selectFile("B.swift")
        content = try! #require(viewModel.content)
        #expect(!content.selectedFileReviewed)
        #expect(content.reviewedPaths == ["A.swift"])
        #expect(content.selectedHunkPositionLabel == "Hunk 1 of 1")

        viewModel.prepareForSourceSwitch()
        content = try! #require(viewModel.content)
        #expect(content.reviewedPaths.isEmpty)
        #expect(content.contextMode == .collapsed)
    }

    @Test func explicitRefreshClearsReviewedMarkersAndViewerControls() async {
        let files = [Self.file("A.swift")]
        let repository = ChangesSessionRepository(results: [.success(files), .success(files)])
        let viewModel = ChangesViewModel(sessionID: "s", worktree: "/w", messageID: "m", repository: repository)
        await viewModel.load()
        viewModel.toggleSelectedFileReviewed()
        viewModel.toggleContextMode()

        await viewModel.refresh()

        let content = try! #require(viewModel.content)
        #expect(content.reviewedPaths.isEmpty)
        #expect(content.contextMode == .collapsed)
        #expect(content.selectedHunkPositionLabel == "Hunk 1 of 1")
        #expect(repository.requests.count == 2)
    }

    @Test func repositoryUpdatesDoNotMutateOpenSnapshotUntilExplicitRefresh() async {
        let repository = ChangesSessionRepository(results: [
            .success([Self.file("snapshot.swift")]),
            .success([Self.file("refreshed.swift")]),
        ])
        let viewModel = ChangesViewModel(sessionID: "s", worktree: "/w", messageID: "m", repository: repository)
        await viewModel.load()

        // Foreground events update other live stores; Changes intentionally owns no event or polling subscription.
        await Task.yield()
        #expect(viewModel.content?.selectedFile.path == "snapshot.swift")
        #expect(repository.requests.count == 1)

        await viewModel.refresh()
        #expect(viewModel.content?.selectedFile.path == "refreshed.swift")
        #expect(repository.requests.count == 2)
    }

    @Test func workingTreeEntryLoadsVcsSourcesAndGitDiff() async {
        let repository = ChangesSessionRepository(
            results: [],
            vcsInfoResults: [.success(VcsInfo(currentBranch: "feature/review", defaultBranch: "main"))],
            vcsDiffResults: [.success([Self.file("working.swift")])]
        )
        let viewModel = ChangesViewModel(
            sessionID: "s", worktree: "/w", messageID: "m", initialSource: .workingTree, repository: repository
        )

        await viewModel.load()

        #expect(viewModel.state.selectedSource == .workingTree)
        #expect(viewModel.state.availableSources == [.lastTurn, .workingTree, .mainBranch])
        #expect(viewModel.content?.selectedFile.path == "working.swift")
        #expect(repository.vcsRequests == [.init(mode: .workingTree, directory: "/w", context: 12)])
    }

    @Test func unsupportedVcsFallsBackToLastTurnButTransientFailureIsRetryable() async {
        let unsupported = ChangesSessionRepository(
            results: [.success([Self.file("last.swift")])],
            vcsInfoResults: [.success(VcsInfo(currentBranch: nil, defaultBranch: nil))]
        )
        let fallback = ChangesViewModel(
            sessionID: "s", worktree: "/w", messageID: "m", initialSource: .workingTree, repository: unsupported
        )
        await fallback.load()
        #expect(fallback.state.selectedSource == .lastTurn)
        #expect(fallback.content?.selectedFile.path == "last.swift")

        let transient = ChangesSessionRepository(
            results: [],
            vcsInfoResults: [.failure(SessionError.transport("offline"))]
        )
        let retryable = ChangesViewModel(
            sessionID: "s", worktree: "/w", messageID: "m", initialSource: .workingTree, repository: transient
        )
        await retryable.load()
        #expect(retryable.state.phase == .error("offline"))
        #expect(transient.requests.isEmpty)
    }

    @Test func sourceSwitchAndRefreshReloadSelectedStableSnapshotAndResetControls() async {
        let repository = ChangesSessionRepository(
            results: [.success([Self.file("last.swift")])],
            vcsInfoResults: [
                .success(VcsInfo(currentBranch: "feature/review", defaultBranch: "main")),
                .success(VcsInfo(currentBranch: "feature/review", defaultBranch: "main")),
            ],
            vcsDiffResults: [
                .success([Self.file("working.swift")]),
                .success([Self.file("main.swift")]),
                .success([Self.file("main-refreshed.swift")]),
            ]
        )
        let viewModel = ChangesViewModel(
            sessionID: "s", worktree: "/w", messageID: "m", initialSource: .workingTree, repository: repository
        )
        await viewModel.load()
        viewModel.toggleContextMode()
        viewModel.toggleSelectedFileReviewed()

        await viewModel.selectSource(.lastTurn)
        await viewModel.selectSource(.mainBranch)
        #expect(viewModel.content?.selectedFile.path == "main.swift")
        #expect(viewModel.content?.reviewedPaths.isEmpty == true)
        #expect(viewModel.content?.contextMode == .collapsed)

        await viewModel.refresh()
        #expect(viewModel.content?.selectedFile.path == "main-refreshed.swift")
        #expect(viewModel.state.selectedSource == .mainBranch)
    }

    @Test func missingVcsDiffEndpointFallsBackToLastTurn() async {
        let repository = ChangesSessionRepository(
            results: [.success([Self.file("last.swift")])],
            vcsInfoResults: [.success(VcsInfo(currentBranch: "feature/review", defaultBranch: "main"))],
            vcsDiffResults: [.failure(SessionError.http(statusCode: 404, message: "missing"))]
        )
        let viewModel = ChangesViewModel(
            sessionID: "s", worktree: "/w", messageID: "m", initialSource: .workingTree, repository: repository
        )

        await viewModel.load()

        #expect(viewModel.state.selectedSource == .lastTurn)
        #expect(viewModel.state.availableSources == [.lastTurn])
        #expect(viewModel.content?.selectedFile.path == "last.swift")
    }

    @Test func coversNoTurnNoChangesUnavailablePatchErrorAndRetry() async {
        let noTurn = ChangesViewModel(sessionID: "s", worktree: "/w", messageID: nil, repository: ChangesSessionRepository(results: []))
        await noTurn.load()
        #expect(noTurn.state.phase == .noTurn)

        let repository = ChangesSessionRepository(results: [
            .success([]),
            .success([FileDiff(path: "binary.png", patch: nil, additions: 0, deletions: 0, status: .modified)]),
            .failure(SessionError.transport("offline")),
            .success([Self.file("ok.swift")]),
        ])
        let viewModel = ChangesViewModel(sessionID: "s", worktree: "/w", messageID: "m", repository: repository)
        await viewModel.load(); #expect(viewModel.state.phase == .noChanges)
        await viewModel.retry()
        #expect(viewModel.content?.selectedFile == FileDiff(path: "binary.png", patch: nil, additions: 0, deletions: 0, status: .modified))
        #expect(viewModel.content?.selectedFileHasPreview == false)
        await viewModel.retry(); #expect(viewModel.state.phase == .error("offline"))
        await viewModel.retry(); #expect(viewModel.content?.selectedFile == Self.file("ok.swift"))
    }

    @Test func reviewChangesNavigationCapturesSessionWorktreeAndLatestCompletedUserMessage() {
        var state = SessionChatUiState.initial(sessionID: "s", sessionTitle: "Session")
        state.messages = [row("user-1", role: .user), row("assistant", role: .assistant), row("pending", role: .user, pending: true)]
        #expect(state.changesNavigationTarget(worktree: "/repo") == ChangesNavigationTarget(sessionID: "s", worktree: "/repo", messageID: "user-1"))
    }

    private func loadedViewModel(_ paths: String...) async -> ChangesViewModel {
        let files = paths.enumerated().map { index, path in
            Self.file(path, status: index == 1 ? .added : .modified)
        }
        let viewModel = ChangesViewModel(
            sessionID: "s",
            worktree: "/w",
            messageID: "m",
            repository: ChangesSessionRepository(results: [.success(files)])
        )
        await viewModel.load()
        return viewModel
    }

    private static func file(
        _ path: String,
        additions: Int = 1,
        deletions: Int = 1,
        status: FileDiffStatus = .modified
    ) -> FileDiff {
        FileDiff(path: path, patch: "@@ -1,1 +1,1 @@\n-old\n+new", additions: additions, deletions: deletions, status: status)
    }

    private func row(_ id: String, role: MessageRole, pending: Bool = false) -> SessionChatRow {
        SessionChatRow(id: id, role: role, text: "", metadataLabel: "", isPending: pending, isStreaming: false, renderedMarkdown: nil, modelProviderID: nil, modelID: nil, contextInputTokens: nil)
    }
}

private final class ChangesSessionRepository: SessionRepository, @unchecked Sendable {
    struct Request: Equatable { let sessionID: String; let messageID: String?; let directory: String? }
    struct VcsRequest: Equatable { let mode: VcsDiffMode; let directory: String?; let context: Int }
    var requests: [Request] = []
    var vcsRequests: [VcsRequest] = []
    private var results: [Result<[FileDiff], Error>]
    private var vcsInfoResults: [Result<VcsInfo, Error>]
    private var vcsDiffResults: [Result<[FileDiff], Error>]
    init(
        results: [Result<[FileDiff], Error>],
        vcsInfoResults: [Result<VcsInfo, Error>] = [],
        vcsDiffResults: [Result<[FileDiff], Error>] = []
    ) {
        self.results = results
        self.vcsInfoResults = vcsInfoResults
        self.vcsDiffResults = vcsDiffResults
    }
    func getSessionDiff(id: String, messageID: String?, directory: String?) async throws -> [FileDiff] {
        requests.append(.init(sessionID: id, messageID: messageID, directory: directory)); return try results.removeFirst().get()
    }
    func getVcsInfo(directory: String?) async throws -> VcsInfo {
        guard !vcsInfoResults.isEmpty else { throw SessionError.http(statusCode: 404, message: "unsupported") }
        return try vcsInfoResults.removeFirst().get()
    }
    func getVcsDiff(mode: VcsDiffMode, directory: String?, context: Int) async throws -> [FileDiff] {
        vcsRequests.append(.init(mode: mode, directory: directory, context: context))
        return try vcsDiffResults.removeFirst().get()
    }
    func listPendingPermissions(directory: String?) async throws -> [PermissionRequest] { [] }
    func listSessions(directory: String?) async throws -> [Session] { [] }
    func createSession(parentID: String?, title: String?, directory: String?) async throws -> Session { fatalError() }
    func getAllSessionStatuses(directory: String?) async throws -> [SessionStatus] { [] }
    func getSession(id: String, directory: String?) async throws -> Session { fatalError() }
    func updateSession(id: String, title: String?) async throws -> Session { fatalError() }
    func deleteSession(id: String) async throws -> Bool { false }
    func listChildSessions(id: String) async throws -> [Session] { [] }
    func listSessionTodos(id: String) async throws -> [SessionTodo] { [] }
    func initializeSessionAgents(id: String, messageID: String, providerID: String, modelID: String) async throws -> Bool { false }
    func forkSession(id: String, atMessageID: String?) async throws -> Session { fatalError() }
    func abortSession(id: String, directory: String?) async throws -> Bool { false }
    func shareSession(id: String) async throws -> Session { fatalError() }
    func unshareSession(id: String) async throws -> Session { fatalError() }
    func summarizeSession(id: String, providerID: String, modelID: String) async throws -> Bool { false }
    func revertSession(id: String, messageID: String, partID: String?) async throws -> Bool { false }
    func unrevertSession(id: String) async throws -> Bool { false }
    func respondToPermission(sessionID: String, permissionID: String, decision: PermissionDecision, directory: String?) async throws -> Bool { false }
}
