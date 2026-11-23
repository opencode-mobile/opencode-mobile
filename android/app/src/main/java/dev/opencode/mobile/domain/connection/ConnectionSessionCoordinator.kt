package dev.opencode.mobile.domain.connection

import dev.opencode.mobile.domain.composer.ComposerDraftStore
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.event.StreamOccupancy
import dev.opencode.mobile.domain.event.WorldSignal
import dev.opencode.mobile.domain.event.WorldSignals
import dev.opencode.mobile.domain.feedback.FeedbackIdentityStore
import dev.opencode.mobile.domain.live.ProjectionCaches
import dev.opencode.mobile.domain.model.SessionModelSelectionStore
import dev.opencode.mobile.domain.model.modelSelectionIdentity
import dev.opencode.mobile.domain.serverhealth.ServerHealthStore
import dev.opencode.mobile.domain.sessioncache.SessionCache
import dev.opencode.mobile.domain.sync.SyncCoordinator
import dev.opencode.mobile.network.ActiveConnectionManager
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.OpencodeSseHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.withContext
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
        private val serverHealthStore: ServerHealthStore,
    ) {
        private val mutex = Mutex()
        private val switchGeneration = AtomicLong(0)
        private val retargetScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val retargetJob = AtomicReference<Job?>(null)

        /** Where HTTP clients are closed and rebuilt; tests run it on their scheduler. */
        internal var clientRebuildDispatcher: CoroutineDispatcher = Dispatchers.IO

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

        private val profileListChangedEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        /** An inactive profile was removed: only the list of saved servers changed. */
        val profileListChanged: SharedFlow<Unit> = profileListChangedEvents.asSharedFlow()

        fun currentProfile(): ConnectionProfile? = activeConnectionManager.currentProfile()

        suspend fun listProfiles(): List<ConnectionProfile> = connectionProfileStore.listProfiles()

        fun bindActiveProfile() {
            projectionCaches.bindProfile(activeConnectionManager.currentProfile()?.id)
        }

        /**
         * Once per launch, before any chat binds: drafts and model picks stored by URL move under
         * their profile id. A failure keeps the old entries, and the next launch tries again.
         */
        suspend fun migrateStoredSelections() {
            try {
                val profiles = connectionProfileStore.catalogWithoutPasswords().profiles
                val useCount = profiles.flatMap(ConnectionProfileIdentity::addressesOf).groupingBy { it }.eachCount()
                profiles.forEach { profile ->
                    val addresses = ConnectionProfileIdentity.addressesOf(profile)
                    val identity = profile.modelSelectionIdentity()
                    composerDraftStore.migrateConnection(identity, addresses)
                    sessionModelSelectionStore.migrateConnection(
                        identity,
                        addresses,
                        exclusiveAddresses = addresses.filterTo(mutableSetOf()) { useCount[it] == 1 },
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                DiagnosticLog.warning(DiagnosticLogArea.Connection, "stored selection migration failed", error)
            }
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
                    if (connectionProfileStore.activeProfile()?.id != id) {
                        // The active server is untouched: its stream, projections and open chat stay.
                        removeStoredProfile(id)
                        return@withLock null
                    }
                    retargetJob.getAndSet(null)?.cancel()
                    applyProfileChangeLocked(ProfileChange.Removed(id))
                    switchGeneration.get()
                }
            if (generation == null) {
                profileListChangedEvents.tryEmit(Unit)
            } else {
                retargetReachableAddress(generation)
            }
        }

        suspend fun clearConnectionData() {
            retargetJob.getAndSet(null)?.cancel()
            mutex.withLock {
                applyProfileChangeLocked(ProfileChange.Cleared)
                // No profile is left to check, so Home must not wait on a cancelled check.
                addressCheckState.value = AddressCheck(switchGeneration.get(), AddressCheck.State.Kept)
            }
        }

        /** Forgets the profile and everything stored under it. Re-adding the server starts clean. */
        private suspend fun removeStoredProfile(id: String) {
            val removed = connectionProfileStore.listProfiles().firstOrNull { it.id == id }
            connectionProfileStore.removeProfile(id)
            if (removed != null) {
                // Every saved address: entries not migrated yet are still stored by URL.
                ConnectionProfileIdentity.addressesOf(removed).forEach { address ->
                    val identity = removed.copy(baseUrl = address).modelSelectionIdentity()
                    sessionCache.purgeConnection(profileId = removed.id, serverBaseUrl = address)
                    sessionModelSelectionStore.clearConnection(identity)
                    composerDraftStore.clearConnection(identity)
                }
            }
            projectionCaches.forgetProfile(id)
            try {
                serverHealthStore.purge(id)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                DiagnosticLog.warning(DiagnosticLogArea.Connection, "server health purge failed", error)
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
                rebuildClients()
                if (
                    worldSignals.occupancy.value != StreamOccupancy.Stopped &&
                    activeConnectionManager.current() != null
                ) {
                    eventStream.start()
                }
            }
            finishAddressCheck(generation, AddressCheck.State.Moved)
        }

        /** Closing a client can block. Not cancellable: a bound server must never keep the old client. */
        private suspend fun rebuildClients() {
            withContext(NonCancellable + clientRebuildDispatcher) {
                // A load in the gap may already use the new server's client; it must stay open.
                httpClient.rebind()
                sseHttpClient.rebuild()
            }
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
            val active =
                try {
                    commitProfileChange(target)
                } finally {
                    // Also after a failure: the stream must not stay stopped on the server still bound.
                    if (
                        worldSignals.occupancy.value != StreamOccupancy.Stopped &&
                        activeConnectionManager.current() != null
                    ) {
                        eventStream.start()
                    }
                }
            if (active == null) {
                profileClearedEvents.emit(Unit)
            } else {
                // Every caller checks the saved addresses next; Home must see that before it loads.
                addressCheckState.value = AddressCheck(generation, AddressCheck.State.Running)
                profileSavedEvents.emit(Unit)
            }
        }

        /** Stores the change and binds its server; returns the profile that is now active. */
        private suspend fun commitProfileChange(target: ProfileChange): ConnectionProfile? {
            when (target) {
                is ProfileChange.Switch -> connectionProfileStore.applyProfile(target.id)
                is ProfileChange.Saved -> Unit
                is ProfileChange.Removed -> removeStoredProfile(target.id)
                is ProfileChange.Cleared -> {
                    secureCredentialStore.clearAll()
                    connectionProfileStore.clearAll()
                    sessionModelSelectionStore.clearAll()
                    composerDraftStore.clearAll()
                    feedbackIdentityStore.writeRefreshToken(null)
                    runCatching { serverHealthStore.purgeAll() }
                }
            }
            val active =
                if (target is ProfileChange.Cleared) {
                    null
                } else {
                    connectionProfileStore.activeProfile()
                }
            // Bind first: clearing restarts subscribed flows, which fetch from whatever is bound.
            if (active == null) {
                activeConnectionManager.disconnect()
            } else {
                activeConnectionManager.reloadFromStore()
            }
            rebuildClients()
            if (active == null) {
                projectionCaches.clearAll()
            } else {
                projectionCaches.clearLiveProjections()
            }
            projectionCaches.bindProfile(active?.id)
            worldSignals.emit(WorldSignal.ProfileSwitched)
            return active
        }
    }
