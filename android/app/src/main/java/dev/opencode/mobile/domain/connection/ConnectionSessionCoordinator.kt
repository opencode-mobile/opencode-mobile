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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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

/**
 * The background check of a profile's saved addresses after it became active. [generation] is
 * the profile change it belongs to, so two changes in a row are told apart.
 */
data class AddressCheck(
    val generation: Long,
    val state: State,
) {
    enum class State {
        /** Still probing; a failed call on the stored address is not final yet. */
        Running,

        /** Another saved address won and is now bound. */
        Moved,

        /** The bound address stays. */
        Kept,
    }
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
        private val retargetScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val retargetJob = AtomicReference<Job?>(null)

        val profileGeneration: Long
            get() = switchGeneration.get()

        private val profileSwitchingEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
        val profileSwitching: SharedFlow<String> = profileSwitchingEvents.asSharedFlow()

        private val profileSavedEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val profileSaved: SharedFlow<Unit> = profileSavedEvents.asSharedFlow()

        private val addressCheckState = MutableStateFlow(AddressCheck(0, AddressCheck.State.Kept))

        /** Home refreshes in place when another address wins, and waits on it before showing unreachable. */
        val addressCheck: StateFlow<AddressCheck> = addressCheckState.asStateFlow()

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
            val generation =
                mutex.withLock {
                    val wasNew = stamped.id.isBlank()
                    connectionProfileStore.saveProfile(stamped)
                    val active = connectionProfileStore.activeProfile()
                    val savedIsActive = wasNew || (stamped.id.isNotBlank() && active?.id == stamped.id)
                    if (!savedIsActive) return@withLock null
                    applyProfileChangeLocked(ProfileChange.Saved(stamped))
                    switchGeneration.get()
                }
            // Home paints the stored address, which setup just verified; a better one rebinds after.
            if (generation != null) retargetReachableAddress(generation)
        }

        suspend fun switchProfile(id: String) {
            // Tell the UI before any stop, probe, or list fetch. The sheet and home move now;
            // this function keeps going in the background.
            profileSwitchingEvents.tryEmit(id)
            retargetJob.getAndSet(null)?.cancel()
            val generation =
                mutex.withLock {
                    applyProfileChangeLocked(ProfileChange.Switch(id))
                    switchGeneration.get()
                }
            val job = retargetScope.launch(start = CoroutineStart.LAZY) { retargetReachableAddress(generation) }
            retargetJob.getAndSet(job)?.cancel()
            job.start()
        }

        suspend fun removeProfile(id: String) {
            val generation =
                mutex.withLock {
                    applyProfileChangeLocked(ProfileChange.Removed(id))
                    switchGeneration.get()
                }
            retargetReachableAddress(generation)
        }

        suspend fun clearConnectionData() {
            mutex.withLock {
                applyProfileChangeLocked(ProfileChange.Cleared)
            }
        }

        private suspend fun retargetReachableAddress(generation: Long) {
            if (switchGeneration.get() != generation) return
            val profile = activeConnectionManager.currentProfile()
            val changed =
                profile != null &&
                    try {
                        activeConnectionManager.resolveReachableAddress(profile)
                    } catch (cancellation: CancellationException) {
                        // A newer switch took over; it reports its own check.
                        throw cancellation
                    } catch (_: Exception) {
                        false
                    }
            if (switchGeneration.get() != generation) return
            if (!changed) {
                finishAddressCheck(generation, AddressCheck.State.Kept)
                return
            }
            mutex.withLock {
                if (switchGeneration.get() != generation) return
                eventStream.stop()
                httpClient.rebuild()
                sseHttpClient.rebuild()
                if (
                    worldSignals.occupancy.value != StreamOccupancy.Stopped &&
                    activeConnectionManager.current() != null
                ) {
                    eventStream.start()
                }
            }
            finishAddressCheck(generation, AddressCheck.State.Moved)
        }

        private fun finishAddressCheck(
            generation: Long,
            state: AddressCheck.State,
        ) {
            addressCheckState.compareAndSet(AddressCheck(generation, AddressCheck.State.Running), AddressCheck(generation, state))
        }

        private suspend fun applyProfileChangeLocked(target: ProfileChange) {
            val generation = switchGeneration.incrementAndGet()
            // Cancel in-flight loads before the disconnect, so stopping the stream does not
            // refetch the server we are leaving.
            syncCoordinator.beginProfileSwitch(generation)
            eventStream.stop()
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
                // Every caller checks the saved addresses next; Home must see that before it loads.
                addressCheckState.value = AddressCheck(generation, AddressCheck.State.Running)
                profileSavedEvents.emit(Unit)
            }
        }
    }
