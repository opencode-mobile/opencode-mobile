package dev.opencode.mobile.ui.archived

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.home.HomeProjectsSnapshot
import dev.opencode.mobile.domain.preferences.HomeDisplayPreferences
import dev.opencode.mobile.domain.session.SessionDomainAdapter
import dev.opencode.mobile.domain.session.SessionRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Screen Model of the Archived sessions screen; the logic lives in [ArchivedSessionsModel]. */
@HiltViewModel
class ArchivedSessionsViewModel
    @Inject
    constructor(
        sessionRepository: SessionRepository,
        sessionDomainAdapter: SessionDomainAdapter,
        homeProjectsSnapshot: HomeProjectsSnapshot,
        foregroundEventStream: ForegroundEventStream,
    ) : ViewModel() {
        private val model =
            ArchivedSessionsModel(
                sessionRepository = sessionRepository,
                sessionDomainAdapter = sessionDomainAdapter,
                projects = homeProjectsSnapshot.projects,
                events = foregroundEventStream.events,
                scope = viewModelScope,
                worktreesEnabled = { HomeDisplayPreferences.showsWorktrees },
            )
        val uiState: StateFlow<ArchivedSessionsUiState> = model.state

        fun bind(scopeId: String) = model.bind(scopeId)

        fun retry() = model.retry()

        fun showAllProjects() = model.showAllProjects()

        fun openActions(row: ArchivedSessionRow) = model.openActions(row)

        fun dismissActions() = model.dismissActions()

        fun chooseOpen(): ArchivedSessionRow? = model.chooseOpen()

        fun chooseUnarchive() = model.chooseUnarchive()

        fun chooseDelete() = model.chooseDelete()

        fun dismissDeleteConfirm() = model.dismissDeleteConfirm()

        fun confirmDelete() = model.confirmDelete()

        fun undoUnarchive(sessionId: String) = model.undoUnarchive(sessionId)

        fun consumeSnackbar() = model.consumeSnackbar()
    }
