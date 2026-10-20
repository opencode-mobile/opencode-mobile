package dev.opencode.mobile.domain.connection

import dev.opencode.mobile.domain.composer.ComposerDraftStore
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.event.StreamOccupancy
import dev.opencode.mobile.domain.event.WorldSignal
import dev.opencode.mobile.domain.event.WorldSignals
import dev.opencode.mobile.domain.feedback.FeedbackIdentityStore
import dev.opencode.mobile.domain.live.ProjectionCaches
import dev.opencode.mobile.domain.model.ModelSelectionConnectionIdentity
import dev.opencode.mobile.domain.model.SessionModelSelectionStore
import dev.opencode.mobile.domain.sessioncache.SessionCache
import dev.opencode.mobile.domain.sync.SyncCoordinator
import dev.opencode.mobile.network.ActiveConnectionManager
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.OpencodeSseHttpClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

sealed class ProfileChange {
    data class Switch(
        val id: String,
    ) : ProfileChange()

    data class Saved(
        val profile: ConnectionProfile,
    ) : ProfileChange()

    data class Removed(
        val id: String,
    ) : ProfileChange()

    data object Cleared : ProfileChange()
}

@Singleton
class ConnectionSessionCoordinator
    @Inject
    constructor(
        private val activeConnectionManager: ActiveConnectionManager,
        private val connectionProfileStore: ConnectionProfileStore,
        private val secureCredentialStore: SecureCredentialStore,
        private val sessionModelSelectionStore: SessionModelSelectionStore,
        private val composerDraftStore: ComposerDraftStore,
        private val sessionCache: SessionCache,
        private val feedbackIdentityStore: FeedbackIdentityStore,
        private val httpClient: OpencodeHttpClient,
        private val sseHttpClient: OpencodeSseHttpClient,
        private val eventStream: ForegroundEventStream,
        private val syncCoordinator: SyncCoordinator,
        private val worldSignals: WorldSignals,
        private val projectionCaches: ProjectionCaches,
    ) {
        private val mutex = Mutex()
        private val switchGeneration = AtomicLong(0)

        val profileGeneration: Long
            get() = switchGeneration.get()

        private val profileSavedEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val profileSaved: SharedFlow<Unit> = profileSavedEvents.asSharedFlow()

        private val profileClearedEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val profileCleared: SharedFlow<Unit> = profileClearedEvents.asSharedFlow()

        fun currentProfile(): ConnectionProfile? = activeConnectionManager.currentProfile()

        suspend fun listProfiles(): List<ConnectionProfile> = connectionProfileStore.listProfiles()

        fun bindActiveProfile() {
            projectionCaches.bindProfile(activeConnectionManager.currentProfile()?.id)
        }

        suspend fun saveProfile(
            profile: ConnectionProfile,
            serverProtocol: ServerProtocol? = null,
        ) {
            val stamped = profile.copy(serverProtocol = serverProtocol ?: profile.serverProtocol)
            mutex.withLock {
                val wasNew = stamped.id.isBlank()
                connectionProfileStore.saveProfile(stamped)
                val active = connectionProfileStore.activeProfile()
                val savedIsActive = wasNew || (stamped.id.isNotBlank() && active?.id == stamped.id)
                if (savedIsActive) {
                    applyProfileChangeLocked(ProfileChange.Saved(stamped))
                }
            }
        }

        suspend fun switchProfile(id: String) {
            mutex.withLock {
                applyProfileChangeLocked(ProfileChange.Switch(id))
            }
        }

        suspend fun removeProfile(id: String) {
            mutex.withLock {
                applyProfileChangeLocked(ProfileChange.Removed(id))
            }
        }

        suspend fun clearConnectionData() {
            mutex.withLock {
                applyProfileChangeLocked(ProfileChange.Cleared)
            }
        }

        private suspend fun applyProfileChangeLocked(target: ProfileChange) {
            val generation = switchGeneration.incrementAndGet()
            eventStream.stop()
            syncCoordinator.beginProfileSwitch(generation)
            when (target) {
                is ProfileChange.Switch -> connectionProfileStore.applyProfile(target.id)
                is ProfileChange.Saved -> Unit
                is ProfileChange.Removed -> {
                    // Forget the profile and everything stored under it: password, transcripts
                    // and activity in the session cache, composer drafts, model selections.
                    // Re-adding the same server later starts clean.
                    val removed = connectionProfileStore.listProfiles().firstOrNull { it.id == target.id }
                    connectionProfileStore.removeProfile(target.id)
                    if (removed != null) {
                        val identity = ModelSelectionConnectionIdentity(removed.baseUrl, removed.username)
                        sessionCache.purgeConnection(profileId = removed.id, serverBaseUrl = removed.baseUrl)
                        sessionModelSelectionStore.clearConnection(identity)
                        composerDraftStore.clearConnection(identity)
                    }
                }
                is ProfileChange.Cleared -> {
                    secureCredentialStore.clearAll()
                    connectionProfileStore.clearAll()
                    sessionModelSelectionStore.clearAll()
                    composerDraftStore.clearAll()
                    feedbackIdentityStore.writeRefreshToken(null)
                }
            }
            val active =
                if (target is ProfileChange.Cleared) {
                    null
                } else {
                    connectionProfileStore.activeProfile()
                }
            if (active == null) {
                projectionCaches.clearAll()
            } else {
                projectionCaches.clearLiveProjections()
            }
            projectionCaches.bindProfile(active?.id)
            if (active == null) {
                activeConnectionManager.disconnect()
            } else {
                activeConnectionManager.reloadFromStore()
            }
            httpClient.rebuild()
            sseHttpClient.rebuild()
            worldSignals.emit(WorldSignal.ProfileSwitched)
            if (
                worldSignals.occupancy.value != StreamOccupancy.Stopped &&
                activeConnectionManager.current() != null
            ) {
                eventStream.start()
            }
            if (active == null) {
                profileClearedEvents.emit(Unit)
            } else {
                profileSavedEvents.emit(Unit)
            }
        }
    }
