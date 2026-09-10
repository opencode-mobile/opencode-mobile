import Foundation
import Testing
@testable import OpenCode_Mobile

struct OpenCodeTextInputTests {
    @Test func accessibilityLabelPrefersExplicitValueThenHint() {
        #expect(OpenCodeTextInput.resolveAccessibilityLabel(hint: "Filter", accessibilityLabel: "Filter changed files") == "Filter changed files")
        #expect(OpenCodeTextInput.resolveAccessibilityLabel(hint: "Server URL", accessibilityLabel: nil) == "Server URL")
        #expect(OpenCodeTextInput.resolveAccessibilityLabel(hint: "Visible hint", accessibilityLabel: "Explicit") == "Explicit")
    }

    @Test func blankHintAndAccessibilityLabelFailContract() {
        #expect(OpenCodeTextInput.resolveAccessibilityLabel(hint: nil, accessibilityLabel: nil) == nil)
        #expect(OpenCodeTextInput.resolveAccessibilityLabel(hint: "  ", accessibilityLabel: "\n") == nil)
        #expect(OpenCodeTextInput.resolveAccessibilityLabel(hint: "", accessibilityLabel: nil) == nil)
    }
}

@MainActor struct FileDiffAdapterTests {
    @Test func decodesServerShapeAndSynthesizesDomainPatchAndStatus() throws {
        let data = Data(#"{"file":"Sources/App.swift","before":"old","after":"new","additions":1,"deletions":1}"#.utf8)
        let diff = SessionDomainAdapter().fileDiff(from: try JSONDecoder().decode(FileDiffDTO.self, from: data))

        #expect(diff.path == "Sources/App.swift")
        #expect(diff.status == .modified)
        #expect(diff.patch == "@@ -1,1 +1,1 @@\n-old\n+new")
        #expect(diff.additions == 1)
        #expect(diff.deletions == 1)
    }

    @Test func synthesizesAddedAndDeletedFilesWithoutPhantomBlankLines() throws {
        let added = try JSONDecoder().decode(FileDiffDTO.self, from: Data(#"{"file":"new.txt","before":"","after":"hello","additions":1}"#.utf8))
        let deleted = try JSONDecoder().decode(FileDiffDTO.self, from: Data(#"{"file":"old.txt","before":"goodbye","after":"","deletions":1}"#.utf8))
        #expect(SessionDomainAdapter().fileDiff(from: added).patch == "@@ -0,0 +1,1 @@\n+hello")
        #expect(SessionDomainAdapter().fileDiff(from: deleted).patch == "@@ -1,1 +0,0 @@\n-goodbye")
    }
}

struct UnifiedDiffParserTests {
    @Test func parsesHunksAndAdvancesLineNumbers() {
        let document = UnifiedDiffParser.parse("@@ -2,3 +2,3 @@\n same\n-old\n+new\n next\n@@ -20 +20 @@\n-before\n+after")
        #expect(document.hunks.count == 2)
        #expect(document.rows[1].oldLine == 2 && document.rows[1].newLine == 2)
        #expect(document.rows[2].oldLine == 3 && document.rows[2].newLine == nil)
        #expect(document.rows[3].oldLine == nil && document.rows[3].newLine == 3)
    }

    @Test func collapsedProjectionKeepsThreeContextLinesAroundChanges() {
        let before = (1...8).map { " before \($0)" }.joined(separator: "\n")
        let after = (1...8).map { " after \($0)" }.joined(separator: "\n")
        let document = UnifiedDiffParser.parse("@@ -1,17 +1,17 @@\n\(before)\n-old\n+new\n\(after)")
        #expect(document.visibleRows(contextMode: .expanded).count == 19)
        #expect(document.visibleRows(contextMode: .collapsed).count == 9)
    }

    @Test func emptyBinaryAndMalformedPatchesHaveNoHunks() {
        #expect(UnifiedDiffParser.parse("").hunks.isEmpty)
        #expect(UnifiedDiffParser.parse("Binary files differ").hunks.isEmpty)
        #expect(UnifiedDiffParser.parse("@@ -1,2 +1,2 @@\n-old\n+new").hunks.isEmpty)
    }
}

@MainActor
struct ChangesViewModelTests {
    @Test func exposesTwoSourcesWithDesktopCopy() {
        #expect(ChangeSource.workingTree.displayName == "Git changes")
        #expect(ChangeSource.lastTurn.displayName == "Last turn changes")
        #expect(ChangeSource.allCases == [.workingTree, .lastTurn])
    }

    @Test func loadsSortedSnapshotAndFilteredTotals() async {
        let repository = ChangesSessionRepository(results: [.success([
            Self.file("B.swift", additions: 3, deletions: 2),
            Self.file("A.swift", additions: 2, deletions: 1),
        ])])
        let viewModel = ChangesViewModel(sessionID: "s", worktree: "/repo", messageID: "m", repository: repository)
        await viewModel.load()

        #expect(viewModel.content?.files.map(\.path) == ["A.swift", "B.swift"])
        #expect(viewModel.content?.summary == "2 changed files")
        #expect(viewModel.content?.additions == 5)
        #expect(repository.requests == [.init(sessionID: "s", messageID: "m", directory: "/repo")])

        viewModel.updateFilter("B")
        #expect(viewModel.content?.filteredFiles.map(\.path) == ["B.swift"])
        #expect(viewModel.content?.summary == "1 changed file")
        #expect(viewModel.content?.additions == 3)
        #expect(viewModel.content?.deletions == 2)
        viewModel.updateFilter("missing")
        #expect(viewModel.content?.summary == "0 changed files")
        #expect(viewModel.content?.additions == 0 && viewModel.content?.deletions == 0)
    }

    @Test func refreshPreservesFilterUntilReplacementSnapshotArrives() async {
        let repository = ChangesSessionRepository(results: [
            .success([Self.file("keep.swift"), Self.file("other.swift")]),
            .success([Self.file("keep-new.swift")]),
        ])
        let viewModel = ChangesViewModel(sessionID: "s", worktree: "/w", messageID: "m", repository: repository)
        await viewModel.load()
        viewModel.updateFilter("keep")
        await viewModel.refresh()
        #expect(viewModel.content?.filter == "keep")
        #expect(viewModel.content?.filteredFiles.map(\.path) == ["keep-new.swift"])
        #expect(!viewModel.state.isRefreshing)
    }

    @Test func refreshFailureKeepsSnapshotAndExposesRetry() async {
        let repository = ChangesSessionRepository(results: [
            .success([Self.file("keep.swift")]),
            .failure(SessionError.transport("offline")),
        ])
        let viewModel = ChangesViewModel(sessionID: "s", worktree: "/w", messageID: "m", repository: repository)
        await viewModel.load()
        viewModel.updateFilter("keep")
        let refresh = Task { await viewModel.refresh() }
        viewModel.updateFilter("latest")
        await refresh.value

        #expect(viewModel.content?.files.map(\.path) == ["keep.swift"])
        #expect(viewModel.content?.filter == "latest")
        #expect(viewModel.state.refreshError == "offline")
        #expect(!viewModel.state.isRefreshing)
    }

    @Test func gitEntryUsesFixedSourceOrderAndWorktreeDiff() async {
        let repository = ChangesSessionRepository(
            results: [],
            vcsInfoResults: [.success(VcsInfo(currentBranch: "feature", defaultBranch: "main"))],
            vcsDiffResults: [.success([Self.file("git.swift")])]
        )
        let viewModel = ChangesViewModel(
            sessionID: "s",
            worktree: "/w",
            messageID: "m",
            initialSource: .workingTree,
            repository: repository
        )
        await viewModel.load()
        #expect(viewModel.state.availableSources == [.workingTree, .lastTurn])
        #expect(viewModel.state.selectedSource == .workingTree)
        #expect(repository.vcsRequests == [.init(mode: .workingTree, directory: "/w", context: 12)])
    }

    @Test func unsupportedGitFallsBackButTransientFailureIsRetryable() async {
        let unsupported = ChangesSessionRepository(
            results: [.success([Self.file("last.swift")])],
            vcsInfoResults: [.success(VcsInfo(currentBranch: nil, defaultBranch: nil))]
        )
        let fallback = ChangesViewModel(
            sessionID: "s", worktree: "/w", messageID: "m", initialSource: .workingTree, repository: unsupported
        )
        await fallback.load()
        #expect(fallback.state.selectedSource == .lastTurn)
        #expect(fallback.state.availableSources == [.lastTurn])

        let transient = ChangesSessionRepository(
            results: [],
            vcsInfoResults: [.failure(SessionError.transport("offline"))]
        )
        let retryable = ChangesViewModel(
            sessionID: "s", worktree: "/w", messageID: "m", initialSource: .workingTree, repository: transient
        )
        await retryable.load()
        guard case .error = retryable.state.phase else {
            Issue.record("Expected retryable error")
            return
        }
    }

    @Test func missingGitDiffEndpointFallsBackToLastTurn() async {
        let repository = ChangesSessionRepository(
            results: [.success([Self.file("last.swift")])],
            vcsInfoResults: [.success(VcsInfo(currentBranch: "feature", defaultBranch: "main"))],
            vcsDiffResults: [.failure(SessionError.http(statusCode: 404, message: "missing"))]
        )
        let viewModel = ChangesViewModel(
            sessionID: "s", worktree: "/w", messageID: "m", initialSource: .workingTree, repository: repository
        )
        await viewModel.load()
        #expect(viewModel.state.selectedSource == .lastTurn)
        #expect(viewModel.content?.files.first?.path == "last.swift")
    }

    @Test func v2HidesLastTurnAndKeepsWorkingTree() async {
        let repository = ChangesSessionRepository(
            results: [],
            vcsInfoResults: [.success(VcsInfo(currentBranch: "feature", defaultBranch: "main"))],
            vcsDiffResults: [.success([Self.file("git.swift")])]
        )
        let viewModel = ChangesViewModel(
            sessionID: "s",
            worktree: "/w",
            messageID: "m",
            initialSource: .lastTurn,
            repository: repository,
            capabilities: .forProtocol(.v2)
        )
        await viewModel.load()
        #expect(viewModel.state.availableSources == [.workingTree])
        #expect(viewModel.state.selectedSource == .workingTree)
        #expect(repository.requests.isEmpty)
        #expect(repository.vcsRequests == [.init(mode: .workingTree, directory: "/w", context: 12)])
    }

    @Test func v2DoesNotExposeLastTurnBeforeProbe() async {
        let repository = ChangesSessionRepository(results: [])
        let viewModel = ChangesViewModel(
            sessionID: "s",
            worktree: "/w",
            messageID: "m",
            initialSource: .lastTurn,
            repository: repository,
            capabilities: .forProtocol(.v2)
        )
        #expect(viewModel.state.availableSources == [.workingTree])
        #expect(viewModel.state.selectedSource == .workingTree)
        await viewModel.selectSource(.lastTurn)
        #expect(viewModel.state.selectedSource == .workingTree)
        #expect(repository.requests.isEmpty)
    }

    @Test func loadUsesLiveCapabilitiesAfterProtocolDetection() async {
        var serverProtocol = ServerProtocol.v1
        let repository = ChangesSessionRepository(
            results: [],
            vcsInfoResults: [.success(VcsInfo(currentBranch: "feature", defaultBranch: "main"))],
            vcsDiffResults: [.success([Self.file("git.swift")])]
        )
        let viewModel = ChangesViewModel(
            sessionID: "s",
            worktree: "/w",
            messageID: "m",
            initialSource: .lastTurn,
            repository: repository,
            capabilitiesProvider: { .forProtocol(serverProtocol) }
        )
        serverProtocol = .v2
        await viewModel.load()
        #expect(viewModel.state.availableSources == [.workingTree])
        #expect(viewModel.state.selectedSource == .workingTree)
        #expect(repository.requests.isEmpty)
        #expect(repository.vcsRequests == [.init(mode: .workingTree, directory: "/w", context: 12)])
    }

    @Test func v2WithoutGitShowsEmptyChanges() async {
        let repository = ChangesSessionRepository(
            results: [],
            vcsInfoResults: [.success(VcsInfo(currentBranch: nil, defaultBranch: nil))]
        )
        let viewModel = ChangesViewModel(
            sessionID: "s",
            worktree: "/w",
            messageID: "m",
            initialSource: .lastTurn,
            repository: repository,
            capabilities: .forProtocol(.v2)
        )
        await viewModel.load()
        #expect(viewModel.state.availableSources.isEmpty)
        guard case .noChanges = viewModel.state.phase else {
            Issue.record("Expected empty changes on v2 without git")
            return
        }
        #expect(repository.requests.isEmpty)
    }

    @Test func viewerStartsCollapsedNavigatesHunksAndDisablesUnavailableActions() {
        let twoHunks = FileDiff(
            path: "A.swift",
            patch: "@@ -1 +1 @@\n-old\n+new\n@@ -5 +5 @@\n-before\n+after",
            additions: 2,
            deletions: 2,
            status: .modified
        )
        var viewer = FileViewerState(file: twoHunks)
        #expect(viewer.hasPreview)
        #expect(!viewer.canSelectPreviousHunk)
        #expect(viewer.canSelectNextHunk)
        #expect(!viewer.canExpandContext)
        viewer.selectNextHunk()
        #expect(viewer.canSelectPreviousHunk)
        #expect(!viewer.canSelectNextHunk)

        let before = (1...8).map { " before \($0)" }.joined(separator: "\n")
        let after = (1...8).map { " after \($0)" }.joined(separator: "\n")
        var expandable = FileViewerState(file: FileDiff(
            path: "B.swift",
            patch: "@@ -1,17 +1,17 @@\n\(before)\n-old\n+new\n\(after)",
            additions: 1,
            deletions: 1,
            status: .modified
        ))
        #expect(expandable.canExpandContext)
        expandable.toggleContextMode()
        #expect(expandable.contextMode == .expanded)
        #expect(FileViewerState(file: FileDiff(path: "image.png", patch: nil, additions: 0, deletions: 0, status: .modified)).hasPreview == false)
    }

    @Test func entryPointsChooseGitAndLastTurn() {
        var state = SessionChatUiState.initial(sessionID: "s", sessionTitle: "Session")
        state.timeline = .ready
        state.messages = [row("user", .user), row("assistant", .assistant)]
        state.reviewChangesMessageID = "user"
        state.reviewChangesSummary = ReviewChangesSummary(fileCount: 1, additions: 1, deletions: 0)
        #expect(state.changesNavigationTarget(worktree: "/repo").initialSource == .workingTree)
        #expect(state.reviewChangesNavigationTarget(worktree: "/repo")?.initialSource == .lastTurn)
    }

    @Test func v2HidesLastTurnNavigationTargets() {
        var state = SessionChatUiState.initial(sessionID: "s", sessionTitle: "Session")
        state.timeline = .ready
        state.messages = [row("user", .user), row("assistant", .assistant)]
        state.reviewChangesMessageID = "user"
        state.reviewChangesSummary = ReviewChangesSummary(fileCount: 1, additions: 1, deletions: 0)
        state.supportsSessionMessageDiff = false
        #expect(state.reviewChangesNavigationTarget(worktree: "/repo") == nil)
        #expect(
            state.fileChangeNavigationTarget(
                worktree: "/repo",
                assistantMessageID: "assistant",
                initialFilePath: "src/Main.kt"
            ) == nil
        )
    }

    @Test func fileChangeNavigationUsesOwningTurnAndOptionalPath() {
        var state = SessionChatUiState.initial(sessionID: "s", sessionTitle: "Session")
        state.timeline = .ready
        state.messages = [
            row("user-1", .user),
            row("assistant-1", .assistant),
            row("user-2", .user),
            row("assistant-2", .assistant),
        ]
        let single = state.fileChangeNavigationTarget(
            worktree: "/repo",
            assistantMessageID: "assistant-1",
            initialFilePath: "src/Main.kt"
        )
        #expect(single?.messageID == "user-1")
        #expect(single?.initialSource == .lastTurn)
        #expect(single?.initialFilePath == "src/Main.kt")

        let multi = state.fileChangeNavigationTarget(
            worktree: "/repo",
            assistantMessageID: "assistant-2",
            initialFilePath: nil
        )
        #expect(multi?.messageID == "user-2")
        #expect(multi?.initialFilePath == nil)
    }

    private static func file(
        _ path: String,
        additions: Int = 1,
        deletions: Int = 1,
        status: FileDiffStatus = .modified
    ) -> FileDiff {
        FileDiff(path: path, patch: "@@ -1 +1 @@\n-old\n+new", additions: additions, deletions: deletions, status: status)
    }

    private func row(_ id: String, _ role: MessageRole) -> SessionChatRow {
        SessionChatRow(
            id: id,
            role: role,
            text: "",
            metadataLabel: "",
            isPending: false,
            isStreaming: false,
            renderedMarkdown: nil,
            modelProviderID: nil,
            modelID: nil,
            contextInputTokens: nil
        )
    }
}

struct ChangedFileGroupsTests {
    @Test func stripsSharedPrefixIntoRelativeFolders() {
        let groups = groupChangedFiles([
            file("android/app/src/main/java/dev/opencode/mobile/ui/changes/ChangesScreen.kt"),
            file("android/app/src/main/java/dev/opencode/mobile/ui/changes/ChangesViewModel.kt"),
            file("android/app/src/main/java/dev/opencode/mobile/ui/sessionchat/SessionChatScreen.kt"),
        ])
        #expect(groups.map(\.directory) == ["changes", "sessionchat"])
        #expect(groups[0].files.map { changedFileName($0.path) } == ["ChangesScreen.kt", "ChangesViewModel.kt"])
        #expect(groups[1].files.map { changedFileName($0.path) } == ["SessionChatScreen.kt"])
    }

    @Test func keepsDotGroupForFilesInTheSharedDirectory() {
        let groups = groupChangedFiles([
            file("android/app/src/main/java/dev/opencode/mobile/OpenCodeApplication.kt"),
            file("android/app/src/main/java/dev/opencode/mobile/ui/changes/ChangesScreen.kt"),
        ])
        #expect(groups.map(\.directory) == [".", "ui/changes"])
        #expect(changedFileName(groups[0].files[0].path) == "OpenCodeApplication.kt")
        #expect(changedFileName(groups[1].files[0].path) == "ChangesScreen.kt")
    }

    @Test func usesFullFolderWhenEveryFileSharesOneParent() {
        let groups = groupChangedFiles([
            file("Sources/App.swift"),
            file("Sources/New.swift"),
            file("Sources/Removed.swift"),
        ])
        #expect(groups.map(\.directory) == ["Sources"])
        #expect(groups[0].files.map { changedFileName($0.path) } == ["App.swift", "New.swift", "Removed.swift"])
    }

    @Test func groupsMixedRootsWithoutASharedPrefix() {
        let groups = groupChangedFiles([
            file("android/Foo.kt"),
            file("docs/list-item.md"),
            file("ios/Bar.swift"),
        ])
        #expect(groups.map(\.directory) == ["android", "docs", "ios"])
    }

    @Test func groupsRootLevelFiles() {
        let groups = groupChangedFiles([file("README.md"), file("LICENSE")])
        #expect(groups.map(\.directory) == ["."])
        #expect(groups[0].files.map { changedFileName($0.path) } == ["README.md", "LICENSE"])
    }

    @Test func fileGroupsFollowTheFilter() {
        let content = ChangesContentState(
            files: [
                file("android/app/src/main/java/dev/opencode/mobile/ui/changes/ChangesScreen.kt"),
                file("android/app/src/main/java/dev/opencode/mobile/ui/sessionchat/SessionChatScreen.kt"),
            ],
            filter: "changes"
        )
        #expect(content.fileGroups.map(\.directory) == ["changes"])
        #expect(changedFileName(content.fileGroups[0].files[0].path) == "ChangesScreen.kt")
    }

    @Test func filterKeepsUnfilteredPrefix() {
        let content = ChangesContentState(
            files: [
                file("android/app/src/main/java/dev/opencode/mobile/OpenCodeApplication.kt"),
                file("android/app/src/main/java/dev/opencode/mobile/ui/changes/ChangesScreen.kt"),
            ],
            filter: "ChangesScreen"
        )
        #expect(content.fileGroups.map(\.directory) == ["ui/changes"])
    }

    @Test func emptyFilesYieldNoGroups() {
        #expect(groupChangedFiles([]).isEmpty)
    }

    private func file(_ path: String) -> FileDiff {
        FileDiff(path: path, patch: nil, additions: 1, deletions: 0, status: .modified)
    }
}

struct ReviewChangesPillVisibilityTests {
    @Test func showsWhenLatestTurnChangedFiles() {
        let summary = ReviewChangesSummary(fileCount: 2, additions: 4, deletions: 1)
        #expect(ReviewChangesPillVisibility.shouldShow(summary: summary))
    }

    @Test func hidesWhenSummaryMissingOrEmpty() {
        #expect(!ReviewChangesPillVisibility.shouldShow(summary: nil))
        #expect(!ReviewChangesPillVisibility.shouldShow(
            summary: ReviewChangesSummary(fileCount: 0, additions: 0, deletions: 0)
        ))
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
        requests.append(.init(sessionID: id, messageID: messageID, directory: directory))
        return try results.removeFirst().get()
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
    func updateSession(id: String, title: String?, directory: String?) async throws -> Session { fatalError() }
    func archiveSession(id: String, directory: String?) async throws -> Session { fatalError() }
    func deleteSession(id: String, directory: String?) async throws -> Bool { false }
    func listChildSessions(id: String) async throws -> [Session] { [] }
    func listSessionTodos(id: String, directory: String?) async throws -> [SessionTodo] { [] }
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
