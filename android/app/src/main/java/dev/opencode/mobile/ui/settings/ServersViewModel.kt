package dev.opencode.mobile.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.R
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionSessionCoordinator
import dev.opencode.mobile.ui.text.UiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ServersUiState(
    val profiles: List<ConnectionProfile> = emptyList(),
    val activeId: String? = null,
    val pendingRemoval: ConnectionProfile? = null,
) {
    /** Top bar subtitle, such as "2 Connection Profiles". */
    val profileCountLabel: UiText
        get() = UiText.plural(R.plurals.servers_profile_count, profiles.size)

    /** Remove dialog title; the profile name is server content and stays verbatim. */
    val removeDialogTitle: UiText
        get() {
            val pending = pendingRemoval ?: return UiText.resource(R.string.servers_remove_dialog_title)
            return UiText.resource(R.string.servers_remove_dialog_title_named, UiText.verbatim(pending.displayName))
        }
}

@HiltViewModel
class ServersViewModel
    @Inject
    constructor(
        private val connectionSessionCoordinator: ConnectionSessionCoordinator,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(ServersUiState())
        val uiState: StateFlow<ServersUiState> = _uiState.asStateFlow()

        init {
            refresh()
        }

        fun refresh() {
            viewModelScope.launch {
                val profiles = connectionSessionCoordinator.listProfiles()
                val active = connectionSessionCoordinator.currentProfile()
                _uiState.update { it.copy(profiles = profiles, activeId = active?.id) }
            }
        }

        fun requestRemove(profile: ConnectionProfile) {
            _uiState.update { it.copy(pendingRemoval = profile) }
        }

        fun dismissRemove() {
            _uiState.update { it.copy(pendingRemoval = null) }
        }

        fun confirmRemove() {
            val pending = _uiState.value.pendingRemoval ?: return
            viewModelScope.launch {
                connectionSessionCoordinator.removeProfile(pending.id)
                _uiState.update { it.copy(pendingRemoval = null) }
                refresh()
            }
        }
    }
