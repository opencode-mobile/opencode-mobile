import Foundation
import Testing
@testable import OpenCode_Mobile

/// #752: the New worktree form Screen Model, its copy, and the row availability.
@MainActor
struct WorktreeCreateModelTests {
    // MARK: Name

    @Test func prefillsARandomAdjectiveNounName() {
        let model = WorktreeCreateModel(repository: FormWorktreeRepository(), randomIndex: { _ in 0 })
        model.open(origin: .runIn(projectKey: "prj"), serverProtocol: .v1, projectID: "prj", sourceDirectory: "/src/repo", sourceBranch: "main")
        #expect(model.state?.name == "brave-otter")

        let last = WorktreeCreateModel(repository: FormWorktreeRepository(), randomIndex: { $0 - 1 })
        last.open(origin: .runIn(projectKey: "prj"), serverProtocol: .v1, projectID: "prj", sourceDirectory: "/src/repo", sourceBranch: nil)
        #expect(last.state?.name == "zesty-willow")
    }

    @Test func nameRulesInOrder() {
        #expect(WorktreeNameRules.error(for: "   ") == "Enter a name")
        #expect(WorktreeNameRules.error(for: "-lead") == "Use letters, numbers, dots, dashes or underscores")
        #expect(WorktreeNameRules.error(for: "has space") == "Use letters, numbers, dots, dashes or underscores")
        #expect(WorktreeNameRules.error(for: "feature/x") == "Use letters, numbers, dots, dashes or underscores")
        #expect(WorktreeNameRules.error(for: String(repeating: "a", count: 65)) == "Use at most 64 characters")
        #expect(WorktreeNameRules.error(for: String(repeating: "a", count: 64)) == nil)
        #expect(WorktreeNameRules.error(for: " v1.2_fix-3 ") == nil)
    }

    @Test func nameErrorShowsOnlyAfterAnEdit() {
        let model = openV1()
        #expect(model.state?.nameError == nil)

        model.updateName("bad name")
        #expect(model.state?.nameError == "Use letters, numbers, dots, dashes or underscores")
        #expect(model.state?.canSubmit == false)

        model.updateName("")
        #expect(model.state?.nameError == "Enter a name")
        #expect(model.state?.branchValue == "opencode/\u{2026}")

        model.updateName("good")
        #expect(model.state?.nameError == nil)
        #expect(model.state?.canSubmit == true)
    }

    // MARK: Branch and base per protocol

    @Test func v1ShowsTheOpencodeBranchAndTheCurrentCheckoutAsBase() throws {
        let model = openV1(sourceBranch: "main")
        model.updateName(" fix-login ")
        let state = try #require(model.state)

        #expect(state.title == "New worktree")
        #expect(state.branchValue == "opencode/fix-login")
        #expect(state.branchNote == "opencode v1 names the branch after the worktree")
        #expect(state.baseValue == "main")
        #expect(state.baseNote == "opencode v1 creates from the project's current checkout")
        #expect(!state.baseSelectable)
        #expect(state.submitTitle == "Create and start")

        let unknown = openV1(sourceBranch: nil)
        #expect(unknown.state?.baseValue == "Current checkout")
        unknown.showBasePicker()
        #expect(unknown.state?.basePickerVisible == false)
    }

    @Test func v2ShowsADetachedCheckoutAndADefaultBase() async throws {
        let repository = FormWorktreeRepository(branches: ["main", "feature/x", "origin/main"])
        let model = WorktreeCreateModel(repository: repository, randomIndex: { _ in 0 })
        model.open(origin: .move(sessionID: "ses"), serverProtocol: .v2, projectID: "prj", sourceDirectory: "/src/repo-wt/", sourceBranch: "feature/x")

        let state = try #require(model.state)
        #expect(state.sourceDirectory == "/src/repo-wt")
        #expect(state.branchValue == "No branch (detached)")
        #expect(state.branchNote == "opencode v2 creates a detached checkout, shown by its folder name")
        #expect(state.baseValue == "feature/x (current)")
        #expect(state.baseNote == "Tap to pick a branch")
        #expect(state.baseSelectable)
        #expect(state.submitTitle == "Create and move")
        #expect(state.baseOptionsState == .loading)

        #expect(await waitFor { model.state?.baseOptionsState == .loaded })
        #expect(repository.branchDirectories == ["/src/repo-wt"])
        let rows = try #require(model.state?.baseRows)
        // Default first (the source branch, checked), the source branch is not repeated.
        #expect(rows.map(\.title) == ["feature/x (current)", "main", "origin/main"])
        #expect(rows.map(\.ref) == [nil, "main", "origin/main"])
        #expect(rows.map(\.isSelected) == [true, false, false])
        #expect(rows.map(\.accessibilityIdentifier) == [
            "worktreeCreateBaseOption:", "worktreeCreateBaseOption:main", "worktreeCreateBaseOption:origin/main",
        ])
    }

    @Test func detachedSourceDefaultsToTheCurrentCheckout() {
        let model = WorktreeCreateModel(repository: FormWorktreeRepository(), randomIndex: { _ in 0 })
        model.open(origin: .runIn(projectKey: "prj"), serverProtocol: .v2, projectID: "prj", sourceDirectory: "/src/repo", sourceBranch: "HEAD")
        #expect(model.state?.baseValue == "Current checkout")
        #expect(model.state?.baseRows.first?.title == "Current checkout")
    }

    @Test func baseSelectionReturnsToTheFormAndTheSourceBranchIsTheDefault() async {
        let repository = FormWorktreeRepository(branches: ["main", "feature/x"])
        let model = WorktreeCreateModel(repository: repository, randomIndex: { _ in 0 })
        model.open(origin: .runIn(projectKey: "prj"), serverProtocol: .v2, projectID: "prj", sourceDirectory: "/src/repo", sourceBranch: "main")

        model.showBasePicker()
        #expect(model.state?.basePickerVisible == true)
        model.selectBase("feature/x")
        #expect(model.state?.basePickerVisible == false)
        #expect(model.state?.baseValue == "feature/x")
        #expect(model.state?.request.baseRef == "feature/x")

        model.showBasePicker()
        model.selectBase("main")
        #expect(model.state?.selectedBaseRef == nil)
        #expect(model.state?.baseValue == "main (current)")
        #expect(model.state?.request.baseRef == nil)

        model.showBasePicker()
        model.selectBase(nil)
        #expect(model.state?.request.baseRef == nil)
    }

    @Test func failedBaseListKeepsTheDefaultPickableAndRetriesOnOpen() async {
        let repository = FormWorktreeRepository(branchesFail: true)
        let model = WorktreeCreateModel(repository: repository, randomIndex: { _ in 0 })
        model.open(origin: .runIn(projectKey: "prj"), serverProtocol: .v2, projectID: "prj", sourceDirectory: "/src/repo", sourceBranch: "main")
        #expect(await waitFor { model.state?.baseOptionsState == .failed })
        #expect(model.state?.baseRows.map(\.ref) == [nil])

        repository.branchesFail = false
        repository.branches = ["main", "dev"]
        model.showBasePicker()
        #expect(model.state?.baseOptionsState == .loading)
        #expect(await waitFor { model.state?.baseOptionsState == .loaded })
        #expect(model.state?.baseRows.map(\.ref) == [nil, "dev"])
    }

    @Test func backLeavesTheBaseListThenTheForm() {
        let model = WorktreeCreateModel(repository: FormWorktreeRepository(), randomIndex: { _ in 0 })
        model.open(origin: .runIn(projectKey: "prj"), serverProtocol: .v2, projectID: "prj", sourceDirectory: "/src/repo", sourceBranch: "main")
        model.showBasePicker()

        model.back()
        #expect(model.state?.basePickerVisible == false)
        model.back()
        #expect(model.state == nil)
    }

    // MARK: Submit

    @Test func submitSendsTheTrimmedNameAndBase() async {
        let repository = FormWorktreeRepository(
            created: .success(CreatedWorktree(directory: "/wt/brave-otter", name: "brave-otter", branch: nil))
        )
        let model = WorktreeCreateModel(repository: repository, randomIndex: { _ in 0 })
        model.open(origin: .runIn(projectKey: "prj"), serverProtocol: .v2, projectID: "prj", sourceDirectory: "/src/repo", sourceBranch: "main")
        model.selectBase("origin/main")

        let created = await model.submit()

        #expect(created?.directory == "/wt/brave-otter")
        #expect(repository.requests == [
            CreateWorktreeRequest(projectID: "prj", sourceDirectory: "/src/repo", name: "brave-otter", baseRef: "origin/main"),
        ])
        // Stays on Creating… until the owner closes it.
        #expect(model.state?.submitting == true)
        #expect(model.state?.submitTitle == "Creating\u{2026}")
        #expect(model.state?.canSubmit == false)
    }

    @Test func v1SubmitNeverSendsABase() async {
        let repository = FormWorktreeRepository(
            created: .success(CreatedWorktree(directory: "/wt/x", name: "x", branch: "opencode/x"))
        )
        let model = WorktreeCreateModel(repository: repository, randomIndex: { _ in 0 })
        model.open(origin: .runIn(projectKey: "prj"), serverProtocol: .v1, projectID: "prj", sourceDirectory: "/src/repo", sourceBranch: "main")
        model.selectBase("dev")

        _ = await model.submit()

        #expect(repository.requests.first?.baseRef == nil)
    }

    @Test func invalidNameIsNotSentAndShowsTheError() async {
        let repository = FormWorktreeRepository()
        let model = openV1(repository: repository)
        model.updateName("")
        let created = await model.submit()

        #expect(created == nil)
        #expect(repository.requests.isEmpty)
        #expect(model.state?.nameError == "Enter a name")
    }

    @Test func failedCreateKeepsTheValuesAndExplains() async throws {
        let repository = FormWorktreeRepository(created: .failure(WorktreeCreateError(message: "fatal: invalid reference: nope")))
        let model = WorktreeCreateModel(repository: repository, randomIndex: { _ in 0 })
        model.open(origin: .runIn(projectKey: "prj"), serverProtocol: .v2, projectID: "prj", sourceDirectory: "/src/repo", sourceBranch: "main")
        model.updateName("my-tree")
        model.selectBase("nope")

        let created = await model.submit()

        #expect(created == nil)
        let state = try #require(model.state)
        #expect(state.errorMessage == "Couldn't create the worktree: fatal: invalid reference: nope")
        #expect(state.name == "my-tree")
        #expect(state.selectedBaseRef == "nope")
        #expect(!state.submitting)
        #expect(state.canSubmit)
    }

    @Test func dismissDuringSubmitDropsTheAnswer() async {
        let repository = FormWorktreeRepository(
            created: .success(CreatedWorktree(directory: "/wt/x", name: "x", branch: nil)),
            gateCreate: true
        )
        let model = WorktreeCreateModel(repository: repository, randomIndex: { _ in 0 })
        model.open(origin: .runIn(projectKey: "prj"), serverProtocol: .v2, projectID: "prj", sourceDirectory: "/src/repo", sourceBranch: nil)

        let submit = Task { await model.submit() }
        #expect(await waitFor { model.state?.submitting == true && repository.requests.count == 1 })
        model.dismiss()
        repository.openCreate()

        #expect(await submit.value == nil)
        #expect(model.state == nil)
    }

    // MARK: Availability

    @Test func runInRowChecksInOrder() {
        func check(_ worktrees: Bool, _ git: Bool, _ id: String?, _ server: Bool) -> NewWorktreeAvailability {
            NewWorktreeAvailability.runIn(worktreesEnabled: worktrees, isRepository: git, projectID: id, serverCanCreate: server)
        }
        #expect(check(false, false, nil, false) == NewWorktreeAvailability(enabled: false, subtitle: "Turn on Worktrees in Settings"))
        #expect(check(true, false, nil, false) == NewWorktreeAvailability(enabled: false, subtitle: "Needs a git repository"))
        #expect(check(true, true, nil, false) == NewWorktreeAvailability(enabled: false, subtitle: "Needs a git repository with a commit"))
        #expect(check(true, true, "global", false) == NewWorktreeAvailability(enabled: false, subtitle: "Needs a git repository with a commit"))
        #expect(check(true, true, "prj", false) == NewWorktreeAvailability(enabled: false, subtitle: "This opencode server can't create worktrees"))
        #expect(check(true, true, "prj", true) == NewWorktreeAvailability(enabled: true, subtitle: "Create a checkout and start there"))
    }

    @Test func moveRowChecksInOrder() {
        func check(
            _ move: Bool, _ worktrees: Bool, _ created: Bool, _ git: Bool, _ id: String?, _ server: Bool
        ) -> NewWorktreeAvailability {
            NewWorktreeAvailability.move(
                supportsMove: move, worktreesEnabled: worktrees, sessionCreated: created,
                isRepository: git, projectID: id, serverCanCreate: server
            )
        }
        #expect(check(false, false, false, false, nil, false).subtitle == "Needs an opencode v2 server")
        #expect(check(true, false, false, false, nil, false).subtitle == "Turn on Worktrees in Settings")
        #expect(check(true, true, false, false, nil, false) == NewWorktreeAvailability(enabled: false, subtitle: nil))
        #expect(check(true, true, true, false, nil, false).subtitle == "Needs a git repository")
        #expect(check(true, true, true, true, "global", false).subtitle == "Needs a git repository with a commit")
        #expect(check(true, true, true, true, "prj", false).subtitle == "This opencode server can't create worktrees")
        #expect(check(true, true, true, true, "prj", true) == NewWorktreeAvailability(
            enabled: true, subtitle: "Create a checkout from this one and move there"
        ))
        #expect(!check(true, true, true, true, "prj", false).enabled)
    }

    // MARK: Helpers

    private func openV1(repository: FormWorktreeRepository = FormWorktreeRepository(), sourceBranch: String? = "main") -> WorktreeCreateModel {
        let model = WorktreeCreateModel(repository: repository, randomIndex: { _ in 0 })
        model.open(origin: .runIn(projectKey: "prj"), serverProtocol: .v1, projectID: "prj", sourceDirectory: "/src/repo", sourceBranch: sourceBranch)
        return model
    }

    private func waitFor(_ condition: @MainActor () -> Bool) async -> Bool {
        for _ in 0 ..< 200 {
            if condition() { return true }
            try? await Task.sleep(for: .milliseconds(5))
        }
        return condition()
    }
}

/// Scripted create and branch list for the form.
final class FormWorktreeRepository: WorktreeRepository, @unchecked Sendable {
    private let lock = NSLock()
    private let created: Result<CreatedWorktree, WorktreeCreateError>
    private let gateCreate: Bool
    private var createGate: CheckedContinuation<Void, Never>?
    private var createOpen = false
    private var recordedRequests: [CreateWorktreeRequest] = []
    private var recordedBranchDirectories: [String] = []
    private var scriptedBranches: [String]
    private var scriptedBranchesFail: Bool

    init(
        created: Result<CreatedWorktree, WorktreeCreateError> = .failure(WorktreeCreateError(message: "unused")),
        branches: [String] = [],
        branchesFail: Bool = false,
        gateCreate: Bool = false
    ) {
        self.created = created
        self.scriptedBranches = branches
        self.scriptedBranchesFail = branchesFail
        self.gateCreate = gateCreate
    }

    var requests: [CreateWorktreeRequest] { lock.withLock { recordedRequests } }
    var branchDirectories: [String] { lock.withLock { recordedBranchDirectories } }
    var branches: [String] {
        get { lock.withLock { scriptedBranches } }
        set { lock.withLock { scriptedBranches = newValue } }
    }
    var branchesFail: Bool {
        get { lock.withLock { scriptedBranchesFail } }
        set { lock.withLock { scriptedBranchesFail = newValue } }
    }

    func openCreate() {
        let gate = lock.withLock {
            createOpen = true
            defer { createGate = nil }
            return createGate
        }
        gate?.resume()
    }

    func load(addedPath: String, projectID: String?) async -> WorktreeLoad { .unloaded }
    func loadRefreshed(addedPath: String, projectID: String?) async -> WorktreeLoad { .unloaded }
    func refresh(projectID: String) async -> Bool { false }
    func refetch(projectID: String) async -> WorktreeLoad { .unloaded }
    func onDisconnected() {}
    func onConnection(key: String) {}
    var canCreateWorktrees: Bool { true }

    func create(_ request: CreateWorktreeRequest) async throws -> CreatedWorktree {
        lock.withLock { recordedRequests.append(request) }
        if gateCreate {
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                let resumeNow = lock.withLock {
                    if createOpen { return true }
                    createGate = continuation
                    return false
                }
                if resumeNow { continuation.resume() }
            }
        }
        return try created.get()
    }

    func baseBranches(directory: String) async throws -> [String] {
        let (fail, list) = lock.withLock {
            recordedBranchDirectories.append(directory)
            return (scriptedBranchesFail, scriptedBranches)
        }
        if fail { throw WorktreeRemoteError.http(statusCode: 500, body: "") }
        return list
    }
}
