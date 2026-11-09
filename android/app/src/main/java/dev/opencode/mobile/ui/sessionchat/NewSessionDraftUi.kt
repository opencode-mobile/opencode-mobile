package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.filesystem.ServerPathPolicy
import dev.opencode.mobile.domain.home.HomeProject
import dev.opencode.mobile.domain.home.HomeWorktree
import dev.opencode.mobile.domain.home.HomeWorktreeCopy
import dev.opencode.mobile.domain.session.NewSessionDraft
import dev.opencode.mobile.domain.session.NewSessionPlace
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.theme.letterAccentForWorktree
import dev.opencode.mobile.theme.letterCharacterForProject
import dev.opencode.mobile.ui.main.HomeText
import dev.opencode.mobile.ui.text.UiText
import dev.opencode.mobile.ui.worktree.NewWorktreeAvailability
import dev.opencode.mobile.ui.worktree.WorktreeBaseOptions
import dev.opencode.mobile.ui.worktree.WorktreeBaseRow
import dev.opencode.mobile.ui.worktree.WorktreeName

/** The draft's sheet that is open. */
enum class NewSessionDraftSheet {
    Project,
    Worktree,
    BaseBranch,
}

/** What the Run session in card's Worktree row says. */
sealed interface NewSessionWorktreeValue {
    data object Local : NewSessionWorktreeValue

    /** An existing worktree: its branch, else its folder; server content shown as is. */
    data class Worktree(
        val title: String,
    ) : NewSessionWorktreeValue

    data object NewWorktree : NewSessionWorktreeValue

    /** Not a Git repository: the row stays, greyed, and does not open a sheet. */
    data object NotRepository : NewSessionWorktreeValue
}

/** A row of the Project sheet, in Home's project order. */
data class NewSessionProjectRow(
    val key: String,
    val name: String,
    /** The project's main checkout. */
    val path: String,
    val letter: Char,
    val accent: LetterAccent,
    val selected: Boolean,
)

/** A row of the Worktree sheet. */
sealed interface NewSessionWorktreeOption {
    val selected: Boolean

    data class Local(
        val subtitle: UiText,
        override val selected: Boolean,
    ) : NewSessionWorktreeOption

    data class NewWorktree(
        override val selected: Boolean,
    ) : NewSessionWorktreeOption

    data class Worktree(
        val directory: String,
        val branch: String?,
        /** Branch, else folder name; server content. */
        val title: String,
        /** Running sessions when there are any, else the folder name. */
        val subtitle: UiText,
        override val selected: Boolean,
    ) : NewSessionWorktreeOption
}

/** The New session draft as Session Chat shows it: the Run session in card and its sheets. */
data class NewSessionDraftUiState(
    val projectName: String,
    val projectLetter: Char,
    val projectAccent: LetterAccent,
    /** Null while Worktrees is off: no Worktree row, sessions start on the main checkout. */
    val worktree: NewSessionWorktreeValue?,
    /** Starts from row: the new worktree's base, v2 only. */
    val startsFrom: UiText? = null,
    /** Checkout path under the card. */
    val path: UiText? = null,
    val sheet: NewSessionDraftSheet? = null,
    val projectQuery: String = "",
    val projectRows: List<NewSessionProjectRow> = emptyList(),
    val worktreeSheetTitle: UiText = UiText.verbatim(""),
    /** Local, New worktree (when it can be made); the existing worktrees follow in [worktreeOptions]. */
    val placeOptions: List<NewSessionWorktreeOption> = emptyList(),
    val worktreeOptions: List<NewSessionWorktreeOption.Worktree> = emptyList(),
    val baseRows: List<WorktreeBaseRow> = emptyList(),
    val baseStatus: UiText? = null,
    /** The first send is creating the worktree or session. */
    val submitting: Boolean = false,
) {
    /** The tile is highlighted for any place but Local. */
    val worktreeHighlighted: Boolean
        get() = worktree is NewSessionWorktreeValue.Worktree || worktree == NewSessionWorktreeValue.NewWorktree

    val worktreeEnabled: Boolean
        get() = worktree != null && worktree != NewSessionWorktreeValue.NotRepository
}

/** Pure rules behind the draft card and sheets, shared by the screen model and tests. */
object NewSessionDraftPresentation {
    /** The draft's project among Home's, or one made from its folder when Home does not list it. */
    fun project(
        draft: NewSessionDraft,
        projects: List<HomeProject>,
    ): HomeProject =
        projects.firstOrNull { it.key == draft.projectKey }
            ?: projects.firstOrNull { it.contains(draft.projectDirectory) }
            ?: folderProject(draft.projectKey, draft.projectDirectory)

    private fun folderProject(
        key: String,
        directory: String,
    ): HomeProject {
        val canonical = ServerPathPolicy.canonical(directory.trim())
        return HomeProject(
            key = key,
            projectId = null,
            displayName = ServerPathPolicy.name(canonical).ifBlank { canonical },
            memberPaths = listOf(canonical),
            isRepository = false,
            worktrees = listOf(HomeWorktree(directory = canonical, isMain = true)),
        )
    }

    fun newWorktreeAvailability(
        project: HomeProject,
        worktreesEnabled: Boolean,
        serverCanCreate: Boolean,
    ): NewWorktreeAvailability = NewWorktreeAvailability.runIn(project, worktreesEnabled, serverCanCreate)

    /**
     * Where the session really runs: Local while Worktrees is off or the project is not a Git
     * repository, and while a new worktree cannot be made; a worktree Home no longer lists too.
     */
    fun effectivePlace(
        draft: NewSessionDraft,
        project: HomeProject,
        worktreesEnabled: Boolean,
        serverCanCreate: Boolean,
    ): NewSessionPlace {
        if (!worktreesEnabled || !project.hasVcs) return NewSessionPlace.Local
        return when (val place = draft.place) {
            NewSessionPlace.Local -> place
            is NewSessionPlace.Worktree -> if (project.worktree(place.directory) != null) place else NewSessionPlace.Local
            is NewSessionPlace.NewWorktree ->
                if (draft.createdWorktree != null || newWorktreeAvailability(project, worktreesEnabled, serverCanCreate).enabled) {
                    place
                } else {
                    NewSessionPlace.Local
                }
        }
    }

    /** The main checkout's branch; null when unknown or detached. */
    fun mainBranch(project: HomeProject): String? = project.worktrees.firstOrNull { it.isMain }?.branch

    fun ui(
        draft: NewSessionDraft,
        projects: List<HomeProject>,
        worktreesEnabled: Boolean,
        serverCanCreate: Boolean,
        protocol: ServerProtocol,
        runningCounts: Map<String, Int> = emptyMap(),
        sheet: NewSessionDraftSheet? = null,
        projectQuery: String = "",
        baseOptions: WorktreeBaseOptions? = null,
        submitting: Boolean = false,
    ): NewSessionDraftUiState {
        val project = project(draft, projects)
        val place = effectivePlace(draft, project, worktreesEnabled, serverCanCreate)
        val worktree =
            when {
                !worktreesEnabled -> null
                !project.hasVcs -> NewSessionWorktreeValue.NotRepository
                else ->
                    when (place) {
                        NewSessionPlace.Local -> NewSessionWorktreeValue.Local
                        is NewSessionPlace.Worktree ->
                            NewSessionWorktreeValue.Worktree(
                                project.worktree(place.directory)?.let(HomeWorktreeCopy::worktreeTitle)
                                    ?: place.branch
                                    ?: ServerPathPolicy.name(place.directory),
                            )
                        is NewSessionPlace.NewWorktree -> NewSessionWorktreeValue.NewWorktree
                    }
            }
        val mainBranch = mainBranch(project)
        val startsFrom =
            (place as? NewSessionPlace.NewWorktree)?.takeIf { protocol == ServerProtocol.V2 }?.let { newWorktree ->
                (newWorktree.baseBranch ?: mainBranch)?.let(UiText::verbatim)
                    ?: UiText.resource(R.string.worktree_current_checkout)
            }
        val path =
            when (place) {
                NewSessionPlace.Local -> UiText.verbatim(project.mainCheckoutDirectory)
                is NewSessionPlace.Worktree -> UiText.verbatim(place.directory)
                is NewSessionPlace.NewWorktree ->
                    draft.createdWorktree?.let { UiText.verbatim(it.directory) }
                        ?: UiText.resource(R.string.new_session_path_created_on_send)
            }
        return NewSessionDraftUiState(
            projectName = project.displayName,
            projectLetter = letterCharacterForProject(project.displayName),
            projectAccent = letterAccentForWorktree(project.primaryPath),
            worktree = worktree,
            startsFrom = startsFrom,
            path = path,
            sheet = sheet,
            projectQuery = projectQuery,
            projectRows = if (sheet == NewSessionDraftSheet.Project) projectRows(projects, project.key, projectQuery) else emptyList(),
            worktreeSheetTitle = UiText.resource(R.string.new_session_worktree_sheet_title, project.displayName),
            placeOptions =
                if (sheet == NewSessionDraftSheet.Worktree) {
                    placeOptions(project, place, worktreesEnabled, serverCanCreate)
                } else {
                    emptyList()
                },
            worktreeOptions = if (sheet == NewSessionDraftSheet.Worktree) worktreeOptions(project, place, runningCounts) else emptyList(),
            baseRows =
                if (sheet == NewSessionDraftSheet.BaseBranch) {
                    baseRows(mainBranch, (place as? NewSessionPlace.NewWorktree)?.baseBranch, baseOptions)
                } else {
                    emptyList()
                },
            baseStatus =
                when (baseOptions) {
                    WorktreeBaseOptions.Loading -> UiText.resource(R.string.worktree_base_loading)
                    WorktreeBaseOptions.Failed -> UiText.resource(R.string.worktree_base_failed)
                    is WorktreeBaseOptions.Loaded, null -> null
                }.takeIf { sheet == NewSessionDraftSheet.BaseBranch },
            submitting = submitting,
        )
    }

    /** Home's projects in Home order whose name contains [query] (any case). */
    fun projectRows(
        projects: List<HomeProject>,
        selectedKey: String?,
        query: String,
    ): List<NewSessionProjectRow> {
        val needle = query.trim()
        return projects
            .filter { needle.isEmpty() || it.displayName.contains(needle, ignoreCase = true) }
            .map { project ->
                NewSessionProjectRow(
                    key = project.key,
                    name = project.displayName,
                    path = project.mainCheckoutDirectory,
                    letter = letterCharacterForProject(project.displayName),
                    accent = letterAccentForWorktree(project.primaryPath),
                    selected = project.key == selectedKey,
                )
            }
    }

    /** Local, then New worktree when one can be made. */
    fun placeOptions(
        project: HomeProject,
        place: NewSessionPlace,
        worktreesEnabled: Boolean,
        serverCanCreate: Boolean,
    ): List<NewSessionWorktreeOption> =
        buildList {
            val branch = mainBranch(project)
            add(
                NewSessionWorktreeOption.Local(
                    subtitle =
                        branch?.let { UiText.resource(R.string.new_session_local_current_checkout, it) }
                            ?: UiText.resource(R.string.home_main_checkout),
                    selected = place == NewSessionPlace.Local,
                ),
            )
            if (newWorktreeAvailability(project, worktreesEnabled, serverCanCreate).enabled) {
                add(NewSessionWorktreeOption.NewWorktree(selected = place is NewSessionPlace.NewWorktree))
            }
        }

    /** The project's other checkouts by branch, with their running sessions; no total counts (#981). */
    fun worktreeOptions(
        project: HomeProject,
        place: NewSessionPlace,
        runningCounts: Map<String, Int> = emptyMap(),
    ): List<NewSessionWorktreeOption.Worktree> {
        val selectedDirectory = (place as? NewSessionPlace.Worktree)?.directory
        return project.worktrees
            .filter { !it.isMain }
            .map { worktree ->
                val running = runningCounts[worktree.directory] ?: 0
                NewSessionWorktreeOption.Worktree(
                    directory = worktree.directory,
                    branch = worktree.branch,
                    title = HomeWorktreeCopy.worktreeTitle(worktree),
                    subtitle = if (running > 0) HomeText.runningCount(running) else UiText.verbatim(worktree.folderName),
                    selected = worktree.directory == selectedDirectory,
                )
            }
    }

    /** The default (the main checkout's branch) first, then the server's other branches. */
    fun baseRows(
        mainBranch: String?,
        selected: String?,
        options: WorktreeBaseOptions?,
    ): List<WorktreeBaseRow> {
        val default = WorktreeBaseRow(null, WorktreeName.defaultBase(mainBranch), checked = selected == null)
        val refs = (options as? WorktreeBaseOptions.Loaded)?.refs.orEmpty()
        return listOf(default) +
            refs.filter { it != mainBranch }.map { ref -> WorktreeBaseRow(ref, UiText.verbatim(ref), checked = selected == ref) }
    }
}
