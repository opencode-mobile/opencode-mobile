package dev.opencode.mobile.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.opencode.mobile.domain.agent.AgentMention
import dev.opencode.mobile.domain.agent.FileMention
import dev.opencode.mobile.domain.composer.ComposerDraft
import dev.opencode.mobile.domain.composer.ComposerDraftStore
import dev.opencode.mobile.domain.model.ModelSelectionConnectionIdentity
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64
import javax.inject.Inject

class DataStoreComposerDraftStore
    internal constructor(
        private val dataStore: DataStore<Preferences>,
        private val json: Json,
    ) : ComposerDraftStore {
        @Inject
        constructor(
            @ApplicationContext context: Context,
            json: Json,
        ) : this(context.composerDraftDataStore, json)

        override suspend fun load(
            connectionIdentity: ModelSelectionConnectionIdentity,
            worktree: String,
            sessionId: String,
        ): ComposerDraft? {
            val prefs = dataStore.data.first()
            return decodeDraft(prefs[sessionKey(connectionIdentity, worktree, sessionId)])
        }

        override suspend fun save(
            connectionIdentity: ModelSelectionConnectionIdentity,
            worktree: String,
            sessionId: String,
            draft: ComposerDraft,
        ) {
            val stored = draft.withTimestamp()
            dataStore.edit { prefs ->
                saveIfNewer(prefs, sessionKey(connectionIdentity, worktree, sessionId), stored)
                evictOldestSessions(prefs, connectionIdentity)
            }
        }

        override suspend fun delete(
            connectionIdentity: ModelSelectionConnectionIdentity,
            worktree: String,
            sessionId: String,
        ) {
            dataStore.edit { prefs ->
                prefs.remove(sessionKey(connectionIdentity, worktree, sessionId))
            }
        }

        override suspend fun deleteSessionEntries(
            connectionIdentity: ModelSelectionConnectionIdentity,
            sessionId: String,
        ) {
            val sessionSuffix = "|${encode(sessionId)}"
            dataStore.edit { prefs ->
                prefs
                    .asMap()
                    .keys
                    .filter { key ->
                        key.name.startsWith(sessionPrefix(connectionIdentity)) && key.name.endsWith(sessionSuffix)
                    }.forEach { key ->
                        @Suppress("UNCHECKED_CAST")
                        prefs.remove(key as Preferences.Key<String>)
                    }
            }
        }

        override suspend fun clearConnection(connectionIdentity: ModelSelectionConnectionIdentity) {
            val identityMarker = "|${identityKey(connectionIdentity)}|"
            dataStore.edit { prefs ->
                prefs
                    .asMap()
                    .keys
                    .filter { key -> key.name.startsWith(VERSION_PREFIX) && key.name.contains(identityMarker) }
                    .forEach { key ->
                        @Suppress("UNCHECKED_CAST")
                        prefs.remove(key as Preferences.Key<String>)
                    }
            }
        }

        override suspend fun clearAll() {
            dataStore.edit { prefs ->
                prefs.clear()
            }
        }

        private fun sessionKey(
            identity: ModelSelectionConnectionIdentity,
            worktree: String,
            sessionId: String,
        ) = stringPreferencesKey("${sessionPrefix(identity)}${encode(worktree)}|${encode(sessionId)}")

        private fun sessionPrefix(identity: ModelSelectionConnectionIdentity): String = "$VERSION_PREFIX|session|${identityKey(identity)}|"

        private fun identityKey(identity: ModelSelectionConnectionIdentity): String =
            "${encode(normalizeBaseUrl(identity.serverBaseUrl))}|${encode(effectiveUsername(identity.username))}"

        private fun encode(value: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

        private fun decodeDraft(raw: String?): ComposerDraft? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                json.decodeFromString<StoredComposerDraft>(raw)
            }.getOrNull()?.toDraft()
        }

        private fun saveIfNewer(
            prefs: MutablePreferences,
            key: Preferences.Key<String>,
            draft: ComposerDraft,
        ) {
            val existing = decodeDraft(prefs[key])
            if (existing?.savedAtEpochMs != null &&
                draft.savedAtEpochMs != null &&
                existing.savedAtEpochMs > draft.savedAtEpochMs
            ) {
                return
            }
            prefs[key] = json.encodeToString(StoredComposerDraft.from(draft))
        }

        private fun evictOldestSessions(
            prefs: MutablePreferences,
            identity: ModelSelectionConnectionIdentity,
        ) {
            val entries =
                prefs
                    .asMap()
                    .filterKeys { it.name.startsWith(sessionPrefix(identity)) }
                    .mapNotNull { (key, value) ->
                        val draft = decodeDraft(value as? String) ?: return@mapNotNull null
                        @Suppress("UNCHECKED_CAST")
                        (key as Preferences.Key<String>) to (draft.savedAtEpochMs ?: Long.MIN_VALUE)
                    }.sortedBy { it.second }
            entries.take((entries.size - MAX_SESSION_ENTRIES).coerceAtLeast(0)).forEach { prefs.remove(it.first) }
        }

        private fun ComposerDraft.withTimestamp(): ComposerDraft =
            if (savedAtEpochMs != null) this else copy(savedAtEpochMs = System.currentTimeMillis())

        private fun effectiveUsername(username: String): String = username.trim().ifBlank { DEFAULT_USERNAME }

        private fun normalizeBaseUrl(baseUrl: String): String {
            val trimmed = baseUrl.trim()
            return if (trimmed.endsWith('/')) trimmed else "$trimmed/"
        }

        @Serializable
        private data class StoredAgentMention(
            val id: String,
            val name: String,
            val start: Int,
            val end: Int,
        )

        @Serializable
        private data class StoredFileMention(
            val id: String,
            val path: String,
            val start: Int,
            val end: Int,
        )

        @Serializable
        private data class StoredComposerDraft(
            val text: String,
            val agentMentions: List<StoredAgentMention> = emptyList(),
            val fileMentions: List<StoredFileMention> = emptyList(),
            val savedAtEpochMs: Long? = null,
        ) {
            fun toDraft() =
                ComposerDraft(
                    text = text,
                    agentMentions =
                        agentMentions.map {
                            AgentMention(id = it.id, name = it.name, start = it.start, end = it.end)
                        },
                    fileMentions =
                        fileMentions.map {
                            FileMention(id = it.id, path = it.path, start = it.start, end = it.end)
                        },
                    savedAtEpochMs = savedAtEpochMs,
                )

            companion object {
                fun from(draft: ComposerDraft) =
                    StoredComposerDraft(
                        text = draft.text,
                        agentMentions =
                            draft.agentMentions.map {
                                StoredAgentMention(id = it.id, name = it.name, start = it.start, end = it.end)
                            },
                        fileMentions =
                            draft.fileMentions.map {
                                StoredFileMention(id = it.id, path = it.path, start = it.start, end = it.end)
                            },
                        savedAtEpochMs = draft.savedAtEpochMs,
                    )
            }
        }

        private companion object {
            const val VERSION_PREFIX = "composerDraft.v1"
            const val DEFAULT_USERNAME = "opencode"
            const val MAX_SESSION_ENTRIES = 500
        }
    }
