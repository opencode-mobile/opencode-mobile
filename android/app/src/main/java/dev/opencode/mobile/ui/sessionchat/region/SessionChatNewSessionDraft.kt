package dev.opencode.mobile.ui.sessionchat.region

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.composer.ComposerDraft
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.filesystem.ServerPathPolicy
import dev.opencode.mobile.domain.home.HomeProject
import dev.opencode.mobile.domain.home.HomeProjectsSnapshot
import dev.opencode.mobile.domain.home.HomeWorktreesFeed
import dev.opencode.mobile.domain.home.HomeWorktreesSnapshot
import dev.opencode.mobile.domain.message.AttachmentLimits
import dev.opencode.mobile.domain.message.AttachmentUpload
import dev.opencode.mobile.domain.model.NewSessionModelSelectionResolver
import dev.opencode.mobile.domain.model.SessionModelSelection
import dev.opencode.mobile.domain.preferences.HomeDisplayPreferences
import dev.opencode.mobile.domain.session.NewSessionCreatedSession
import dev.opencode.mobile.domain.session.NewSessionDraft
import dev.opencode.mobile.domain.session.NewSessionDraftStart
import dev.opencode.mobile.domain.session.NewSessionDraftStore
import dev.opencode.mobile.domain.session.NewSessionPlace
import dev.opencode.mobile.domain.session.SessionError
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.text.UiText
import dev.opencode.mobile.domain.worktree.CreateWorktreeRequest
import dev.opencode.mobile.domain.worktree.WorktreeCreateError
import dev.opencode.mobile.domain.worktree.WorktreeRepository
import dev.opencode.mobile.ui.sessionchat.NewSessionDraftPresentation
import dev.opencode.mobile.ui.sessionchat.NewSessionDraftSheet
import dev.opencode.mobile.ui.sessionchat.NewSessionDraftUiState
import dev.opencode.mobile.ui.sessionchat.SessionChatComposerState
import dev.opencode.mobile.ui.worktree.WorktreeBaseOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SessionChatNewSessionDraftRegionState(
    /** The draft Session Chat shows; null once the session exists. */
    val draft: NewSessionDraft? = null,
    /** The profile whose draft this is; null once the draft is released. */
    val profileId: String? = null,
    val sheet: NewSessionDraftSheet? = null,
    val projectQuery: String = "",
    val baseBranches: WorktreeBaseOptions? = null,
    /** The first send is creating the worktree or session, until its prompt shows (or fails). */
    val isCreating: Boolean = false,
    val sendError: UiText? = null,
)

/**
 * Session Chat region for the New session draft (#1004) before its first send: claiming the
 * profile's draft, mirroring the composer into the per-profile store, the Run session in card's
 * project / place / Starts from choices and their sheets, the base branches, leaving or
 * discarding the draft, and the create step of the first send (new worktree if chosen, then the
 * session, then its model). The facade binds the other regions, promotes, and sends.
 *
 * Actions change state silently and return whether the facade should republish; only the base
 * branch load calls [onChange] itself.
 */
class SessionChatNewSessionDraft(
    /** The screen that edits the draft; the store accepts saves from its owner only. */
    private val owner: Any,
    private val newSessionDraftStore: NewSessionDraftStore,
    private val worktreeRepository: WorktreeRepository,
    private val sessionRepository: SessionRepository,
    private val newSessionModelSelectionResolver: NewSessionModelSelectionResolver,
    private val homeProjectsSnapshot: HomeProjectsSnapshot,
    private val homeWorktreesFeed: HomeWorktreesFeed,
    private val currentProfile: () -> ConnectionProfile?,
    private val protocolHint: () -> ServerProtocol,
    /** What the composer holds now. */
    private val composerState: () -> SessionChatComposerState,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val onChange: () -> Unit,
) {
    var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val _state = MutableStateFlow(SessionChatNewSessionDraftRegionState())
    val state: StateFlow<SessionChatNewSessionDraftRegionState> = _state.asStateFlow()

    private var baseJob: Job? = null

    /** A path Add project… saved that Home has not listed yet; the draft moves into its Home project once it does. */
    private var pendingAddedDirectory: String? = null
    private var submitJob: Job? = null

    /** Binding a draft rewrites the composer; its intermediate states are not the draft's. */
    private var syncPaused = false

    /** Session Chat shows a draft, not a created session. */
    val isActive: Boolean get() = _state.value.draft != null

    val isCreating: Boolean get() = _state.value.isCreating

    /** The draft's Worktree sheet shows running sessions per worktree. */
    val runningSessions: StateFlow<HomeWorktreesSnapshot> get() = homeWorktreesFeed.snapshot

    private val draft: NewSessionDraft? get() = _state.value.draft

    /** Claims the profile's draft, or a fresh one at [worktree], for this screen; not adopted yet. */
    fun claim(worktree: String): NewSessionDraft {
        val profileId = currentProfileId()
        val draft =
            newSessionDraftStore.claim(
                profileId = profileId,
                owner = owner,
                start = NewSessionDraftStart.forDirectory(homeProjectsSnapshot.projects.value, worktree),
            )
        _state.update { it.copy(profileId = profileId) }
        return draft
    }

    /** A new draft bind: no sheet, query, create in progress, or error carries over. */
    fun resetForBind() {
        pendingAddedDirectory = null
        _state.update { it.copy(sheet = null, projectQuery = "", isCreating = false, sendError = null) }
    }

    /** Session Chat now shows [draft]. */
    fun adopt(draft: NewSessionDraft) {
        _state.update { it.copy(draft = draft) }
    }

    /** Runs [block] (binding regions to the draft's checkout) without copying composer states. */
    fun withSyncPaused(block: () -> Unit) {
        syncPaused = true
        try {
            block()
        } finally {
            syncPaused = false
        }
    }

    /** Copies what the composer holds (text, attachments, model) into the draft and its store. */
    fun syncFromComposer() {
        if (syncPaused) return
        val draft = draft ?: return
        val composerState = composerState()
        val selection =
            composerState.selectedModel?.takeIf { composerState.isModelSelectionReady }?.let { selected ->
                val picked =
                    SessionModelSelection(
                        providerId = selected.option.providerId,
                        modelId = selected.option.modelId,
                        variant = composerState.selectedVariant,
                        agentId = composerState.selectedMode,
                    )
                // The same pick keeps when it was made.
                val previous = draft.modelSelection
                val unchanged = previous != null && previous.copy(savedAtEpochMs = null) == picked
                if (unchanged) previous else picked.copy(savedAtEpochMs = nowMs())
            } ?: draft.modelSelection
        val next =
            draft.copy(
                composer =
                    ComposerDraft(
                        text = composerState.text,
                        agentMentions = composerState.agentMentions,
                        fileMentions = composerState.fileMentions,
                        skillMentions = composerState.skillMentions,
                    ),
                // An upload still streaming belongs to this screen; the draft keeps finished ones.
                attachments = composerState.attachments.filter { it.upload !is AttachmentUpload.Uploading },
                modelSelection = selection,
            )
        if (next == draft) return
        update(next)
    }

    /** The Run session in card, or null when Session Chat shows a created session. */
    fun uiState(): NewSessionDraftUiState? {
        val state = _state.value
        val draft = state.draft ?: return null
        return NewSessionDraftPresentation.ui(
            draft = draft,
            projects = homeProjectsSnapshot.projects.value,
            worktreesEnabled = HomeDisplayPreferences.showsWorktrees,
            serverCanCreate = worktreeRepository.canCreate.value,
            protocol = protocolHint(),
            runningCounts = runningCountsByDirectory(),
            sheet = state.sheet,
            projectQuery = state.projectQuery,
            baseOptions = state.baseBranches,
            submitting = state.isCreating,
        )
    }

    /** Card → Project row. */
    fun openProjectSheet(): Boolean {
        if (draft == null || isCreating) return false
        _state.update { it.copy(projectQuery = "", sheet = NewSessionDraftSheet.Project) }
        return true
    }

    /** Card → Worktree row; a project that is not a Git repository has none to pick. */
    fun openWorktreeSheet(): Boolean {
        val draft = uiState() ?: return false
        if (!draft.worktreeEnabled || isCreating) return false
        _state.update { it.copy(sheet = NewSessionDraftSheet.Worktree) }
        return true
    }

    /** Card → Starts from row (a new worktree on v2); loads the branches. */
    fun openBaseBranchSheet(): Boolean {
        val draft = uiState() ?: return false
        if (draft.startsFrom == null || isCreating) return false
        _state.update { it.copy(sheet = NewSessionDraftSheet.BaseBranch) }
        loadBaseBranches()
        return true
    }

    fun dismissSheet(): Boolean {
        if (_state.value.sheet == null) return false
        _state.update { it.copy(sheet = null) }
        baseJob?.cancel()
        return true
    }

    fun updateProjectQuery(query: String) {
        _state.update { it.copy(projectQuery = query) }
    }

    /**
     * Project sheet → a project: it runs on that project's main checkout. [onMoved] binds the
     * moved draft's checkout. Returns whether anything changed.
     */
    fun selectProject(
        projectKey: String,
        onMoved: (NewSessionDraft) -> Unit,
    ): Boolean {
        val draft = draft ?: return false
        val project = homeProjectsSnapshot.projects.value.firstOrNull { it.key == projectKey } ?: return false
        pendingAddedDirectory = null
        _state.update { it.copy(sheet = null) }
        if (project.key != draft.projectKey) {
            val next = draft.movedTo(project.key, project.mainCheckoutDirectory, NewSessionPlace.Local)
            update(next)
            onMoved(next)
        }
        return true
    }

    /** Worktree sheet → Local, New worktree, or an existing worktree. See [selectProject]. */
    fun selectPlace(
        place: NewSessionPlace,
        onMoved: (NewSessionDraft) -> Unit,
    ): Boolean {
        val draft = draft ?: return false
        pendingAddedDirectory = null
        _state.update { it.copy(sheet = null) }
        // Picking New worktree again keeps the base already chosen.
        val next = if (place is NewSessionPlace.NewWorktree) draft.place as? NewSessionPlace.NewWorktree ?: place else place
        if (next != draft.place) {
            val moved = draft.movedTo(draft.projectKey, draft.projectDirectory, next)
            update(moved)
            onMoved(moved)
        }
        return true
    }

    /** Starts from sheet → a branch; null (or the main checkout's own branch) is the default. */
    fun selectBaseBranch(ref: String?): Boolean {
        val draft = draft ?: return false
        val place = draft.place as? NewSessionPlace.NewWorktree ?: return false
        val project = NewSessionDraftPresentation.project(draft, homeProjectsSnapshot.projects.value)
        val mainBranch = NewSessionDraftPresentation.mainBranch(project)
        val picked = ref?.takeIf { it.isNotBlank() && it != mainBranch }
        _state.update { it.copy(sheet = null) }
        baseJob?.cancel()
        if (picked != place.baseBranch) {
            update(draft.movedTo(draft.projectKey, draft.projectDirectory, NewSessionPlace.NewWorktree(picked)))
        }
        return true
    }

    /** Project sheet → Add project…: the Project sheet gives way to the Add project sheet. */
    fun beginAddProject(): Boolean {
        if (draft == null || isCreating) return false
        _state.update { it.copy(sheet = null) }
        return true
    }

    /**
     * Add project… saved [worktree] (#1021): the draft runs there. Until Home lists the path, the
     * draft names it as a folder of its own; [onProjectsChanged] moves it into its Home project.
     */
    fun adoptAddedProject(
        worktree: String,
        onMoved: (NewSessionDraft) -> Unit,
    ): Boolean {
        if (draft == null) return false
        val canonical = ServerPathPolicy.canonical(worktree.trim())
        pendingAddedDirectory = canonical.takeIf { homeProjectsSnapshot.projectContaining(it) == null }
        moveTo(NewSessionDraftStart.forDirectory(homeProjectsSnapshot.projects.value, canonical), onMoved)
        return true
    }

    /** Home published its projects: a draft waiting on an added path moves into that path's project. */
    fun onProjectsChanged(onMoved: (NewSessionDraft) -> Unit): Boolean {
        val pending = pendingAddedDirectory ?: return false
        if (homeProjectsSnapshot.projectContaining(pending) == null) return false
        pendingAddedDirectory = null
        return moveTo(NewSessionDraftStart.forDirectory(homeProjectsSnapshot.projects.value, pending), onMoved)
    }

    private fun moveTo(
        start: NewSessionDraftStart,
        onMoved: (NewSessionDraft) -> Unit,
    ): Boolean {
        val draft = draft ?: return false
        if (draft.projectKey == start.projectKey && draft.projectDirectory == start.projectDirectory && draft.place == start.place) {
            return false
        }
        val next = draft.movedTo(start.projectKey, start.projectDirectory, start.place)
        update(next)
        onMoved(next)
        return true
    }

    /** ⋯ → Discard draft: the draft is gone; the screen goes back. */
    fun discard(): Boolean {
        val draft = draft ?: return false
        submitJob?.cancel()
        pendingAddedDirectory = null
        _state.value.profileId?.let { newSessionDraftStore.release(it, owner) }
        _state.update { it.copy(profileId = null, sheet = null) }
        draft.attachments.forEach { AttachmentLimits.discard(it.localPath) }
        return true
    }

    /** The draft screen goes away: a draft with no text and no attachments is not kept. */
    fun leave() {
        val profileId = _state.value.profileId ?: return
        syncFromComposer()
        newSessionDraftStore.discardIfEmpty(profileId, owner)
    }

    /** The first send created the session: the draft is done. */
    fun release() {
        pendingAddedDirectory = null
        _state.value.profileId?.let { newSessionDraftStore.release(it, owner) }
        _state.update { it.copy(profileId = null, draft = null, sheet = null) }
        baseJob?.cancel()
        _state.update { it.copy(baseBranches = null) }
    }

    /**
     * Ctrl+N or `/new` from a session in [directory]: the profile's draft (it wins), else a
     * fresh one in that place. Returns the draft's checkout, which the route names.
     */
    fun prepare(directory: String): String {
        val start = NewSessionDraftStart.forDirectory(homeProjectsSnapshot.projects.value, directory)
        return newSessionDraftStore.open(currentProfileId(), start).directory
    }

    /** The first send starts creating. */
    fun beginCreate() {
        _state.update { it.copy(isCreating = true, sendError = null, sheet = null) }
    }

    /** The facade's first-send job, so [discard] can cancel it. */
    fun trackSubmit(job: Job) {
        submitJob = job
    }

    /** The create step failed: the draft stays with the error and Retry. */
    fun failCreate(error: Throwable) {
        _state.update {
            it.copy(
                isCreating = false,
                sendError =
                    (error as? NewSessionDraftWorktreeFailure)?.text
                        ?: UiText.verbatim(createSessionErrorMessage(error)),
            )
        }
    }

    /** The send's own sending state took over, or the send did not start. */
    fun endCreating() {
        _state.update { it.copy(isCreating = false) }
    }

    fun clearSendError() {
        _state.update { it.copy(sendError = null) }
    }

    /**
     * The create step of the first send: the new worktree (if chosen), then the session in its
     * checkout, then the session's model. What was already created is reused on a retry.
     */
    suspend fun create(): NewSessionCreatedSession {
        val draft = draft ?: throw CancellationException("draft gone")
        val project = NewSessionDraftPresentation.project(draft, homeProjectsSnapshot.projects.value)
        val place =
            NewSessionDraftPresentation.effectivePlace(
                draft = draft,
                project = project,
                worktreesEnabled = HomeDisplayPreferences.showsWorktrees,
                serverCanCreate = worktreeRepository.canCreate.value,
            )
        val directory =
            when (place) {
                NewSessionPlace.Local -> project.mainCheckoutDirectory
                is NewSessionPlace.Worktree -> place.directory
                is NewSessionPlace.NewWorktree -> draft.createdWorktree?.directory ?: createDraftWorktree(project, place)
            }
        this.draft?.createdSession?.takeIf { it.directory == directory }?.let { return it }
        val session = withContext(ioDispatcher) { sessionRepository.createSession(directory = directory) }
        val created = NewSessionCreatedSession(id = session.id, title = session.title, directory = directory)
        this.draft?.let { update(it.copy(createdSession = created)) }
        // Seed the session's model the way any new session gets one: the draft's pick, else the preference.
        val profile = currentProfile()
        if (profile != null) {
            withContext(ioDispatcher) {
                val selection = this@SessionChatNewSessionDraft.draft?.modelSelection ?: newSessionModelSelectionResolver.resolve(profile, directory)
                if (selection != null) newSessionModelSelectionResolver.seedSession(profile, directory, session.id, selection)
            }
        }
        return created
    }

    private fun currentProfileId(): String = currentProfile()?.id.orEmpty()

    private fun update(draft: NewSessionDraft) {
        _state.update { it.copy(draft = draft) }
        val profileId = _state.value.profileId ?: return
        newSessionDraftStore.save(profileId, owner, draft)
    }

    private fun runningCountsByDirectory(): Map<String, Int> =
        homeWorktreesFeed.snapshot.value.sessions
            .filter { it.isRunning }
            .groupingBy { ServerPathPolicy.canonical(it.directory) }
            .eachCount()

    private fun loadBaseBranches() {
        val draft = draft ?: return
        val directory = NewSessionDraftPresentation.project(draft, homeProjectsSnapshot.projects.value).mainCheckoutDirectory
        baseJob?.cancel()
        _state.update { it.copy(baseBranches = WorktreeBaseOptions.Loading) }
        baseJob =
            scope.launch {
                val options =
                    try {
                        WorktreeBaseOptions.Loaded(withContext(ioDispatcher) { worktreeRepository.baseBranches(directory) })
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        WorktreeBaseOptions.Failed
                    }
                _state.update { it.copy(baseBranches = options) }
                onChange()
            }
    }

    /** V2 creates from the main checkout at the chosen base; v1 from the current checkout. The server names it. */
    private suspend fun createDraftWorktree(
        project: HomeProject,
        place: NewSessionPlace.NewWorktree,
    ): String {
        val v2 = protocolHint() == ServerProtocol.V2
        val created =
            try {
                withContext(ioDispatcher) {
                    worktreeRepository.create(
                        CreateWorktreeRequest(
                            projectId = project.projectId,
                            sourceDirectory = project.mainCheckoutDirectory,
                            name = "",
                            baseRef = place.baseBranch.takeIf { v2 },
                        ),
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                val message =
                    ((error as? WorktreeCreateError)?.message ?: error.message?.takeIf { it.isNotBlank() })
                        ?.let(UiText::verbatim)
                        ?: UiText.resource(R.string.worktree_unknown_error)
                throw NewSessionDraftWorktreeFailure(UiText.resource(R.string.worktree_create_failed, message))
            }
        homeProjectsSnapshot.requestWorktreeRefresh(project.mainCheckoutDirectory)
        draft?.let { update(it.copy(createdWorktree = created)) }
        return created.directory
    }

    private fun createSessionErrorMessage(error: Throwable): String =
        when (error) {
            is SessionError.Http -> error.message ?: "Unable to create session"
            is SessionError.Transport -> "Unable to reach the server"
            is SessionError.Decoding -> "Unable to read server response"
            else -> error.message ?: "Unable to create session"
        }
}

/** Creating the draft's new worktree failed; [text] says so with the server's reason. */
private class NewSessionDraftWorktreeFailure(
    val text: UiText,
) : Exception()
