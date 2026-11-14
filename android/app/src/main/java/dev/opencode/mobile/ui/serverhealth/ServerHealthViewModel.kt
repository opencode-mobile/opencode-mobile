package dev.opencode.mobile.ui.serverhealth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.R
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.serverhealth.ServerHealthClock
import dev.opencode.mobile.domain.serverhealth.ServerHealthConnectionState
import dev.opencode.mobile.domain.serverhealth.ServerHealthEvent
import dev.opencode.mobile.domain.serverhealth.ServerHealthLiveStatus
import dev.opencode.mobile.domain.serverhealth.ServerHealthMonitor
import dev.opencode.mobile.domain.serverhealth.ServerHealthPing
import dev.opencode.mobile.domain.serverhealth.ServerHealthRange
import dev.opencode.mobile.domain.serverhealth.ServerHealthReport
import dev.opencode.mobile.domain.serverhealth.ServerHealthReportBuilder
import dev.opencode.mobile.domain.serverhealth.ServerHealthStore
import dev.opencode.mobile.domain.text.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

data class ServerHealthUiState(
    val profileName: String = "",
    val protocolLabel: UiText? = null,
    val status: ServerHealthConnectionState = ServerHealthConnectionState.Stopped,
    val currentPingMs: Int? = null,
    val recentPings: List<Int?> = emptyList(),
    val recentP95Ms: Int? = null,
    val range: ServerHealthRange = ServerHealthRange.Day,
    val report: ServerHealthReport? = null,
    val serverVersion: String? = null,
    val liveTargets: Int = 0,
    val requiredTargets: Int = 0,
    val isActiveProfile: Boolean = false,
)

/** Server Health for one Connection Profile. Live status shows only while it is the active profile. */
@HiltViewModel
class ServerHealthViewModel
    @Inject
    constructor(
        private val monitor: ServerHealthMonitor,
        private val store: ServerHealthStore,
        private val profileStore: ConnectionProfileStore,
        private val clock: ServerHealthClock,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(ServerHealthUiState())
        val uiState: StateFlow<ServerHealthUiState> = _uiState.asStateFlow()

        private var profileId: String? = null
        private var activeProfileId: String? = null
        private var bindJobs: List<Job> = emptyList()
        private val loadMutex = Mutex()
        private var loaded: Pair<List<ServerHealthPing>, List<ServerHealthEvent>>? = null
        private var liveStatus = ServerHealthLiveStatus()

        fun bind(profileId: String) {
            if (this.profileId == profileId) return
            this.profileId = profileId
            bindJobs.forEach { it.cancel() }
            loaded = null
            _uiState.value = ServerHealthUiState()
            bindJobs =
                listOf(
                    viewModelScope.launch { loadProfile(profileId) },
                    viewModelScope.launch {
                        monitor.revision.collect {
                            reload(profileId)
                            delay(RELOAD_THROTTLE_MS)
                        }
                    },
                    viewModelScope.launch {
                        monitor.liveStatus.collect { status ->
                            liveStatus = status
                            publish(profileId)
                        }
                    },
                )
        }

        fun selectRange(range: ServerHealthRange) {
            if (_uiState.value.range == range) return
            _uiState.update { it.copy(range = range) }
            val id = profileId ?: return
            viewModelScope.launch { reload(id) }
        }

        private suspend fun loadProfile(profileId: String) {
            val profiles = runCatching { profileStore.listProfiles() }.getOrDefault(emptyList())
            val profile = profiles.firstOrNull { it.id == profileId }
            activeProfileId = runCatching { profileStore.activeProfile()?.id }.getOrNull()
            _uiState.update {
                it.copy(
                    profileName = profile?.displayName.orEmpty(),
                    protocolLabel =
                        when (profile?.serverProtocol) {
                            ServerProtocol.V1 -> UiText.resource(R.string.server_health_protocol_v1)
                            ServerProtocol.V2 -> UiText.resource(R.string.server_health_protocol_v2)
                            null -> null
                        },
                )
            }
            publish(profileId)
        }

        private suspend fun reload(profileId: String) {
            loadMutex.withLock {
                val now = clock.nowMs()
                val since = now - ServerHealthStore.RETENTION_MS
                val data =
                    try {
                        store.pings(profileId, since) to store.events(profileId, since)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        return
                    }
                if (this.profileId != profileId) return
                loaded = data
            }
            publish(profileId)
        }

        private fun publish(profileId: String) {
            if (this.profileId != profileId) return
            val now = clock.nowMs()
            val data = loaded
            val isActive = liveStatus.profileId == profileId || activeProfileId == profileId
            val isLive = liveStatus.profileId == profileId
            _uiState.update { state ->
                val pings = data?.first.orEmpty()
                val recent = if (data == null) emptyList() else ServerHealthReportBuilder.recentPings(pings, now)
                val report = data?.let { ServerHealthReportBuilder.build(it.first, it.second, now, state.range) }
                state.copy(
                    status = if (isLive) liveStatus.state else ServerHealthConnectionState.Stopped,
                    currentPingMs = if (isLive) liveStatus.lastPingMs else null,
                    recentPings = recent,
                    recentP95Ms = ServerHealthReportBuilder.percentile(recent.filterNotNull(), 0.95),
                    report = report,
                    serverVersion =
                        (if (isLive) liveStatus.lastVersion else null)
                            ?: pings.lastOrNull { it.version != null }?.version,
                    liveTargets = if (isLive) liveStatus.liveTargets else 0,
                    requiredTargets = if (isLive) liveStatus.requiredTargets else 0,
                    isActiveProfile = isActive,
                )
            }
        }

        private companion object {
            const val RELOAD_THROTTLE_MS = 2_000L
        }
    }
