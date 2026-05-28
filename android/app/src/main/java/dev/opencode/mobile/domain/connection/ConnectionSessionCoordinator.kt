package dev.opencode.mobile.domain.connection

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
        private val httpClient: OpencodeHttpClient,
        private val sseHttpClient: OpencodeSseHttpClient,
    ) {
        private val profileSavedEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val profileSaved: SharedFlow<Unit> = profileSavedEvents.asSharedFlow()

        suspend fun saveProfile(profile: ConnectionProfile) {
            activeConnectionManager.applyProfile(profile)
            httpClient.rebuild()
            sseHttpClient.rebuild()
            profileSavedEvents.emit(Unit)
        }
    }
