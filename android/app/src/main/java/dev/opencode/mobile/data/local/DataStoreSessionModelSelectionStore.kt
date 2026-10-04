package dev.opencode.mobile.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.opencode.mobile.domain.model.ModelSelectionConnectionIdentity
import dev.opencode.mobile.domain.model.SessionModelSelection
import dev.opencode.mobile.domain.model.SessionModelSelectionStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64
import javax.inject.Inject

class DataStoreSessionModelSelectionStore
    internal constructor(
        private val dataStore: DataStore<Preferences>,
        private val json: Json,
    ) : SessionModelSelectionStore {
        @Inject
        constructor(
            @ApplicationContext context: Context,
            json: Json,
        ) : this(context.sessionModelSelectionDataStore, json)

        suspend fun load(
            serverBaseUrl: String,
            worktree: String,
            sessionId: String,
        ): SessionModelSelection? =
            loadSession(
                ModelSelectionConnectionIdentity(serverBaseUrl, DEFAULT_USERNAME),
                worktree,
                sessionId,
            )

        suspend fun save(
            serverBaseUrl: String,
            worktree: String,
            sessionId: String,
            selection: SessionModelSelection,
        ) {
            saveSession(ModelSelectionConnectionIdentity(serverBaseUrl, DEFAULT_USERNAME), worktree, sessionId, selection)
        }

        override suspend fun loadSession(
            connectionIdentity: ModelSelectionConnectionIdentity,
            worktree: String,
            sessionId: String,
        ): SessionModelSelection? {
            val prefs = dataStore.data.first()
            return decodeSelection(prefs[sessionKey(connectionIdentity, worktree, sessionId)])
                ?: decodeSelection(prefs[legacyKey(connectionIdentity.serverBaseUrl, worktree, sessionId)])
        }

        override suspend fun saveSession(
            connectionIdentity: ModelSelectionConnectionIdentity,
            worktree: String,
            sessionId: String,
            selection: SessionModelSelection,
        ) {
            val stored = selection.withTimestamp()
            dataStore.edit { prefs ->
                saveIfNewer(prefs, sessionKey(connectionIdentity, worktree, sessionId), stored)
                prefs.remove(legacyKey(connectionIdentity.serverBaseUrl, worktree, sessionId))
                evictOldestSessions(prefs, connectionIdentity)
            }
        }

        override suspend fun deleteSession(
            connectionIdentity: ModelSelectionConnectionIdentity,
            worktree: String,
            sessionId: String,
        ) {
            dataStore.edit { prefs ->
                prefs.remove(sessionKey(connectionIdentity, worktree, sessionId))
                prefs.remove(legacyKey(connectionIdentity.serverBaseUrl, worktree, sessionId))
            }
        }

        override suspend fun deleteSessionEntries(
            connectionIdentity: ModelSelectionConnectionIdentity,
            sessionId: String,
        ) {
            val sessionSuffix = "|${encode(sessionId)}"
            val legacyPrefix = normalizeBaseUrl(connectionIdentity.serverBaseUrl)
            dataStore.edit { prefs ->
                prefs
                    .asMap()
                    .keys
                    .filter { key ->
                        (key.name.startsWith(sessionPrefix(connectionIdentity)) && key.name.endsWith(sessionSuffix)) ||
                            (key.name.startsWith(legacyPrefix) && key.name.endsWith("|$sessionId"))
                    }.forEach { key ->
                        @Suppress("UNCHECKED_CAST")
                        prefs.remove(key as Preferences.Key<String>)
                    }
            }
        }

        override suspend fun loadNewSessionPreference(
            connectionIdentity: ModelSelectionConnectionIdentity,
            worktree: String,
        ): SessionModelSelection? {
            val prefs = dataStore.data.first()
            return decodeSelection(prefs[preferenceKey(connectionIdentity, worktree)])
        }

        override suspend fun saveNewSessionPreference(
            connectionIdentity: ModelSelectionConnectionIdentity,
            worktree: String,
            selection: SessionModelSelection,
        ) {
            dataStore.edit { prefs ->
                saveIfNewer(prefs, preferenceKey(connectionIdentity, worktree), selection.withTimestamp())
            }
        }

        override suspend fun loadNewSessionRuntimeMode(
            connectionIdentity: ModelSelectionConnectionIdentity,
            worktree: String,
        ): String? =
            dataStore.data
                .first()[runtimeModeKey(connectionIdentity, worktree)]
                ?.takeIf { it.isNotBlank() }

        override suspend fun saveNewSessionRuntimeMode(
            connectionIdentity: ModelSelectionConnectionIdentity,
            worktree: String,
            runtimeMode: String,
        ) {
            dataStore.edit { prefs ->
                prefs[runtimeModeKey(connectionIdentity, worktree)] = runtimeMode
            }
        }

        override suspend fun clearAll() {
            dataStore.edit { prefs ->
                prefs.clear()
            }
        }

        private fun legacyKey(
            serverBaseUrl: String,
            worktree: String,
            sessionId: String,
        ): androidx.datastore.preferences.core.Preferences.Key<String> =
            stringPreferencesKey(
                listOf(
                    normalizeBaseUrl(serverBaseUrl),
                    worktree,
                    sessionId,
                ).joinToString("|"),
            )

        private fun sessionKey(
            identity: ModelSelectionConnectionIdentity,
            worktree: String,
            sessionId: String,
        ) = stringPreferencesKey("${sessionPrefix(identity)}${encode(worktree)}|${encode(sessionId)}")

        private fun preferenceKey(
            identity: ModelSelectionConnectionIdentity,
            worktree: String,
        ) = stringPreferencesKey("${preferencePrefix(identity)}${encode(worktree)}")

        private fun runtimeModeKey(
            identity: ModelSelectionConnectionIdentity,
            worktree: String,
        ) = stringPreferencesKey("$VERSION_PREFIX|runtimeMode|${identityKey(identity)}|${encode(worktree)}")

        private fun sessionPrefix(identity: ModelSelectionConnectionIdentity): String = "$VERSION_PREFIX|session|${identityKey(identity)}|"

        private fun preferencePrefix(identity: ModelSelectionConnectionIdentity): String =
            "$VERSION_PREFIX|preference|${identityKey(identity)}|"

        private fun identityKey(identity: ModelSelectionConnectionIdentity): String =
            "${encode(normalizeBaseUrl(identity.serverBaseUrl))}|${encode(effectiveUsername(identity.username))}"

        private fun encode(value: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

        private fun decodeSelection(raw: String?): SessionModelSelection? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                json.decodeFromString<StoredSessionModelSelection>(raw)
            }.getOrNull()?.let {
                SessionModelSelection(
                    providerId = it.providerId,
                    modelId = it.modelId,
                    variant = it.variant,
                    agentId = it.agentId,
                    savedAtEpochMs = it.savedAtEpochMs,
                )
            }
        }

        private fun saveIfNewer(
            prefs: androidx.datastore.preferences.core.MutablePreferences,
            key: Preferences.Key<String>,
            selection: SessionModelSelection,
        ) {
            val existing = decodeSelection(prefs[key])
            if (existing?.savedAtEpochMs != null &&
                selection.savedAtEpochMs != null &&
                existing.savedAtEpochMs > selection.savedAtEpochMs
            ) {
                return
            }
            prefs[key] =
                json.encodeToString(
                    StoredSessionModelSelection(
                        providerId = selection.providerId,
                        modelId = selection.modelId,
                        variant = selection.variant,
                        agentId = selection.agentId,
                        savedAtEpochMs = selection.savedAtEpochMs,
                    ),
                )
        }

        private fun evictOldestSessions(
            prefs: androidx.datastore.preferences.core.MutablePreferences,
            identity: ModelSelectionConnectionIdentity,
        ) {
            val entries =
                prefs
                    .asMap()
                    .filterKeys { it.name.startsWith(sessionPrefix(identity)) }
                    .mapNotNull { (key, value) ->
                        val selection = decodeSelection(value as? String) ?: return@mapNotNull null
                        @Suppress("UNCHECKED_CAST")
                        (key as Preferences.Key<String>) to (selection.savedAtEpochMs ?: Long.MIN_VALUE)
                    }.sortedBy { it.second }
            entries.take((entries.size - MAX_SESSION_ENTRIES).coerceAtLeast(0)).forEach { prefs.remove(it.first) }
        }

        private fun SessionModelSelection.withTimestamp(): SessionModelSelection =
            if (savedAtEpochMs != null) this else copy(savedAtEpochMs = System.currentTimeMillis())

        private fun effectiveUsername(username: String): String = username.trim().ifBlank { DEFAULT_USERNAME }

        private fun normalizeBaseUrl(baseUrl: String): String {
            val trimmed = baseUrl.trim()
            return if (trimmed.endsWith('/')) trimmed else "$trimmed/"
        }

        @Serializable
        private data class StoredSessionModelSelection(
            val providerId: String,
            val modelId: String,
            val variant: String? = null,
            val agentId: String? = null,
            val savedAtEpochMs: Long? = null,
        )

        private companion object {
            const val VERSION_PREFIX = "modelSelection.v2"
            const val DEFAULT_USERNAME = "opencode"
            const val MAX_SESSION_ENTRIES = 500
        }
    }
