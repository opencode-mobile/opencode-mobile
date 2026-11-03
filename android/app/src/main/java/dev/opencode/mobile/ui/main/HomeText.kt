package dev.opencode.mobile.ui.main

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.home.HomeWorktree
import dev.opencode.mobile.ui.text.UiText

/**
 * Home app text (scope picker, dock, Run in, snackbars) as [UiText]. Project, branch and folder
 * names are server content and stay verbatim.
 */
internal object HomeText {
    /** "main checkout" for the main checkout, else its folder name. */
    fun worktreeSubtitle(worktree: HomeWorktree): UiText =
        if (worktree.isMain) UiText.resource(R.string.home_main_checkout) else UiText.verbatim(worktree.folderName)

    fun projectCount(count: Int): UiText = UiText.plural(R.plurals.home_project_count, count)

    fun allWorktrees(count: Int): UiText = UiText.plural(R.plurals.home_all_worktrees, count)

    fun runningCount(count: Int): UiText = UiText.plural(R.plurals.home_running_count, count)

    fun showAll(count: Int): UiText = UiText.plural(R.plurals.home_show_all, count)

    val showFewer: UiText get() = UiText.resource(R.string.home_show_fewer)

    val reorderNote: UiText get() = UiText.resource(R.string.home_reorder_note)

    fun joinedProject(displayName: String): UiText = UiText.resource(R.string.home_snackbar_joined_project, displayName)

    fun removedProject(displayName: String): UiText = UiText.resource(R.string.home_snackbar_removed, displayName)

    fun alreadyOnHome(displayName: String): UiText = UiText.resource(R.string.home_snackbar_already_on_home, displayName)
}
