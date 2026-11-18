package dev.opencode.mobile.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.R
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionSessionCoordinator
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.serverhealth.ServerHealthClock
import dev.opencode.mobile.domain.serverhealth.ServerHealthLiveStatus
import dev.opencode.mobile.domain.serverhealth.ServerHealthMonitor
import dev.opencode.mobile.domain.serverhealth.ServerHealthStore
import dev.opencode.mobile.domain.text.UiText
import dev.opencode.mobile.ui.serverhealth.ServerHealthQuickView
import dev.opencode.mobile.ui.serverhealth.ServerHealthQuickViewData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ServersUiState(
    val profiles: List<ConnectionProfile> = emptyList(),
    val activeId: String? = null,
    /** Health of the active profile; null until it has been read. */
    val healthQuickView: ServerHealthQuickView? = null,
) {
    /** Top bar subtitle, such as "2 Connection Profiles". */
    val profileCountLabel: UiText
        get() = UiText.plural(R.plurals.servers_profile_count, profiles.size)
}

@HiltViewModel
class ServersViewModel
    @Inject
    constructor(
        private val connectionSessionCoordinator: ConnectionSessionCoordinator,
        private val serverHealthMonitor: ServerHealthMonitor,
        private val serverHealthStore: ServerHealthStore,
        private val clock: ServerHealthClock,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(ServersUiState())
        val uiState: StateFlow<ServersUiState> = _uiState.asStateFlow()

        private val _openHealthRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)

        /** Profile ids whose Server Health page should open. */
        val openHealthRequests: SharedFlow<String> = _openHealthRequests.asSharedFlow()

        /** Reads a day of pings and builds the quick view's report; kept off Main. */
        internal var workDispatcher: CoroutineDispatcher = Dispatchers.Default

        private var switchJob: Job? = null
        private var healthData: Pair<String, ServerHealthQuickViewData>? = null
        private var liveStatus = ServerHealthLiveStatus()

        init {
            refresh()
            viewModelScope.launch {
                combine(serverHealthMonitor.revision, _uiState.map { it.activeId }.distinctUntilChanged()) { _, activeId ->
                    activeId
                }.conflate()
                    .collect { activeId ->
                        reloadHealth(activeId)
                        delay(RELOAD_THROTTLE_MS)
                    }
            }
            viewModelScope.launch {
                serverHealthMonitor.liveStatus.collect { status ->
                    liveStatus = status
                    publishHealth()
                }
            }
        }

        fun refresh() {
            viewModelScope.launch { reload() }
        }

        /**
         * Makes [profileId] the active server, the same switch as a pick in the Home server sheet.
         * Ignores the active server and taps while an earlier switch is still running.
         */
        fun switchTo(profileId: String) {
            val state = _uiState.value
            if (switchJob?.isActive == true) return
            if (profileId == state.activeId || state.profiles.none { it.id == profileId }) return
            switchJob =
                viewModelScope.launch {
                    try {
                        connectionSessionCoordinator.switchProfile(profileId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        DiagnosticLog.warning(DiagnosticLogArea.Connection, "servers switch failed id=$profileId", error)
                    }
                    reload()
                    if (_uiState.value.activeId != profileId) {
                        DiagnosticLog.warning(DiagnosticLogArea.Connection, "servers switch did not apply id=$profileId")
                    }
                }
        }

        private suspend fun reload() {
            val profiles = connectionSessionCoordinator.listProfiles()
            val active = connectionSessionCoordinator.currentProfile()
            _uiState.update { it.copy(profiles = profiles, activeId = active?.id) }
            publishHealth()
        }

        fun openHealth(profileId: String) {
            _openHealthRequests.tryEmit(profileId)
        }

        private suspend fun reloadHealth(activeId: String?) {
            if (activeId.isNullOrBlank()) {
                healthData = null
                publishHealth()
                return
            }
            val data =
                try {
                    withContext(workDispatcher) {
                        ServerHealthQuickViewData.load(serverHealthStore, activeId, clock.nowMs())
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return
                }
            healthData = activeId to data
            publishHealth()
        }

        private fun publishHealth() {
            val activeId = _uiState.value.activeId
            val data = healthData?.takeIf { it.first == activeId }?.second
            _uiState.update {
                it.copy(healthQuickView = if (activeId == null || data == null) null else data.withLive(activeId, liveStatus))
            }
        }

        private companion object {
            const val RELOAD_THROTTLE_MS = 2_000L
        }
    }
