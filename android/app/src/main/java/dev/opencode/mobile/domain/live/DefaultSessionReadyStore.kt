package dev.opencode.mobile.domain.live

import android.util.Log
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.session.SessionStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultSessionReadyStore
    @Inject
    constructor(
        private val sessionStatusLiveStore: SessionStatusLiveStore,
        private val eventStream: ForegroundEventStream,
        private val liveStoreScope: LiveStoreScope,
        private val persistence: SessionReadyPersistence,
        private val clock: SessionReadyClock,
    ) : SessionReadyStore {
        private val lock = Any()
        private val watched = mutableMapOf<String, WatchRecord>()
        private val ready = mutableMapOf<String, ReadyRecord>()
        private var currentProfileId: String = ""
        private var focusedSessionId: String? = null
        private var focusedDirectory: String? = null
        private val previousTypes = mutableMapOf<String, MutableMap<String, String?>>()
        private val readyFlows = ConcurrentHashMap<String, MutableStateFlow<Set<String>>>()
        private val statusJobs = ConcurrentHashMap<String, Job>()
        private val eventJob: Job =
            liveStoreScope.coroutineScope.launch {
                eventStream.events.collect { event ->
                    onEvent(event.type, event.sessionId)
                }
            }

        init {
            val directoriesToObserve: Set<String>
            synchronized(lock) {
                loadPersistedLocked()
                directoriesToObserve = watched.values.map { it.directory }.toSet()
            }
            directoriesToObserve.forEach { ensureStatusObservation(it) }
        }

        override fun observeReadyIds(directory: String): Flow<Set<String>> {
            synchronized(lock) {
                val prunedDirectories = pruneExpiredLocked()
                prunedDirectories.forEach { publishReadyLocked(it, prune = false) }
            }
            ensureStatusObservation(directory)
            return readyFlow(directory)
        }

        override fun notifyPromptSent(
            sessionId: String,
            directory: String,
        ) {
            synchronized(lock) {
                startWatchLocked(sessionId, directory, reason = "prompt")
            }
            ensureStatusObservation(directory)
        }

        override fun setFocusedSession(
            sessionId: String?,
            directory: String?,
        ) {
            synchronized(lock) {
                if (sessionId == null) {
                    // Leave while Working: keep a watch even if debounced status never
                    // delivered a busy snapshot before unfocus.
                    val priorId = focusedSessionId
                    val priorDirectory = focusedDirectory
                    if (priorId != null && priorDirectory != null) {
                        val type = previousTypes[priorDirectory]?.get(priorId)
                        if (type.isBusyOrRetry()) {
                            startWatchLocked(priorId, priorDirectory, reason = "leave-while-busy")
                        }
                    }
                    focusedSessionId = null
                    focusedDirectory = null
                    if (priorId != null) {
                        logI("focus cleared session=$priorId")
                    }
                } else {
                    focusedSessionId = sessionId
                    focusedDirectory = directory
                    // Opening Session Chat acknowledges Ready. Drop Ready and any
                    // leftover watch so idle catch-up cannot immediately re-stamp Ready
                    // after a focused session.status arms a speculative watch.
                    val readyRecord = ready.remove(sessionId)
                    val watchRecord = watched.remove(sessionId)
                    if (readyRecord != null || watchRecord != null) {
                        if (readyRecord != null) {
                            logI("ready cleared on focus session=$sessionId")
                        }
                        if (watchRecord != null) {
                            logI("watch cleared on focus session=$sessionId")
                        }
                        persistStateLocked()
                        val directoryToPublish = readyRecord?.directory ?: watchRecord?.directory
                        publishReadyLocked(directoryToPublish)
                    }
                    if (directory != null) {
                        val previous = previousTypes.getOrPut(directory) { mutableMapOf() }
                        if (!previous[sessionId].isBusyOrRetry()) {
                            previous[sessionId] = "idle"
                        }
                    }
                }
            }
            if (sessionId != null && directory != null) {
                ensureStatusObservation(directory)
            }
        }

        override fun bindProfile(profileId: String?) {
            val directoriesToObserve: Set<String>
            synchronized(lock) {
                val next = profileId.orEmpty()
                if (next == currentProfileId) return
                val migrateUnscoped =
                    currentProfileId.isEmpty() &&
                        next.isNotEmpty() &&
                        (ready.isNotEmpty() || watched.isNotEmpty())
                if (migrateUnscoped) {
                    currentProfileId = next
                    persistStateLocked()
                    focusedSessionId = null
                    focusedDirectory = null
                    previousTypes.clear()
                    logI("bindProfile migrated unscoped profile=$next")
                    return
                }
                persistStateLocked()
                watched.clear()
                ready.clear()
                focusedSessionId = null
                focusedDirectory = null
                previousTypes.clear()
                currentProfileId = next
                loadPersistedLocked()
                readyFlows.keys.toList().forEach { directory ->
                    publishReadyLocked(directory, prune = false)
                }
                directoriesToObserve = watched.values.map { it.directory }.toSet()
                logI("bindProfile profile=$next ready=${ready.size} watched=${watched.size}")
            }
            directoriesToObserve.forEach { ensureStatusObservation(it) }
        }

        override fun clearCache() {
            synchronized(lock) {
                watched.clear()
                ready.clear()
                focusedSessionId = null
                focusedDirectory = null
                previousTypes.clear()
                persistence.save(SessionReadyPersistedState())
                readyFlows.keys.toList().forEach { directory ->
                    readyFlows[directory]?.value = emptySet()
                }
                logI("clearCache")
            }
        }

        private fun onEvent(
            type: String,
            sessionId: String?,
        ) {
            if (sessionId == null) return
            synchronized(lock) {
                when (type) {
                    "session.status" -> {
                        val directory = focusedDirectory
                        if (sessionId == focusedSessionId && directory != null) {
                            startWatchLocked(sessionId, directory, reason = "focused-status-event")
                            ensureStatusObservationLocked(directory)
                        }
                    }
                    "session.idle" -> {
                        val directory = watched[sessionId]?.directory ?: return
                        handleIdleLocked(sessionId, directory, markPreviousIdle = true)
                    }
                }
            }
        }

        private fun ensureStatusObservation(directory: String) {
            synchronized(lock) {
                ensureStatusObservationLocked(directory)
            }
            readyFlow(directory)
        }

        private fun ensureStatusObservationLocked(directory: String) {
            statusJobs.computeIfAbsent(directory) {
                liveStoreScope.coroutineScope.launch {
                    sessionStatusLiveStore.observeStatuses(directory).collect { statuses ->
                        onStatuses(directory, statuses)
                    }
                }
            }
        }

        private fun onStatuses(
            directory: String,
            statuses: Map<String, SessionStatus>,
        ) {
            synchronized(lock) {
                val prunedDirectories = pruneExpiredLocked()
                prunedDirectories.forEach { publishReadyLocked(it, prune = false) }
                val previous = previousTypes.getOrPut(directory) { mutableMapOf() }
                val watchedIds = watched.filterValues { it.directory == directory }.keys
                val sessionIds = previous.keys + statuses.keys + watchedIds
                var readyChanged = false

                for (sessionId in sessionIds) {
                    val type = statuses[sessionId]?.raw?.get("type")
                    val prev = previous[sessionId]
                    val isBusy = type.isBusyOrRetry()
                    val wasBusy = prev.isBusyOrRetry()
                    val firstSighting = watched[sessionId]?.directory == directory && !previous.containsKey(sessionId)

                    if (sessionId == focusedSessionId &&
                        directory == focusedDirectory &&
                        isBusy
                    ) {
                        startWatchLocked(sessionId, directory, reason = "focused-busy")
                    }

                    if (watched[sessionId]?.directory == directory) {
                        val watch = watched[sessionId]
                        val becameIdle = wasBusy && !isBusy
                        val restoredAlreadyIdle =
                            firstSighting && !isBusy && (watch?.allowIdleCatchUp == true)
                        if (becameIdle || restoredAlreadyIdle) {
                            if (handleIdleLocked(sessionId, directory, markPreviousIdle = false)) {
                                readyChanged = true
                            }
                        }
                    }

                    if (statuses.containsKey(sessionId)) {
                        previous[sessionId] = type
                    } else if (previous.containsKey(sessionId)) {
                        previous.remove(sessionId)
                    }
                }

                if (readyChanged) {
                    publishReadyLocked(directory)
                }
            }
        }

        /** @return true if a new watch was armed */
        private fun startWatchLocked(
            sessionId: String,
            directory: String,
            reason: String,
        ): Boolean {
            if (watched[sessionId]?.directory == directory) return false
            // focused-status-event is speculative (type unknown before debounce). It must
            // not use restoredAlreadyIdle catch-up, or opening an idle Ready session can
            // re-arm Ready after leave when the status map still omits idle rows.
            val allowIdleCatchUp = reason != "focused-status-event"
            watched[sessionId] =
                WatchRecord(
                    directory = directory,
                    watchedAtEpochMs = clock.nowEpochMs(),
                    allowIdleCatchUp = allowIdleCatchUp,
                )
            logI("watch started reason=$reason session=$sessionId directory=$directory")
            persistStateLocked()
            return true
        }

        /** @return true if Ready set changed */
        private fun handleIdleLocked(
            sessionId: String,
            directory: String,
            markPreviousIdle: Boolean,
        ): Boolean {
            if (watched[sessionId]?.directory != directory) return false
            watched.remove(sessionId)
            if (markPreviousIdle) {
                previousTypes.getOrPut(directory) { mutableMapOf() }[sessionId] = "idle"
            }
            return if (sessionId == focusedSessionId) {
                persistStateLocked()
                logI("idle handled outcome=suppressed-open session=$sessionId directory=$directory")
                false
            } else {
                ready[sessionId] = ReadyRecord(directory = directory, readyAtEpochMs = clock.nowEpochMs())
                logI("idle handled outcome=ready session=$sessionId directory=$directory")
                persistStateLocked()
                publishReadyLocked(directory)
                true
            }
        }

        private fun loadPersistedLocked() {
            val now = clock.nowEpochMs()
            val state = persistence.load()
            var pruned = false
            for (entry in state.ready) {
                if (entry.profileId != currentProfileId) continue
                if (now - entry.atEpochMs >= READY_TTL_MS) {
                    pruned = true
                    continue
                }
                ready[entry.sessionId] =
                    ReadyRecord(
                        directory = entry.directory,
                        readyAtEpochMs = entry.atEpochMs,
                    )
            }
            for (entry in state.watched) {
                if (entry.profileId != currentProfileId) continue
                if (now - entry.atEpochMs >= READY_TTL_MS) {
                    pruned = true
                    continue
                }
                if (ready.containsKey(entry.sessionId)) continue
                watched[entry.sessionId] =
                    WatchRecord(
                        directory = entry.directory,
                        watchedAtEpochMs = entry.atEpochMs,
                        allowIdleCatchUp = true,
                    )
            }
            if (pruned || ready.isNotEmpty() || watched.isNotEmpty()) {
                persistStateLocked()
            }
            if (ready.isNotEmpty()) {
                logI("loaded persisted ready count=${ready.size}")
            }
            if (watched.isNotEmpty()) {
                logI("loaded persisted watched count=${watched.size}")
            }
        }

        /** @return directories whose Ready sets changed because of expiry */
        private fun pruneExpiredLocked(): Set<String> {
            val now = clock.nowEpochMs()
            val expiredReady =
                ready.filterValues { now - it.readyAtEpochMs >= READY_TTL_MS }.keys.toList()
            val expiredWatched =
                watched.filterValues { now - it.watchedAtEpochMs >= READY_TTL_MS }.keys.toList()
            if (expiredReady.isEmpty() && expiredWatched.isEmpty()) return emptySet()
            val directories = mutableSetOf<String>()
            expiredReady.forEach { id ->
                ready.remove(id)?.directory?.let { directories.add(it) }
                logI("ready expired session=$id")
            }
            expiredWatched.forEach { id ->
                watched.remove(id)?.directory?.let { directories.add(it) }
                logI("watch expired session=$id")
            }
            persistStateLocked()
            return directories
        }

        private fun persistStateLocked() {
            val now = clock.nowEpochMs()
            val stored = persistence.load()
            val otherReady =
                stored.ready.filter {
                    it.profileId != currentProfileId && now - it.atEpochMs < READY_TTL_MS
                }
            val otherWatched =
                stored.watched.filter {
                    it.profileId != currentProfileId && now - it.atEpochMs < READY_TTL_MS
                }
            persistence.save(
                SessionReadyPersistedState(
                    ready =
                        otherReady +
                            ready.map { (sessionId, record) ->
                                SessionReadyPersistedEntry(
                                    sessionId = sessionId,
                                    directory = record.directory,
                                    atEpochMs = record.readyAtEpochMs,
                                    profileId = currentProfileId,
                                )
                            },
                    watched =
                        otherWatched +
                            watched.map { (sessionId, record) ->
                                SessionReadyPersistedEntry(
                                    sessionId = sessionId,
                                    directory = record.directory,
                                    atEpochMs = record.watchedAtEpochMs,
                                    profileId = currentProfileId,
                                )
                            },
                ),
            )
        }

        private fun publishReadyLocked(
            directory: String?,
            prune: Boolean = true,
        ) {
            if (prune) {
                val prunedDirectories = pruneExpiredLocked()
                prunedDirectories.filter { it != directory }.forEach { publishReadyLocked(it, prune = false) }
            }
            if (directory == null) {
                readyFlows.keys.toList().forEach { dir ->
                    readyFlows[dir]?.value = readyIdsFor(dir)
                }
                return
            }
            readyFlow(directory).value = readyIdsFor(directory)
        }

        private fun readyIdsFor(directory: String): Set<String> =
            ready.filterValues { it.directory == directory }.keys.toSet()

        private fun readyFlow(directory: String): MutableStateFlow<Set<String>> =
            readyFlows.getOrPut(directory) {
                MutableStateFlow(readyIdsFor(directory))
            }

        private fun logI(message: String) {
            runCatching { Log.i(TAG, message) }
        }

        private data class ReadyRecord(
            val directory: String,
            val readyAtEpochMs: Long,
        )

        private data class WatchRecord(
            val directory: String,
            val watchedAtEpochMs: Long,
            val allowIdleCatchUp: Boolean,
        )

        companion object {
            private const val TAG = "SessionReadyStore"
            const val READY_TTL_MS: Long = 24L * 60L * 60L * 1000L
        }
    }

private fun String?.isBusyOrRetry(): Boolean = this == "busy" || this == "retry"
