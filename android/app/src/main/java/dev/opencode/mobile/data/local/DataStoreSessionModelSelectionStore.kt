package dev.opencode.mobile.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.opencode.mobile.domain.model.SessionModelSelection
import dev.opencode.mobile.domain.model.SessionModelSelectionStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

class DataStoreSessionModelSelectionStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val json: Json,
    ) : SessionModelSelectionStore {
        override suspend fun load(
            serverBaseUrl: String,
            worktree: String,
            sessionId: String,
        ): SessionModelSelection? {
            val prefs = context.sessionModelSelectionDataStore.data.first()
            return decodeSelection(prefs[cacheKey(serverBaseUrl, worktree, sessionId)])
        }

        override suspend fun save(
            serverBaseUrl: String,
            worktree: String,
            sessionId: String,
            selection: SessionModelSelection,
        ) {
            context.sessionModelSelectionDataStore.edit { prefs ->
                prefs[cacheKey(serverBaseUrl, worktree, sessionId)] =
                    json.encodeToString(
                        StoredSessionModelSelection(
                            providerId = selection.providerId,
                            modelId = selection.modelId,
                            variant = selection.variant,
                        ),
                    )
            }
        }

        override suspend fun clearAll() {
            context.sessionModelSelectionDataStore.edit { prefs ->
                prefs.clear()
            }
        }

        private fun cacheKey(
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

        private fun decodeSelection(raw: String?): SessionModelSelection? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                json.decodeFromString<StoredSessionModelSelection>(raw)
            }.getOrNull()?.let {
                SessionModelSelection(
                    providerId = it.providerId,
                    modelId = it.modelId,
                    variant = it.variant,
                )
            }
        }

        private fun normalizeBaseUrl(baseUrl: String): String {
            val trimmed = baseUrl.trim()
            return if (trimmed.endsWith('/')) trimmed else "$trimmed/"
        }

        @Serializable
        private data class StoredSessionModelSelection(
            val providerId: String,
            val modelId: String,
            val variant: String? = null,
        )
    }
