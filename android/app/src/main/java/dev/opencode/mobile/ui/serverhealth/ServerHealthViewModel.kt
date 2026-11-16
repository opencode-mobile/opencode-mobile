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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

        /** Reads and report building: up to a week of pings, too much for Main during the page's transition. */
        internal var workDispatcher: CoroutineDispatcher = Dispatchers.Default

        private var profileId: String? = null
        private var activeProfileId: String? = null
        private var bindJobs: List<Job> = emptyList()
        private val loadMutex = Mutex()
        private var record: HealthRecord? = null
        private var built: BuiltHealth? = null
        private var liveStatus = ServerHealthLiveStatus()

        /** The retained week of pings and events [reload] reads; every range is built from it. */
        private class HealthRecord(
            val pings: List<ServerHealthPing>,
            val events: List<ServerHealthEvent>,
        )

        /** What [rebuildLocked] computes off Main; [publish] lays the live status over it. */
        private class BuiltHealth(
            val report: ServerHealthReport,
            val recentPings: List<Int?>,
            val recentP95Ms: Int?,
            val storedVersion: String?,
        )

        fun bind(profileId: String) {
            if (this.profileId == profileId) return
            this.profileId = profileId
            bindJobs.forEach { it.cancel() }
            record = null
            built = null
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
            // Built from the week already read, so a store failure cannot strand the old range's chart.
            // Nothing read yet (the first read failed): retry the store instead.
            viewModelScope.launch {
                if (record == null) return@launch reload(id)
                loadMutex.withLock { rebuildLocked(id) }
                publish(id)
            }
        }

        private suspend fun loadProfile(profileId: String) {
            // Only the name, protocol and active id are shown: never decrypt passwords here.
            val catalog = runCatching { profileStore.catalogWithoutPasswords() }.getOrNull()
            val profile = catalog?.profiles?.firstOrNull { it.id == profileId }
            activeProfileId = catalog?.activeId
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
                val since = clock.nowMs() - ServerHealthStore.RETENTION_MS
                val next =
                    try {
                        withContext(workDispatcher) {
                            HealthRecord(store.pings(profileId, since), store.events(profileId, since))
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        return
                    }
                if (this.profileId != profileId) return
                record = next
                rebuildLocked(profileId)
            }
            publish(profileId)
        }

        /** Builds the selected range from [record]; call with [loadMutex] held so builds apply in order. */
        private suspend fun rebuildLocked(profileId: String) {
            val data = record ?: return
            val range = _uiState.value.range
            val next = withContext(workDispatcher) { build(data, clock.nowMs(), range) }
            // A range picked while this one was building wins; its own rebuild applies it.
            if (this.profileId != profileId || _uiState.value.range != range) return
            built = next
        }

        private fun build(
            data: HealthRecord,
            now: Long,
            range: ServerHealthRange,
        ): BuiltHealth {
            val recent = ServerHealthReportBuilder.recentPings(data.pings, now)
            return BuiltHealth(
                report = ServerHealthReportBuilder.build(data.pings, data.events, now, range),
                recentPings = recent,
                recentP95Ms = ServerHealthReportBuilder.percentile(recent.filterNotNull(), 0.95),
                storedVersion = data.pings.lastOrNull { it.version != null }?.version,
            )
        }

        private fun publish(profileId: String) {
            if (this.profileId != profileId) return
            val data = built
            val isActive = liveStatus.profileId == profileId || activeProfileId == profileId
            val isLive = liveStatus.profileId == profileId
            _uiState.update { state ->
                // A report for another range stays up until the reload for the new one lands.
                state.copy(
                    status = if (isLive) liveStatus.state else ServerHealthConnectionState.Stopped,
                    currentPingMs = if (isLive) liveStatus.lastPingMs else null,
                    recentPings = data?.recentPings.orEmpty(),
                    recentP95Ms = data?.recentP95Ms,
                    report = data?.report,
                    serverVersion = (if (isLive) liveStatus.lastVersion else null) ?: data?.storedVersion,
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
