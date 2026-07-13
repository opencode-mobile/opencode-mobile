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
        let rows = UnifiedDiffParser.parse("@@ -2,3 +2,3 @@\n same\n-old\n\\ No newline at end of file\n+new\n next")
        #expect(rows.map(\.kind) == [.hunk, .context, .deletion, .metadata, .addition, .context])
        #expect(rows[1].oldLine == 2 && rows[1].newLine == 2)
        #expect(rows[2].oldLine == 3 && rows[2].newLine == nil)
        #expect(rows[3].oldLine == nil && rows[3].newLine == nil)
        #expect(rows[4].oldLine == nil && rows[4].newLine == 3)
        #expect(rows[5].oldLine == 4 && rows[5].newLine == 4)
    }
}

@MainActor
struct ChangesViewModelTests {
    @Test func loadsFirstPathSortedFileAndScopesRequest() async {
        let repository = ChangesSessionRepository(results: [.success([
            Self.file("file2.swift"), Self.file("file10.swift"),
        ])])
        let viewModel = ChangesViewModel(sessionID: "ses-1", worktree: "/repo", messageID: "msg-1", repository: repository)
        await viewModel.load()

        #expect(viewModel.state == ChangesState.content(Self.file("file10.swift")))
        #expect(repository.requests == [ChangesSessionRepository.Request(sessionID: "ses-1", messageID: "msg-1", directory: "/repo")])
    }

    @Test func coversNoTurnNoChangesUnavailablePatchErrorAndRetry() async {
        let noTurn = ChangesViewModel(sessionID: "s", worktree: "/w", messageID: nil, repository: ChangesSessionRepository(results: []))
        await noTurn.load()
        #expect(noTurn.state == .noTurn)

        let repository = ChangesSessionRepository(results: [
            .success([]),
            .success([FileDiff(path: "binary.png", patch: nil, additions: 0, deletions: 0, status: .modified)]),
            .failure(SessionError.transport("offline")),
            .success([Self.file("ok.swift")]),
        ])
        let viewModel = ChangesViewModel(sessionID: "s", worktree: "/w", messageID: "m", repository: repository)
        await viewModel.load(); #expect(viewModel.state == .noChanges)
        await viewModel.retry(); #expect(viewModel.state == .unavailablePatch(FileDiff(path: "binary.png", patch: nil, additions: 0, deletions: 0, status: .modified)))
        await viewModel.retry(); #expect(viewModel.state == .error("offline"))
        await viewModel.retry(); #expect(viewModel.state == .content(Self.file("ok.swift")))
    }

    @Test func reviewChangesNavigationCapturesSessionWorktreeAndLatestCompletedUserMessage() {
        var state = SessionChatUiState.initial(sessionID: "s", sessionTitle: "Session")
        state.messages = [row("user-1", role: .user), row("assistant", role: .assistant), row("pending", role: .user, pending: true)]
        #expect(state.changesNavigationTarget(worktree: "/repo") == ChangesNavigationTarget(sessionID: "s", worktree: "/repo", messageID: "user-1"))
    }

    private static func file(_ path: String) -> FileDiff {
        FileDiff(path: path, patch: "@@ -1,1 +1,1 @@\n-old\n+new", additions: 1, deletions: 1, status: .modified)
    }

    private func row(_ id: String, role: MessageRole, pending: Bool = false) -> SessionChatRow {
        SessionChatRow(id: id, role: role, text: "", metadataLabel: "", isPending: pending, isStreaming: false, renderedMarkdown: nil, modelProviderID: nil, modelID: nil, contextInputTokens: nil)
    }
}

private final class ChangesSessionRepository: SessionRepository, @unchecked Sendable {
    struct Request: Equatable { let sessionID: String; let messageID: String?; let directory: String? }
    var requests: [Request] = []
    private var results: [Result<[FileDiff], Error>]
    init(results: [Result<[FileDiff], Error>]) { self.results = results }
    func getSessionDiff(id: String, messageID: String?, directory: String?) async throws -> [FileDiff] {
        requests.append(.init(sessionID: id, messageID: messageID, directory: directory)); return try results.removeFirst().get()
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
