import Foundation

/// Project chrome under the Session Chat title: the checkout name for a worktree checkout,
/// otherwise the smart project label with its letter.
struct SessionChatProjectIdentity: Equatable {
    var label: String?
    var letter: Character?
    var accent: LetterAccent?
    var isWorktreeCheckout = false

    static let none = SessionChatProjectIdentity()

    /// Nothing known about the added paths yet: the folder name with its letter.
    static func folder(_ worktree: String) -> SessionChatProjectIdentity {
        guard !worktree.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return .none }
        let displayName = ProjectDomainAdapter.displayName(from: worktree)
        return SessionChatProjectIdentity(
            label: displayName,
            letter: letterCharacter(for: displayName),
            accent: letterAccent(for: worktree)
        )
    }

    /// A worktree checkout (the Home badge predicate for any added path) shows its checkout
    /// name; anything else shows the smart label among the added paths.
    static func resolve(
        worktree: String,
        addedPaths: [String],
        inventoryFor: (String) -> WorktreeInventory
    ) -> SessionChatProjectIdentity {
        guard !worktree.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return .none }
        if let checkout = WorktreeMark.checkoutNameIfMarked(
            boundDirectory: worktree,
            addedPaths: addedPaths,
            inventoryFor: inventoryFor
        ) {
            return SessionChatProjectIdentity(label: checkout, isWorktreeCheckout: true)
        }
        var identity = folder(worktree)
        identity.label = smartProjectLabel(focusWorktree: worktree, peerWorktrees: addedPaths)
        return identity
    }
}
