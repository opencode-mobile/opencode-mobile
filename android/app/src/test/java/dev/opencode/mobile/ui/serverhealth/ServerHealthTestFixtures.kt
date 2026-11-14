package dev.opencode.mobile.ui.serverhealth

import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.event.EventStreamConnectionState
import dev.opencode.mobile.domain.event.LiveConnectionConfig
import dev.opencode.mobile.domain.event.LiveConnectionSnapshot
import dev.opencode.mobile.domain.event.StreamOccupancy
import dev.opencode.mobile.domain.serverhealth.ServerHealthConnectionState
import dev.opencode.mobile.domain.serverhealth.ServerHealthEvent
import dev.opencode.mobile.domain.serverhealth.ServerHealthFailureCategory
import dev.opencode.mobile.domain.serverhealth.ServerHealthMonitor
import dev.opencode.mobile.domain.serverhealth.ServerHealthPing
import dev.opencode.mobile.domain.serverhealth.ServerHealthPingResult
import dev.opencode.mobile.domain.serverhealth.ServerHealthPingTarget
import dev.opencode.mobile.domain.serverhealth.ServerHealthStore
import dev.opencode.mobile.domain.serverhealth.ServerHealthStreamFailureRelay
import dev.opencode.mobile.network.ServerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

internal const val HEALTH_EPOCH = 1_000_000_000_000L
internal const val HEALTH_MINUTE = 60_000L

internal val workProfile =
    ConnectionProfile(
        baseUrl = "http://192.168.1.2:4096",
        username = "opencode",
        id = "a",
        name = "Work",
        serverProtocol = ServerProtocol.V2,
    )
internal val homeProfile =
    ConnectionProfile(baseUrl = "http://10.0.0.1:4096", username = "opencode", id = "b", name = "Home")

/** Live 60–30 min ago, reconnecting until 20 min ago, live again, stopped 10 min ago; three recent checks. */
internal suspend fun seedHealthHistory(
    store: ServerHealthStore,
    profileId: String,
) {
    listOf(
        ServerHealthEvent.state(profileId, HEALTH_EPOCH - 60 * HEALTH_MINUTE, ServerHealthConnectionState.Live),
        ServerHealthEvent.state(profileId, HEALTH_EPOCH - 30 * HEALTH_MINUTE, ServerHealthConnectionState.Reconnecting),
        ServerHealthEvent.state(profileId, HEALTH_EPOCH - 20 * HEALTH_MINUTE, ServerHealthConnectionState.Live),
        ServerHealthEvent.state(profileId, HEALTH_EPOCH - 10 * HEALTH_MINUTE, ServerHealthConnectionState.Stopped),
    ).forEach { store.appendEvent(it) }
    listOf(
        ServerHealthPing(profileId, HEALTH_EPOCH - 5 * HEALTH_MINUTE, 12, null, pid = 1, version = "1.4.0"),
        ServerHealthPing(profileId, HEALTH_EPOCH - 4 * HEALTH_MINUTE, null, ServerHealthFailureCategory.Network),
        ServerHealthPing(profileId, HEALTH_EPOCH - 3 * HEALTH_MINUTE, 15, null, pid = 1, version = "1.4.0"),
    ).forEach { store.appendPing(it) }
}

/** A monitor fed by hand; occupancy stays in Grace so no checks are sent. */
internal class MonitorHarness(
    scope: CoroutineScope,
    store: ServerHealthStore,
    clock: () -> Long,
) {
    val snapshots = MutableStateFlow(LiveConnectionSnapshot())
    val occupancy = MutableStateFlow(StreamOccupancy.Grace)
    var activeProfile: String? = null
    val monitor =
        ServerHealthMonitor(
            snapshots = snapshots,
            occupancy = occupancy,
            streamFailures = ServerHealthStreamFailureRelay().failures,
            activeTarget = {
                activeProfile?.let { ServerHealthPingTarget(it, ServerConnection(baseUrl = "http://192.168.1.2:4096"), ServerProtocol.V2) }
            },
            pinger = { _, _ -> ServerHealthPingResult.Failure(ServerHealthFailureCategory.Network) },
            store = store,
            scope = scope,
            clock = clock,
        )

    fun connect(profileId: String) {
        activeProfile = profileId
        snapshots.value =
            LiveConnectionSnapshot(
                config = LiveConnectionConfig(profileId, "identity", ServerProtocol.V2, emptyList()),
                connectionState = EventStreamConnectionState.Connected,
                requiredTargets = listOf("global", "instance"),
                liveTargets = listOf("global", "instance"),
            )
    }
}

internal class ProfilesStore(
    profiles: List<ConnectionProfile>,
    private var activeId: String?,
) : ConnectionProfileStore {
    private val saved = profiles.toMutableList()

    override suspend fun activeProfile(): ConnectionProfile? = saved.firstOrNull { it.id == activeId }

    override suspend fun listProfiles(): List<ConnectionProfile> = saved.toList()

    override suspend fun saveProfile(profile: ConnectionProfile) {
        saved.removeAll { it.id == profile.id }
        saved += profile
        if (activeId == null) activeId = profile.id
    }

    override suspend fun applyProfile(id: String) {
        activeId = id
    }

    override suspend fun removeProfile(id: String) {
        saved.removeAll { it.id == id }
    }

    override suspend fun addedWorktrees(serverBaseUrl: String): List<String> = emptyList()

    override suspend fun addWorktree(
        serverBaseUrl: String,
        worktree: String,
    ) = Unit

    override suspend fun removeWorktree(
        serverBaseUrl: String,
        worktree: String,
    ) = Unit

    override suspend fun setAddedWorktrees(
        serverBaseUrl: String,
        worktrees: List<String>,
    ) = Unit

    override suspend fun clearAll() {
        saved.clear()
        activeId = null
    }
}
