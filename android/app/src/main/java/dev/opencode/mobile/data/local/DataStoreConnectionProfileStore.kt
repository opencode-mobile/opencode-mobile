package dev.opencode.mobile.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.SecureCredentialStore
import dev.opencode.mobile.domain.connection.ServerProtocol
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

class DataStoreConnectionProfileStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val json: Json,
        private val secureCredentialStore: SecureCredentialStore,
    ) : ConnectionProfileStore {
        override suspend fun activeProfile(): ConnectionProfile? {
            val prefs = context.connectionProfileDataStore.data.first()
            val baseUrl = prefs[ACTIVE_BASE_URL_KEY]?.takeIf { it.isNotBlank() } ?: return null
            val username = prefs[ACTIVE_USERNAME_KEY]?.takeIf { it.isNotBlank() } ?: ServerConnectionDefaults.USERNAME
            val password = secureCredentialStore.readPassword(baseUrl).orEmpty()
            val serverProtocol =
                prefs[ACTIVE_PROTOCOL_KEY]?.let { wire ->
                    ServerProtocol.entries.firstOrNull { it.wireValue == wire }
                }
            return ConnectionProfile(
                baseUrl = baseUrl,
                username = username,
                password = password,
                serverProtocol = serverProtocol,
            )
        }

        override suspend fun saveProfile(profile: ConnectionProfile) {
            val normalized = normalizeBaseUrl(profile.baseUrl)
            context.connectionProfileDataStore.edit { prefs ->
                prefs[ACTIVE_BASE_URL_KEY] = normalized
                prefs[ACTIVE_USERNAME_KEY] = profile.username.ifBlank { ServerConnectionDefaults.USERNAME }
                val protocol = profile.serverProtocol
                if (protocol != null) {
                    prefs[ACTIVE_PROTOCOL_KEY] = protocol.wireValue
                } else {
                    prefs.remove(ACTIVE_PROTOCOL_KEY)
                }
            }
            secureCredentialStore.writePassword(normalized, profile.password)
        }

        override suspend fun addedWorktrees(serverBaseUrl: String): List<String> {
            val prefs = context.connectionProfileDataStore.data.first()
            return decodeWorktrees(prefs[stringPreferencesKey(normalizeBaseUrl(serverBaseUrl))])
        }

        override suspend fun addWorktree(
            serverBaseUrl: String,
            worktree: String,
        ) {
            context.connectionProfileDataStore.edit { prefs ->
                val key = stringPreferencesKey(normalizeBaseUrl(serverBaseUrl))
                val current = decodeWorktrees(prefs[key]).toMutableList()
                if (worktree !in current) {
                    current.add(worktree)
                    prefs[key] = json.encodeToString(current)
                }
            }
        }

        override suspend fun removeWorktree(
            serverBaseUrl: String,
            worktree: String,
        ) {
            context.connectionProfileDataStore.edit { prefs ->
                val key = stringPreferencesKey(normalizeBaseUrl(serverBaseUrl))
                val current = decodeWorktrees(prefs[key]).toMutableList()
                if (current.remove(worktree)) {
                    prefs[key] = json.encodeToString(current)
                }
            }
        }

        private fun decodeWorktrees(raw: String?): List<String> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
        }

        override suspend fun setAddedWorktrees(
            serverBaseUrl: String,
            worktrees: List<String>,
        ) {
            context.connectionProfileDataStore.edit { prefs ->
                prefs[stringPreferencesKey(normalizeBaseUrl(serverBaseUrl))] = json.encodeToString(worktrees.distinct())
            }
        }

        override suspend fun clearAll() {
            context.connectionProfileDataStore.edit { prefs ->
                prefs.clear()
            }
        }

        private fun normalizeBaseUrl(baseUrl: String): String {
            val trimmed = baseUrl.trim()
            return if (trimmed.endsWith('/')) trimmed else "$trimmed/"
        }

        private companion object {
            val ACTIVE_BASE_URL_KEY = stringPreferencesKey("active_profile_base_url")
            val ACTIVE_USERNAME_KEY = stringPreferencesKey("active_profile_username")
            val ACTIVE_PROTOCOL_KEY = stringPreferencesKey("active_profile_protocol")
        }
    }

object ServerConnectionDefaults {
    const val USERNAME = "opencode"
    const val DEFAULT_BASE_URL = "http://127.0.0.1:4096/"
}
