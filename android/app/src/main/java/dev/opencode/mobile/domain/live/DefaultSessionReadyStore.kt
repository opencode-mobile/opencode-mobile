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
    ) : SessionReadyStore {
        private val lock = Any()
        private val watched = mutableMapOf<String, String>()
        private val ready = mutableMapOf<String, String>()
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

        override fun observeReadyIds(directory: String): Flow<Set<String>> {
            ensureStatusObservation(directory)
            return readyFlow(directory)
        }

        override fun notifyPromptSent(
            sessionId: String,
            directory: String,
        ) {
            synchronized(lock) {
                watched[sessionId] = directory
                logI("watch started reason=prompt session=$sessionId directory=$directory")
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
                        if (type.isBusyOrRetry() && watched[priorId] != priorDirectory) {
                            watched[priorId] = priorDirectory
                            logI(
                                "watch started reason=leave-while-busy session=$priorId directory=$priorDirectory",
                            )
                        }
                    }
                    focusedSessionId = null
                    focusedDirectory = null
                } else {
                    focusedSessionId = sessionId
                    focusedDirectory = directory
                    val readyDirectory = ready.remove(sessionId)
                    if (readyDirectory != null) {
                        logI("ready cleared on focus session=$sessionId")
                        publishReadyLocked(readyDirectory)
                    }
                }
            }
            if (sessionId != null && directory != null) {
                ensureStatusObservation(directory)
            }
        }

        override fun clearCache() {
            synchronized(lock) {
                watched.clear()
                ready.clear()
                focusedSessionId = null
                focusedDirectory = null
                previousTypes.clear()
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
                            if (watched[sessionId] != directory) {
                                watched[sessionId] = directory
                                logI(
                                    "watch started reason=focused-status-event session=$sessionId directory=$directory",
                                )
                            }
                            ensureStatusObservationLocked(directory)
                        }
                    }
                    "session.idle" -> {
                        val directory = watched[sessionId] ?: return
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
                val previous = previousTypes.getOrPut(directory) { mutableMapOf() }
                val sessionIds = previous.keys + statuses.keys
                var readyChanged = false

                for (sessionId in sessionIds) {
                    val type = statuses[sessionId]?.raw?.get("type")
                    val prev = previous[sessionId]
                    val isBusy = type.isBusyOrRetry()
                    val wasBusy = prev.isBusyOrRetry()

                    if (sessionId == focusedSessionId &&
                        directory == focusedDirectory &&
                        isBusy
                    ) {
                        if (watched[sessionId] != directory) {
                            watched[sessionId] = directory
                            logI("watch started reason=focused-busy session=$sessionId directory=$directory")
                        }
                    }

                    if (watched[sessionId] == directory && wasBusy && !isBusy) {
                        if (handleIdleLocked(sessionId, directory, markPreviousIdle = false)) {
                            readyChanged = true
                        }
                    }

                    if (statuses.containsKey(sessionId)) {
                        previous[sessionId] = type
                    } else {
                        previous.remove(sessionId)
                    }
                }

                if (readyChanged) {
                    publishReadyLocked(directory)
                }
            }
        }

        /** @return true if Ready set changed */
        private fun handleIdleLocked(
            sessionId: String,
            directory: String,
            markPreviousIdle: Boolean,
        ): Boolean {
            if (watched[sessionId] != directory) return false
            watched.remove(sessionId)
            if (markPreviousIdle) {
                previousTypes.getOrPut(directory) { mutableMapOf() }[sessionId] = "idle"
            }
            return if (sessionId == focusedSessionId) {
                logI("idle handled outcome=suppressed-open session=$sessionId directory=$directory")
                false
            } else {
                ready[sessionId] = directory
                logI("idle handled outcome=ready session=$sessionId directory=$directory")
                publishReadyLocked(directory)
                true
            }
        }

        private fun publishReadyLocked(directory: String?) {
            if (directory == null) {
                readyFlows.keys.toList().forEach { dir ->
                    readyFlows[dir]?.value = ready.filterValues { it == dir }.keys.toSet()
                }
                return
            }
            readyFlow(directory).value = ready.filterValues { it == directory }.keys.toSet()
        }

        private fun readyFlow(directory: String): MutableStateFlow<Set<String>> =
            readyFlows.getOrPut(directory) {
                MutableStateFlow(ready.filterValues { it == directory }.keys.toSet())
            }

        private fun logI(message: String) {
            runCatching { Log.i(TAG, message) }
        }

        companion object {
            private const val TAG = "SessionReadyStore"
        }
    }

private fun String?.isBusyOrRetry(): Boolean = this == "busy" || this == "retry"
