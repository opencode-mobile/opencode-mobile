package dev.opencode.mobile.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.R
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.home.HomeProject
import dev.opencode.mobile.domain.home.HomeScope
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionDomainAdapter
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.SessionSummary
import dev.opencode.mobile.domain.session.SessionTitleSearch
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.theme.letterAccentForWorktree
import dev.opencode.mobile.theme.letterCharacterForProject
import dev.opencode.mobile.ui.components.SessionListSession
import dev.opencode.mobile.ui.text.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What Home shows, as search reads it: its projects, per-path session lists, and row settings. */
data class SessionSearchHome(
    val projects: List<HomeProject> = emptyList(),
    val sections: List<ProjectSectionUi> = emptyList(),
    /** Sessions of worktrees Home lists separately (v1), by worktree directory. */
    val extraSessions: Map<String, List<SessionSummary>> = emptyMap(),
    val worktreesEnabled: Boolean = true,
) {
    /** Home's rows for these lists, with Home's live status. */
    fun rows(): List<SessionListSession> = homeSessionListSessions(projects, sections, extraSessions, worktreesEnabled)

    /** Every list Home loads for [scopeId]: the added paths, and worktrees it lists on their own. */
    fun listedDirectories(scopeId: String): List<String> {
        val inScope = projects.filter { scopeId == HomeScope.ALL_ID || it.key == scopeId }
        val worktreeLists = inScope.flatMap { project -> project.worktrees.map { it.directory } }.filter(extraSessions::containsKey)
        return (inScope.flatMap { it.memberPaths } + worktreeLists).distinct()
    }

    companion object {
        fun from(
            state: MainScreenUiState.Loaded,
            worktreesEnabled: Boolean,
        ): SessionSearchHome = SessionSearchHome(state.projects, state.sections, state.home.extraSessions, worktreesEnabled)
    }
}

/** One choice of the scope menu: All projects or a Home project. */
data class SessionSearchScopeOption(
    val id: String,
    val title: UiText,
    /** Project letter; null for All projects. */
    val letter: Char? = null,
    val letterAccent: LetterAccent? = null,
) {
    val isAll: Boolean
        get() = id == HomeScope.ALL_ID
}

/** A matching session, drawn with Home's row; [match] is the matched part of its title. */
data class SessionSearchResult(
    val session: SessionListSession,
    val match: IntRange?,
)

sealed interface SessionSearchContent {
    /** Nothing typed: a short hint naming the scope. */
    data object Hint : SessionSearchContent

    /** The server is answering and nothing matched yet. */
    data object Searching : SessionSearchContent

    data class Results(
        val results: List<SessionSearchResult>,
    ) : SessionSearchContent

    /** [offerAllProjects]: a project scope, and another project has a match. */
    data class NoMatch(
        val offerAllProjects: Boolean,
    ) : SessionSearchContent
}

data class SessionSearchUiState(
    val open: Boolean = false,
    val query: String = "",
    val scope: SessionSearchScopeOption = ALL_PROJECTS,
    val scopeOptions: List<SessionSearchScopeOption> = listOf(ALL_PROJECTS),
    /** A server search for the current query and scope is running. */
    val busy: Boolean = false,
    val content: SessionSearchContent = SessionSearchContent.Hint,
    /** Bumped when the field should take focus again (Ctrl+F while search is open). */
    val focusRequest: Int = 0,
) {
    companion object {
        val ALL_PROJECTS = SessionSearchScopeOption(HomeScope.ALL_ID, UiText.resource(R.string.home_all_projects))
    }
}

/**
 * Screen Model of Search sessions (#929). Results come from Home's rows at once, then from the
 * server's title search, which also finds sessions Home has not loaded.
 */
@HiltViewModel
class SessionSearchViewModel internal constructor(
    private val search: suspend (query: String, directory: String) -> List<Session>,
    private val sessionDomainAdapter: SessionDomainAdapter,
    private val debounceMs: Long = SEARCH_DEBOUNCE_MS,
) : ViewModel() {
    @Inject
    constructor(
        sessionRepository: SessionRepository,
        sessionDomainAdapter: SessionDomainAdapter,
    ) : this(
        search = { query, directory -> sessionRepository.searchSessions(query, directory) },
        sessionDomainAdapter = sessionDomainAdapter,
    )

    private val _uiState = MutableStateFlow(SessionSearchUiState())
    val uiState: StateFlow<SessionSearchUiState> = _uiState.asStateFlow()

    private var home = SessionSearchHome()
    private var homeRows: List<SessionListSession> = emptyList()

    /** Server matches of [serverKey]'s query and scope, by id; null until the server answered. */
    private var serverRows: List<SessionListSession>? = null
    private var serverKey: Pair<String, String>? = null
    private var serverJob: Job? = null

    /** Opens search in Home's scope (a worktree scope searches its project) with an empty query. */
    fun open(homeScopeId: String) {
        if (_uiState.value.open) {
            _uiState.value = _uiState.value.copy(focusRequest = _uiState.value.focusRequest + 1)
            return
        }
        cancelServerSearch()
        val scopeId =
            when (val scope = HomeScope.resolve(homeScopeId, home.projects, home.worktreesEnabled)) {
                is HomeScope.Project -> scope.key
                is HomeScope.Worktree -> scope.projectKey
                HomeScope.All, null -> HomeScope.ALL_ID
            }
        _uiState.value =
            SessionSearchUiState(
                open = true,
                scope = scopeOption(scopeId),
                scopeOptions = scopeOptions(),
                focusRequest = _uiState.value.focusRequest + 1,
            )
    }

    fun close() {
        cancelServerSearch()
        _uiState.value = SessionSearchUiState(focusRequest = _uiState.value.focusRequest)
    }

    fun updateQuery(text: String) {
        if (text == _uiState.value.query) return
        _uiState.value = _uiState.value.copy(query = text)
        refresh(debounce = true)
    }

    fun clearQuery() = updateQuery("")

    fun selectScope(id: String) {
        if (id == _uiState.value.scope.id) return
        _uiState.value = _uiState.value.copy(scope = scopeOption(id))
        refresh(debounce = false)
    }

    fun searchAllProjects() = selectScope(HomeScope.ALL_ID)

    /** Esc (#869 order): clears the query first, then closes search. */
    fun escape() {
        if (_uiState.value.query.isNotEmpty()) clearQuery() else close()
    }

    /** Home's lists changed (live status, a new session, a project added or removed). */
    fun homeChanged(next: SessionSearchHome) {
        if (next == home) return
        home = next
        homeRows = next.rows()
        val state = _uiState.value
        val options = scopeOptions()
        // A removed project falls back to All projects.
        val scope = options.firstOrNull { it.id == state.scope.id } ?: SessionSearchUiState.ALL_PROJECTS
        _uiState.value = state.copy(scope = scope, scopeOptions = options)
        if (!state.open) return
        if (scope.id != state.scope.id) refresh(debounce = false) else publish()
    }

    private fun refresh(debounce: Boolean) {
        val state = _uiState.value
        val query = SessionTitleSearch.normalizedQuery(state.query)
        val key = query to state.scope.id
        if (key != serverKey) {
            cancelServerSearch()
            serverKey = key
            if (query.isNotEmpty()) startServerSearch(query, state.scope.id, debounce)
        }
        publish()
    }

    private fun startServerSearch(
        query: String,
        scopeId: String,
        debounce: Boolean,
    ) {
        val key = query to scopeId
        val directories = home.listedDirectories(scopeId)
        if (directories.isEmpty()) {
            serverRows = emptyList()
            return
        }
        _uiState.value = _uiState.value.copy(busy = true)
        serverJob =
            viewModelScope.launch {
                if (debounce) delay(debounceMs)
                val replies =
                    try {
                        coroutineScope {
                            directories
                                .map { directory -> async { directory to search(query, directory) } }
                                .awaitAll()
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        DiagnosticLog.warning(DiagnosticLogArea.Chat, "session search failed", error)
                        null
                    }
                if (serverKey != key) return@launch
                serverRows = replies?.let(::serverRowsOf).orEmpty()
                _uiState.value = _uiState.value.copy(busy = false)
                publish()
            }
    }

    /** Server sessions as Home would draw them: each in the list it came from. */
    private fun serverRowsOf(replies: List<Pair<String, List<Session>>>): List<SessionListSession> {
        val byDirectory = replies.toMap()
        val summaries = byDirectory.mapValues { (_, sessions) -> sessions.map { sessionDomainAdapter.toSessionSummary(it) } }
        val sections =
            home.sections
                .filter { it.worktree in byDirectory }
                .map { section -> section.copy(allSessions = summaries[section.worktree].orEmpty()) }
        val extra = summaries.filterKeys { directory -> home.sections.none { it.worktree == directory } }
        return homeSessionListSessions(home.projects, sections, extra, home.worktreesEnabled)
    }

    private fun cancelServerSearch() {
        serverJob?.cancel()
        serverJob = null
        serverKey = null
        serverRows = null
        if (_uiState.value.busy) _uiState.value = _uiState.value.copy(busy = false)
    }

    private fun publish() {
        val state = _uiState.value
        val query = SessionTitleSearch.normalizedQuery(state.query)
        val content =
            if (query.isEmpty()) {
                SessionSearchContent.Hint
            } else {
                val results = matches(query, state.scope.id)
                when {
                    results.isNotEmpty() -> SessionSearchContent.Results(results)
                    state.busy -> SessionSearchContent.Searching
                    else ->
                        SessionSearchContent.NoMatch(
                            offerAllProjects = !state.scope.isAll && matches(query, HomeScope.ALL_ID).isNotEmpty(),
                        )
                }
            }
        _uiState.value = state.copy(content = content)
    }

    /** Home rows win over server copies (they carry live status); newest first. */
    private fun matches(
        query: String,
        scopeId: String,
    ): List<SessionSearchResult> {
        val known = homeRows.associateBy { it.id }
        val server = serverRows.orEmpty().filterNot { it.id in known }
        return (homeRows + server)
            .filter { scopeId == HomeScope.ALL_ID || it.worktree == scopeId }
            .mapNotNull { row -> SessionTitleSearch.matchRange(row.title, query)?.let { SessionSearchResult(row, it) } }
            .sortedByDescending { it.session.updatedAtEpochMs ?: 0L }
    }

    private fun scopeOptions(): List<SessionSearchScopeOption> =
        listOf(SessionSearchUiState.ALL_PROJECTS) +
            home.projects.map { project ->
                SessionSearchScopeOption(
                    id = project.key,
                    title = UiText.verbatim(project.displayName),
                    letter = letterCharacterForProject(project.displayName),
                    letterAccent = letterAccentForWorktree(project.primaryPath),
                )
            }

    private fun scopeOption(id: String): SessionSearchScopeOption =
        scopeOptions().firstOrNull { it.id == id } ?: SessionSearchUiState.ALL_PROJECTS

    companion object {
        const val SEARCH_DEBOUNCE_MS = 250L
    }
}
