import Foundation
import Observation

/// Where the New worktree form was opened from.
enum WorktreeCreateOrigin: Equatable, Sendable {
    /// Home New session Run in sheet: start a session in the new checkout.
    case runIn(projectKey: String)
    /// Session Chat Move sheet: move the session into the new checkout.
    case move(sessionID: String)
}

enum WorktreeCreateBaseOptionsState: Equatable, Sendable {
    case loading
    case loaded
    case failed
}

/// One row of the v2 base list. `ref == nil` is the default: the source checkout's HEAD.
struct WorktreeCreateBaseOption: Identifiable, Equatable, Sendable {
    let ref: String?
    let title: String
    let isSelected: Bool

    var id: String { ref ?? "" }
    var accessibilityIdentifier: String { "worktreeCreateBaseOption:" + (ref ?? "") }
}

/// The New worktree form. Read-only outside this file; `WorktreeCreateModel` owns every change.
struct WorktreeCreateFormState: Equatable, Sendable {
    let origin: WorktreeCreateOrigin
    let serverProtocol: ServerProtocol
    let projectID: String?
    /// Run in: the project's main checkout. Move: the session's current checkout.
    let sourceDirectory: String
    /// Branch of `sourceDirectory`; nil when unknown or detached.
    let sourceBranch: String?
    fileprivate(set) var name: String
    /// Shown after the first edit or a submit attempt.
    fileprivate(set) var nameErrorVisible = false
    /// v2 start ref; nil = the source checkout's HEAD.
    fileprivate(set) var selectedBaseRef: String?
    fileprivate(set) var baseOptions: [String] = []
    fileprivate(set) var baseOptionsState: WorktreeCreateBaseOptionsState
    /// v2: the sheet lists branches instead of the form.
    fileprivate(set) var basePickerVisible = false
    fileprivate(set) var submitting = false
    /// `Couldn't create the worktree: …`, shown above the button; the values stay.
    fileprivate(set) var errorMessage: String?

    var nameError: String? {
        nameErrorVisible ? WorktreeNameRules.error(for: name) : nil
    }

    var canSubmit: Bool {
        WorktreeNameRules.error(for: name) == nil && !submitting
    }

    var title: String { WorktreeCreateCopy.formTitle }

    /// Branch row: read-only on both protocols.
    var branchValue: String {
        serverProtocol == .v2 ? WorktreeCreateCopy.v2NoBranch : WorktreeCreateCopy.v1Branch(name)
    }

    var branchNote: String {
        serverProtocol == .v2 ? WorktreeCreateCopy.v2BranchNote : WorktreeCreateCopy.v1BranchNote
    }

    /// v2 picks a base; v1 always creates from the project's current checkout.
    var baseSelectable: Bool { serverProtocol == .v2 }

    var defaultBaseLabel: String {
        sourceBranch.map { $0 + WorktreeCreateCopy.currentSuffix } ?? WorktreeCreateCopy.currentCheckout
    }

    var baseValue: String {
        if serverProtocol == .v2 { return selectedBaseRef ?? defaultBaseLabel }
        return sourceBranch ?? WorktreeCreateCopy.currentCheckout
    }

    var baseNote: String {
        serverProtocol == .v2 ? WorktreeCreateCopy.basePickHint : WorktreeCreateCopy.v1BaseNote
    }

    var submitTitle: String {
        if submitting { return WorktreeCreateCopy.submitting }
        switch origin {
        case .runIn: return WorktreeCreateCopy.submitRunIn
        case .move: return WorktreeCreateCopy.submitMove
        }
    }

    /// Default (the source checkout) first, then the other refs the server listed.
    var baseRows: [WorktreeCreateBaseOption] {
        var rows = [WorktreeCreateBaseOption(ref: nil, title: defaultBaseLabel, isSelected: selectedBaseRef == nil)]
        var seen: Set<String> = []
        for ref in baseOptions where ref != sourceBranch && seen.insert(ref).inserted {
            rows.append(WorktreeCreateBaseOption(ref: ref, title: ref, isSelected: selectedBaseRef == ref))
        }
        return rows
    }

    var request: CreateWorktreeRequest {
        CreateWorktreeRequest(
            projectID: projectID,
            sourceDirectory: sourceDirectory,
            name: name.trimmingCharacters(in: .whitespacesAndNewlines),
            baseRef: serverProtocol == .v2 ? selectedBaseRef : nil
        )
    }
}

/// Screen Model of the New worktree form, shared by Home's Run in sheet and Session Chat's
/// Move sheet. The owner decides what a created worktree leads to.
@MainActor
@Observable
final class WorktreeCreateModel {
    /// Nil while the sheet shows its list.
    private(set) var state: WorktreeCreateFormState?

    @ObservationIgnored private let repository: any WorktreeRepository
    @ObservationIgnored private let randomIndex: (Int) -> Int
    @ObservationIgnored private var baseTask: Task<Void, Never>?
    /// Bumped on open and dismiss so a late answer for a closed form is dropped.
    @ObservationIgnored private var generation = 0

    init(
        repository: any WorktreeRepository,
        randomIndex: @escaping (Int) -> Int = { Int.random(in: 0 ..< max($0, 1)) }
    ) {
        self.repository = repository
        self.randomIndex = randomIndex
    }

    var isPresented: Bool { state != nil }

    func open(
        origin: WorktreeCreateOrigin,
        serverProtocol: ServerProtocol,
        projectID: String?,
        sourceDirectory: String,
        sourceBranch: String?
    ) {
        generation += 1
        baseTask?.cancel()
        let source = ServerPathPolicy.canonical(sourceDirectory)
        state = WorktreeCreateFormState(
            origin: origin,
            serverProtocol: serverProtocol,
            projectID: projectID,
            sourceDirectory: source,
            sourceBranch: HomeProjectGrouping.usableBranch(sourceBranch),
            name: WorktreeNameRules.randomName(randomIndex),
            baseOptionsState: serverProtocol == .v2 ? .loading : .loaded
        )
        if serverProtocol == .v2 {
            loadBaseOptions(directory: source)
        }
    }

    func updateName(_ name: String) {
        guard state != nil, state?.name != name else { return }
        state?.name = name
        state?.nameErrorVisible = true
    }

    func showBasePicker() {
        guard let current = state, current.baseSelectable, !current.submitting else { return }
        state?.basePickerVisible = true
        if current.baseOptionsState == .failed {
            state?.baseOptionsState = .loading
            loadBaseOptions(directory: current.sourceDirectory)
        }
    }

    /// Picking the source checkout's own branch is the default (no `branch` sent).
    func selectBase(_ ref: String?) {
        guard let current = state else { return }
        let picked = ref?.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalized = (picked == nil || picked?.isEmpty == true || picked == current.sourceBranch) ? nil : picked
        state?.selectedBaseRef = normalized
        state?.basePickerVisible = false
    }

    func hideBasePicker() {
        state?.basePickerVisible = false
    }

    /// Header Back: the base list returns to the form, the form to the sheet's list.
    func back() {
        guard let current = state, !current.submitting else { return }
        if current.basePickerVisible {
            hideBasePicker()
        } else {
            dismiss()
        }
    }

    func dismiss() {
        generation += 1
        baseTask?.cancel()
        baseTask = nil
        state = nil
    }

    /// Creates the worktree. Nil when the name is invalid, the form closed meanwhile, or the
    /// server refused (then `errorMessage` says why and the values stay). On success the form
    /// keeps showing Creating… until the owner dismisses it.
    func submit() async -> CreatedWorktree? {
        guard let current = state, !current.submitting else { return nil }
        guard WorktreeNameRules.error(for: current.name) == nil else {
            state?.nameErrorVisible = true
            return nil
        }
        state?.submitting = true
        state?.errorMessage = nil
        state?.basePickerVisible = false
        let token = generation
        do {
            let created = try await repository.create(current.request)
            guard token == generation, state != nil else { return nil }
            return created
        } catch {
            guard token == generation, state != nil else { return nil }
            state?.submitting = false
            state?.errorMessage = WorktreeCreateCopy.failed(Self.message(error))
            return nil
        }
    }

    private func loadBaseOptions(directory: String) {
        baseTask?.cancel()
        let token = generation
        let repository = repository
        baseTask = Task { @MainActor [weak self] in
            let result: [String]?
            do {
                result = try await repository.baseBranches(directory: directory)
            } catch {
                result = nil
            }
            guard let self, !Task.isCancelled, token == self.generation, self.state != nil else { return }
            if let result {
                self.state?.baseOptions = result
                self.state?.baseOptionsState = .loaded
            } else {
                self.state?.baseOptionsState = .failed
            }
        }
    }

    private static func message(_ error: Error) -> String {
        if let error = error as? WorktreeCreateError { return error.message }
        return error.localizedDescription
    }
}
