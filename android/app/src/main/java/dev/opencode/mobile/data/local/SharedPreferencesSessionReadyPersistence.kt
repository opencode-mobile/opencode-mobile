package dev.opencode.mobile.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.opencode.mobile.domain.live.SessionReadyPersistedEntry
import dev.opencode.mobile.domain.live.SessionReadyPersistedState
import dev.opencode.mobile.domain.live.SessionReadyPersistence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SharedPreferencesSessionReadyPersistence
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val json: Json,
    ) : SessionReadyPersistence {
        private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        /** The newest state; every Ready change reads it, so JSON is parsed once per process. */
        @Volatile
        private var current: SessionReadyPersistedState? = null
        private val pendingWrite = AtomicReference<SessionReadyPersistedState?>(null)

        @OptIn(ExperimentalCoroutinesApi::class)
        private val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

        override fun load(): SessionReadyPersistedState = current ?: synchronized(this) { current ?: read().also { current = it } }

        private fun read(): SessionReadyPersistedState {
            val raw = prefs.getString(KEY_STATE, null) ?: prefs.getString(KEY_ENTRIES_LEGACY, null)
            if (raw.isNullOrBlank()) return SessionReadyPersistedState()
            return runCatching {
                val element = json.parseToJsonElement(raw)
                when (element) {
                    is JsonArray -> {
                        // Legacy: ready-only array with readyAtEpochMs.
                        val ready =
                            json.decodeFromJsonElement<List<LegacyReadyEntry>>(element).map {
                                SessionReadyPersistedEntry(
                                    sessionId = it.sessionId,
                                    directory = it.directory,
                                    atEpochMs = it.readyAtEpochMs,
                                    profileId = "",
                                )
                            }
                        SessionReadyPersistedState(ready = ready)
                    }
                    is JsonObject -> {
                        val stored = json.decodeFromJsonElement<StoredState>(element)
                        SessionReadyPersistedState(
                            ready = stored.ready.map { it.toDomain() },
                            watched = stored.watched.map { it.toDomain() },
                        )
                    }
                    else -> SessionReadyPersistedState()
                }
            }.getOrDefault(SessionReadyPersistedState())
        }

        /** Updates memory now; the newest state is encoded and written off the caller's thread. */
        override fun save(state: SessionReadyPersistedState) {
            current = state
            if (pendingWrite.getAndSet(state) != null) return
            writes.launch {
                pendingWrite.getAndSet(null)?.let(::write)
            }
        }

        private fun write(state: SessionReadyPersistedState) {
            val stored =
                StoredState(
                    ready = state.ready.map { StoredEntry.from(it) },
                    watched = state.watched.map { StoredEntry.from(it) },
                )
            prefs
                .edit()
                .putString(KEY_STATE, json.encodeToString(stored))
                .remove(KEY_ENTRIES_LEGACY)
                .apply()
        }

        @Serializable
        private data class StoredState(
            val ready: List<StoredEntry> = emptyList(),
            val watched: List<StoredEntry> = emptyList(),
        )

        @Serializable
        private data class StoredEntry(
            val sessionId: String,
            val directory: String,
            val atEpochMs: Long,
            val profileId: String = "",
        ) {
            fun toDomain() =
                SessionReadyPersistedEntry(
                    sessionId = sessionId,
                    directory = directory,
                    atEpochMs = atEpochMs,
                    profileId = profileId,
                )

            companion object {
                fun from(entry: SessionReadyPersistedEntry) =
                    StoredEntry(
                        sessionId = entry.sessionId,
                        directory = entry.directory,
                        atEpochMs = entry.atEpochMs,
                        profileId = entry.profileId,
                    )
            }
        }

        @Serializable
        private data class LegacyReadyEntry(
            val sessionId: String,
            val directory: String,
            val readyAtEpochMs: Long,
        )

        companion object {
            private const val PREFS_NAME = "session_ready"
            private const val KEY_STATE = "ready_state"
            private const val KEY_ENTRIES_LEGACY = "ready_entries"
        }
    }
