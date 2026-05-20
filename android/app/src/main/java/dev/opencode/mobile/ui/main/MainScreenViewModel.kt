package dev.opencode.mobile.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.project.ProjectDomainAdapter
import dev.opencode.mobile.domain.project.ProjectRepository
import dev.opencode.mobile.domain.project.ServerProbeError
import dev.opencode.mobile.domain.session.SessionDomainAdapter
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.SessionSummary
import dev.opencode.mobile.network.ServerConnection
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.theme.letterAccentForWorktree
import dev.opencode.mobile.theme.letterCharacterForProject
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class MainScreenViewModel
@Inject
constructor(
  private val projectRepository: ProjectRepository,
  private val sessionRepository: SessionRepository,
  private val connectionProfileStore: ConnectionProfileStore,
  private val sessionDomainAdapter: SessionDomainAdapter,
  private val serverConnection: ServerConnection,
) : ViewModel() {
  private val _uiState = MutableStateFlow<MainScreenUiState>(MainScreenUiState.Probing)
  val uiState: StateFlow<MainScreenUiState> = _uiState.asStateFlow()

  private val serverBaseUrl: String get() = serverConnection.baseUrl

  init {
    refresh()
  }

  fun refresh() {
    viewModelScope.launch { runHomeLoad() }
  }

  fun retryProbe() {
    refresh()
  }

  fun showAddProjectSheet() {
    viewModelScope.launch { loadAddProjectCandidates() }
  }

  fun dismissAddProjectSheet() {
    _uiState.update { state ->
      when (state) {
        is MainScreenUiState.Empty -> state.copy(showAddProjectSheet = false, addProjectCandidates = emptyList())
        is MainScreenUiState.Loaded -> state.copy(showAddProjectSheet = false, addProjectCandidates = emptyList())
        else -> state
      }
    }
  }

  fun addProject(worktree: String) {
    viewModelScope.launch {
      connectionProfileStore.addWorktree(serverBaseUrl, worktree)
      dismissAddProjectSheet()
      runHomeLoad(skipProbe = true)
    }
  }

  fun showSeeAllSessions(worktree: String) {
    val state = _uiState.value as? MainScreenUiState.Loaded ?: return
    val section = state.sections.firstOrNull { it.worktree == worktree } ?: return
    _uiState.value =
      state.copy(
        seeAllSessions =
          SeeAllSessionsSheet(
            worktree = worktree,
            projectName = section.displayName,
            sessions = section.allSessions,
          ),
      )
  }

  fun dismissSeeAllSessions() {
    _uiState.update { state ->
      if (state is MainScreenUiState.Loaded) state.copy(seeAllSessions = null) else state
    }
  }

  fun createSession(worktree: String) {
    viewModelScope.launch {
      runCatching { sessionRepository.createSession(directory = worktree) }
        .onSuccess { session ->
          _uiState.update { state ->
            when (state) {
              is MainScreenUiState.Loaded ->
                state.copy(
                  pendingOpenSession =
                    SessionOpenTarget(
                      sessionId = session.id,
                      title = session.title,
                      worktree = worktree,
                    ),
                )
              else -> state
            }
          }
        }
    }
  }

  fun consumePendingOpenSession() {
    _uiState.update { state ->
      if (state is MainScreenUiState.Loaded) state.copy(pendingOpenSession = null) else state
    }
  }

  private suspend fun runHomeLoad(skipProbe: Boolean = false) {
    if (!skipProbe) {
      _uiState.value = MainScreenUiState.Probing
      try {
        projectRepository.probeServer()
      } catch (error: ServerProbeError) {
        _uiState.value = MainScreenUiState.ProbeFailed(probeErrorMessage(error))
        return
      } catch (error: Throwable) {
        _uiState.value = MainScreenUiState.ProbeFailed(error.message ?: "Unable to reach the server")
        return
      }
    }

    _uiState.value = MainScreenUiState.Loading
    try {
      var worktrees = connectionProfileStore.addedWorktrees(serverBaseUrl)
      if (worktrees.isEmpty()) {
        val bootProject = projectRepository.getCurrentProject(directory = null)
        connectionProfileStore.addWorktree(serverBaseUrl, bootProject.worktree)
        worktrees = listOf(bootProject.worktree)
      }

      if (worktrees.isEmpty()) {
        _uiState.value = MainScreenUiState.Empty()
        return
      }

      val sections = loadProjectSections(worktrees)
      _uiState.value =
        if (sections.isEmpty()) {
          MainScreenUiState.Empty()
        } else {
          MainScreenUiState.Loaded(sections = sections)
        }
    } catch (error: Throwable) {
      _uiState.value = MainScreenUiState.ProbeFailed(error.message ?: "Unable to load projects")
    }
  }

  private suspend fun loadProjectSections(worktrees: List<String>): List<ProjectSectionUi> =
    coroutineScope {
      worktrees.map { worktree ->
        async {
          runCatching { loadSection(worktree) }
            .getOrElse { throwable ->
              ProjectSectionUi(
                worktree = worktree,
                displayName = ProjectDomainAdapter.displayNameFromWorktree(worktree),
                pathSubtitle = worktree,
                letter = letterCharacterForProject(ProjectDomainAdapter.displayNameFromWorktree(worktree)),
                letterAccent = letterAccentForWorktree(worktree),
                previewSessions = emptyList(),
                allSessions = emptyList(),
                totalSessionCount = 0,
                loadError = throwable.message ?: "Unable to load sessions",
              )
            }
        }
      }.awaitAll()
    }

  private suspend fun loadSection(worktree: String): ProjectSectionUi {
    val project = projectRepository.getCurrentProject(directory = worktree)
    val sessions =
      sessionRepository
        .listSessions(directory = worktree)
        .sortedByDescending { it.updatedAtEpochMs ?: 0L }
    val summaries = sessions.map(sessionDomainAdapter::toSessionSummary)
    return ProjectSectionUi(
      worktree = worktree,
      displayName = project.displayName,
      pathSubtitle = project.worktree,
      letter = letterCharacterForProject(project.displayName),
      letterAccent = letterAccentForWorktree(worktree),
      previewSessions = summaries.take(PREVIEW_SESSION_LIMIT),
      allSessions = summaries,
      totalSessionCount = summaries.size,
      loadError = null,
    )
  }

  private suspend fun loadAddProjectCandidates() {
    try {
      val added = connectionProfileStore.addedWorktrees(serverBaseUrl).toSet()
      val candidates =
        projectRepository
          .listProjects()
          .filter { it.worktree !in added }
          .map { project ->
            AddProjectCandidateUi(
              worktree = project.worktree,
              displayName = project.displayName,
              pathSubtitle = project.worktree,
              letter = letterCharacterForProject(project.displayName),
              letterAccent = letterAccentForWorktree(project.worktree),
            )
          }
      _uiState.update { state ->
        when (state) {
          is MainScreenUiState.Empty ->
            state.copy(showAddProjectSheet = true, addProjectCandidates = candidates)
          is MainScreenUiState.Loaded ->
            state.copy(showAddProjectSheet = true, addProjectCandidates = candidates)
          else -> state
        }
      }
    } catch (error: Throwable) {
      _uiState.update { state ->
        when (state) {
          is MainScreenUiState.Empty -> state.copy(addProjectError = error.message)
          is MainScreenUiState.Loaded -> state.copy(addProjectError = error.message)
          else -> state
        }
      }
    }
  }

  private fun probeErrorMessage(error: ServerProbeError): String =
    when (error) {
      is ServerProbeError.Transport -> "Unable to reach the server"
      is ServerProbeError.Http -> error.message ?: "Server returned ${error.statusCode}"
      is ServerProbeError.Unhealthy -> "Server reported unhealthy"
    }

  private companion object {
    const val PREVIEW_SESSION_LIMIT = 3
  }
}

sealed interface MainScreenUiState {
  data object Probing : MainScreenUiState

  data class ProbeFailed(val message: String) : MainScreenUiState

  data object Loading : MainScreenUiState

  data class Empty(
    val showAddProjectSheet: Boolean = false,
    val addProjectCandidates: List<AddProjectCandidateUi> = emptyList(),
    val addProjectError: String? = null,
  ) : MainScreenUiState

  data class Loaded(
    val sections: List<ProjectSectionUi>,
    val showAddProjectSheet: Boolean = false,
    val addProjectCandidates: List<AddProjectCandidateUi> = emptyList(),
    val addProjectError: String? = null,
    val seeAllSessions: SeeAllSessionsSheet? = null,
    val pendingOpenSession: SessionOpenTarget? = null,
  ) : MainScreenUiState
}

data class ProjectSectionUi(
  val worktree: String,
  val displayName: String,
  val pathSubtitle: String,
  val letter: Char,
  val letterAccent: LetterAccent,
  val previewSessions: List<SessionSummary>,
  val allSessions: List<SessionSummary>,
  val totalSessionCount: Int,
  val loadError: String? = null,
)

data class AddProjectCandidateUi(
  val worktree: String,
  val displayName: String,
  val pathSubtitle: String,
  val letter: Char,
  val letterAccent: LetterAccent,
)

data class SeeAllSessionsSheet(
  val worktree: String,
  val projectName: String,
  val sessions: List<SessionSummary>,
)

data class SessionOpenTarget(
  val sessionId: String,
  val title: String,
  val worktree: String,
)
