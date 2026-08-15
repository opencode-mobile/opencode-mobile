package dev.opencode.mobile.domain.connection

import dev.opencode.mobile.domain.model.SessionModelSelectionStore
import dev.opencode.mobile.network.ActiveConnectionManager
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.OpencodeSseHttpClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionSessionCoordinator
    @Inject
    constructor(
        private val activeConnectionManager: ActiveConnectionManager,
        private val connectionProfileStore: ConnectionProfileStore,
        private val secureCredentialStore: SecureCredentialStore,
        private val sessionModelSelectionStore: SessionModelSelectionStore,
        private val httpClient: OpencodeHttpClient,
        private val sseHttpClient: OpencodeSseHttpClient,
    ) {
        private val profileSavedEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val profileSaved: SharedFlow<Unit> = profileSavedEvents.asSharedFlow()

        private val profileClearedEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val profileCleared: SharedFlow<Unit> = profileClearedEvents.asSharedFlow()

        fun currentProfile(): ConnectionProfile? = activeConnectionManager.currentProfile()

        suspend fun listProfiles(): List<ConnectionProfile> = connectionProfileStore.listProfiles()

        suspend fun saveProfile(
            profile: ConnectionProfile,
            serverProtocol: ServerProtocol? = null,
        ) {
            val stamped = profile.copy(serverProtocol = serverProtocol ?: profile.serverProtocol)
            val wasNew = stamped.id.isBlank()
            connectionProfileStore.saveProfile(stamped)
            val active = connectionProfileStore.activeProfile()
            val savedIsActive = wasNew || (stamped.id.isNotBlank() && active?.id == stamped.id)
            if (savedIsActive) {
                rebindActive()
                profileSavedEvents.emit(Unit)
            }
        }

        suspend fun switchProfile(id: String) {
            connectionProfileStore.applyProfile(id)
            rebindActive()
            profileSavedEvents.emit(Unit)
        }

        suspend fun removeProfile(id: String) {
            connectionProfileStore.removeProfile(id)
            if (connectionProfileStore.activeProfile() == null) {
                activeConnectionManager.disconnect()
                httpClient.rebuild()
                sseHttpClient.rebuild()
                profileClearedEvents.tryEmit(Unit)
            } else {
                rebindActive()
                profileSavedEvents.emit(Unit)
            }
        }

        suspend fun clearConnectionData() {
            secureCredentialStore.clearAll()
            connectionProfileStore.clearAll()
            sessionModelSelectionStore.clearAll()
            activeConnectionManager.disconnect()
            httpClient.rebuild()
            sseHttpClient.rebuild()
            profileClearedEvents.tryEmit(Unit)
        }

        private suspend fun rebindActive() {
            activeConnectionManager.reloadFromStore()
            httpClient.rebuild()
            sseHttpClient.rebuild()
        }
    }
