package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.project.ProjectDomainAdapter
import dev.opencode.mobile.domain.project.smartProjectLabel
import dev.opencode.mobile.domain.worktree.WorktreeInventory
import dev.opencode.mobile.domain.worktree.WorktreeMark
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.theme.letterAccentForWorktree
import dev.opencode.mobile.theme.letterCharacterForProject

/**
 * Project chrome under the Session Chat title: the checkout name for a worktree checkout,
 * otherwise the smart project label with its letter.
 */
data class SessionChatProjectIdentity(
    val label: String? = null,
    val letter: Char? = null,
    val accent: LetterAccent? = null,
    val isWorktreeCheckout: Boolean = false,
) {
    companion object {
        val None = SessionChatProjectIdentity()

        /** Nothing known about the added paths yet: the folder name with its letter. */
        fun folder(worktree: String): SessionChatProjectIdentity {
            if (worktree.isBlank()) return None
            val displayName = ProjectDomainAdapter.displayNameFromWorktree(worktree)
            return SessionChatProjectIdentity(
                label = displayName,
                letter = letterCharacterForProject(displayName),
                accent = letterAccentForWorktree(worktree),
            )
        }

        /**
         * A worktree checkout (the Home badge predicate for any added path) shows its checkout
         * name; anything else shows the smart label among the added paths.
         */
        fun resolve(
            worktree: String,
            addedPaths: List<String>,
            inventoryFor: (String) -> WorktreeInventory,
        ): SessionChatProjectIdentity {
            if (worktree.isBlank()) return None
            WorktreeMark.checkoutNameIfMarked(worktree, addedPaths, inventoryFor)?.let { checkout ->
                return SessionChatProjectIdentity(label = checkout, isWorktreeCheckout = true)
            }
            return folder(worktree).copy(label = smartProjectLabel(worktree, addedPaths))
        }
    }
}
