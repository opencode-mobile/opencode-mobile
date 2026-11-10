package dev.opencode.mobile.ui.main.region

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.filesystem.DirectoryNotFoundException
import dev.opencode.mobile.domain.filesystem.FilesystemRepository
import dev.opencode.mobile.domain.filesystem.ServerPathPolicy
import dev.opencode.mobile.domain.home.ResolvedHomePath
import dev.opencode.mobile.domain.project.ProjectRepository
import dev.opencode.mobile.domain.session.NewSessionDraftStore
import dev.opencode.mobile.domain.text.UiText
import dev.opencode.mobile.ui.main.AddProjectListing
import dev.opencode.mobile.ui.main.AddProjectSheetModel
import dev.opencode.mobile.ui.main.AddProjectSheetSession
import dev.opencode.mobile.ui.main.AddProjectSheetUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The Add project sheet session, while one is open. */
data class HomeAddProjectState(
    val session: AddProjectSheetSession? = null,
)

/** What [HomeAddProject.add] did with the chosen path. */
sealed interface HomeAddProjectResult {
    /** The path is already on Home; nothing was validated or saved. */
    data object AlreadyAdded : HomeAddProjectResult

    /** The path resolved and was saved to the Connection Profile. */
    data class Added(
        val resolution: ResolvedHomePath,
    ) : HomeAddProjectResult

    /** Validation failed; the sheet shows the error itself. */
    data object Failed : HomeAddProjectResult
}

/**
 * Add project sheet and folder browser: opening it (server home, added paths, server project
 * suggestions, `/Volumes`), field typing with a debounced listing, folder navigation, row
 * summaries, and validating and saving the chosen path. Reloading Home, scope requests,
 * snackbars, opening the draft and analytics stay with the facade.
 *
 * Every sheet change goes to [onSheetChanged], which patches it into Home's content the way the
 * facade always did (H-AH-2): a content rebuild hides it until the next sheet change.
 */
internal class HomeAddProject(
    private val filesystemRepository: FilesystemRepository,
    private val projectRepository: ProjectRepository,
    private val connectionProfileStore: ConnectionProfileStore,
    private val newSessionDraftStore: NewSessionDraftStore,
    private val scope: CoroutineScope,
    private val serverBaseUrl: () -> String,
    private val currentProfileId: () -> String,
    /** Project ids already on Home for the given added paths. */
    private val addedProjectIds: (List<String>) -> Set<String>,
    private val onSheetChanged: (AddProjectSheetUi) -> Unit,
) {
    var state = HomeAddProjectState()
        private set

    /** The open sheet session, or null when the sheet is closed. */
    val session: AddProjectSheetSession?
        get() = state.session

    // Results from an earlier session are dropped by generation.
    private var generation = 0
    private var debounceJob: Job? = null

    // Row summaries (folder count, git) for visible browser rows: FIFO, a few at a time,
    // each path tried once per sheet session.
    private val summaryQueue = ArrayDeque<String>()
    private val summariesInFlight = mutableSetOf<String>()
    private val summaryFailures = mutableSetOf<String>()

    fun open() {
        end()
        val generation = generation
        state = state.copy(session = AddProjectSheetSession())
        publish()
        scope.launch {
            val paths =
                try {
                    filesystemRepository.getPaths()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    DiagnosticLog.warning(DiagnosticLogArea.Connection, "add project home lookup failed", error)
                    if (generation != this@HomeAddProject.generation) return@launch
                    updateSession {
                        it.copy(isOpening = false, openError = AddProjectSheetUi.LIST_ERROR_MESSAGE)
                    }
                    return@launch
                }
            val added = connectionProfileStore.addedWorktrees(serverBaseUrl())
            if (generation != this@HomeAddProject.generation) return@launch
            val home = ServerPathPolicy.canonical(paths.home)
            updateSession {
                it.copy(
                    home = home,
                    isOpening = false,
                    openError = null,
                    fieldText = ServerPathPolicy.fieldText(home),
                    addedWorktrees = added,
                    addedProjectIds = addedProjectIds(added),
                )
            }
            loadListing(home)
            loadServerProjects(generation)
            if (ServerPathPolicy.root(home) == "/") loadVolumes(generation)
        }
    }

    /** Ends the session; results still in flight are dropped. */
    fun end() {
        generation++
        debounceJob?.cancel()
        debounceJob = null
        state = state.copy(session = null)
        summaryQueue.clear()
        summariesInFlight.clear()
        summaryFailures.clear()
    }

    /** A draft that asked for a project gets none when the sheet closes without one. */
    fun cancelDraftRequest() {
        newSessionDraftStore.cancelAddProject(currentProfileId())
    }

    /** Whether a New session draft asked for this project; consumes that request. */
    fun consumeDraftRequest(): Boolean = newSessionDraftStore.consumeAddProjectRequest(currentProfileId())

    /** Typing in the path field: filters locally, and lists a new folder after a pause. */
    fun updateField(text: String) {
        val session = session ?: return
        if (session.fieldText == text) return
        updateSession { it.copy(fieldText = text, fieldError = null) }
        scheduleListing()
    }

    /** Chip, row, and Up taps list the folder right away. */
    fun setFolder(path: String) {
        debounceJob?.cancel()
        updateSession {
            it.copy(fieldText = ServerPathPolicy.fieldText(path), fieldError = null)
        }
        session?.parts?.directory?.let(::loadListing)
    }

    /** A browser row came on screen: queue its folder count and git check. */
    fun rowAppeared(path: String) {
        val session = session ?: return
        val key = ServerPathPolicy.canonical(path)
        if (session.listings.containsKey(key) ||
            key in summariesInFlight ||
            key in summaryQueue ||
            key in summaryFailures
        ) {
            return
        }
        summaryQueue.addLast(key)
        startSummaries()
    }

    /** A browser row left the screen: drop its summary if it has not started. */
    fun rowDisappeared(path: String) {
        summaryQueue.remove(ServerPathPolicy.canonical(path))
    }

    /** Replaces the suggestion sheet with the full-screen folder browser. */
    fun browse() {
        updateSession { it.copy(browsing = true) }
    }

    /**
     * Back from the browser. Returns to the suggestion sheet at the server home, so
     * "On this server" is not lost because the field was left in a subfolder.
     */
    fun closeBrowser() {
        val session = session ?: return
        val home = session.home
        if (home.isEmpty()) {
            updateSession { it.copy(browsing = false) }
            return
        }
        debounceJob?.cancel()
        updateSession {
            it.copy(
                browsing = false,
                fieldText = ServerPathPolicy.fieldText(home),
                fieldError = null,
            )
        }
        loadListing(home)
    }

    fun retryListing() {
        val session = session ?: return
        if (session.openError != null) {
            open()
            return
        }
        val directory = session.parts.directory ?: return
        updateSession { it.copy(listings = it.listings - directory) }
        loadListing(directory)
    }

    /**
     * Validates [worktree] and saves it to the Connection Profile. [onResolved] runs after the
     * server resolved its project and before it is saved.
     */
    suspend fun add(
        worktree: String,
        onResolved: (ResolvedHomePath) -> Unit,
    ): HomeAddProjectResult {
        val added = connectionProfileStore.addedWorktrees(serverBaseUrl())
        if (worktree in added) return HomeAddProjectResult.AlreadyAdded
        updateSession { it.copy(isValidating = true, fieldError = null) }
        val result = runCatching { projectRepository.getCurrentProject(directory = worktree) }
        result
            .onSuccess { project ->
                val resolution = ResolvedHomePath.from(project)
                onResolved(resolution)
                // Persist the selected directory (web open-project behavior), not the
                // /project/current worktree — non-git dirs resolve to global "/".
                connectionProfileStore.addWorktree(serverBaseUrl(), worktree)
                return HomeAddProjectResult.Added(resolution)
            }.onFailure { error ->
                updateSession {
                    it.copy(
                        isValidating = false,
                        fieldError =
                            error.message?.let(UiText::verbatim)
                                ?: UiText.resource(R.string.add_project_error_add_failed),
                    )
                }
            }
        return HomeAddProjectResult.Failed
    }

    private fun scheduleListing() {
        debounceJob?.cancel()
        val directory = session?.parts?.directory ?: return
        if (session?.listings?.containsKey(directory) == true) return
        debounceJob =
            scope.launch {
                delay(AddProjectSheetModel.LISTING_DEBOUNCE_MS)
                loadListing(directory)
            }
    }

    /** Lists [directory] once per sheet session; a failure stays until Retry. */
    private fun loadListing(directory: String) {
        val session = session ?: return
        if (session.listings.containsKey(directory)) return
        val generation = generation
        updateSession { it.copy(listings = it.listings + (directory to AddProjectListing.Loading)) }
        scope.launch {
            val listing =
                try {
                    AddProjectListing.Loaded(filesystemRepository.listDirectory(directory))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: DirectoryNotFoundException) {
                    AddProjectListing.NotFound
                } catch (error: Exception) {
                    DiagnosticLog.warning(DiagnosticLogArea.Connection, "add project listing failed", error)
                    AddProjectListing.Failed(AddProjectSheetUi.LIST_ERROR_MESSAGE)
                }
            if (generation != this@HomeAddProject.generation) return@launch
            updateSession { session ->
                val updated = session.copy(listings = session.listings + (directory to listing))
                if (listing is AddProjectListing.Loaded) updated.withListedRoot(directory) else updated
            }
        }
    }

    /** Starts queued row summaries, oldest first, up to [AddProjectSheetModel.FOLDER_SUMMARY_CONCURRENCY]. */
    private fun startSummaries() {
        while (summariesInFlight.size < AddProjectSheetModel.FOLDER_SUMMARY_CONCURRENCY) {
            val path = summaryQueue.removeFirstOrNull() ?: return
            // The browser may have listed it while it waited.
            if (session?.listings?.containsKey(path) != false) continue
            loadSummary(path)
        }
    }

    /**
     * Lists one row's folder into the browser's listing cache, so opening it is instant. A
     * failure only hides the row's count: no error, no retry this session.
     */
    private fun loadSummary(path: String) {
        val generation = generation
        summariesInFlight += path
        scope.launch {
            val listing =
                try {
                    filesystemRepository.listDirectory(path)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    null
                }
            if (generation != this@HomeAddProject.generation) return@launch
            summariesInFlight -= path
            if (listing == null) {
                summaryFailures += path
            } else {
                updateSession { session ->
                    when (session.listings[path]) {
                        null, AddProjectListing.Loading ->
                            session
                                .copy(listings = session.listings + (path to AddProjectListing.Loaded(listing)))
                                .withListedRoot(path)
                        else -> session
                    }
                }
            }
            startSummaries()
        }
    }

    private fun loadServerProjects(generation: Int) {
        scope.launch {
            val projects =
                try {
                    projectRepository.listProjects()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    // The section stays hidden.
                    if (generation != this@HomeAddProject.generation) return@launch
                    updateSession { it.copy(suggestionsResolved = true) }
                    return@launch
                }
            if (generation != this@HomeAddProject.generation) return@launch
            updateSession { it.copy(serverProjects = projects, suggestionsResolved = true) }
        }
    }

    private fun loadVolumes(generation: Int) {
        scope.launch {
            val volumes =
                try {
                    filesystemRepository.listDirectory(AddProjectSheetModel.VOLUMES_PATH).folders
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    // Linux has no /Volumes.
                    return@launch
                }
            if (generation != this@HomeAddProject.generation) return@launch
            updateSession { it.copy(volumes = volumes) }
        }
    }

    private fun updateSession(transform: (AddProjectSheetSession) -> AddProjectSheetSession) {
        val session = session ?: return
        state = state.copy(session = transform(session))
        publish()
    }

    private fun publish() {
        val sheet = session?.let { AddProjectSheetModel.build(it) } ?: return
        onSheetChanged(sheet)
    }
}
