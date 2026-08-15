package dev.opencode.mobile.data.local

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileCatalog
import dev.opencode.mobile.domain.connection.ConnectionProfileIdentity
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.SecureCredentialStore
import dev.opencode.mobile.domain.connection.ServerProtocol
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

class DataStoreConnectionProfileStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val json: Json,
        private val secureCredentialStore: SecureCredentialStore,
    ) : ConnectionProfileStore {
        override suspend fun activeProfile(): ConnectionProfile? {
            val catalog = migratedCatalog()
            return catalog.activeProfile()?.withPassword()
        }

        override suspend fun listProfiles(): List<ConnectionProfile> = migratedCatalog().profiles.map { it.withPassword() }

        override suspend fun saveProfile(profile: ConnectionProfile) {
            mutateCatalog { catalog -> catalog.save(profile) }
            val saved =
                migratedCatalog().profiles.firstOrNull { stored ->
                    stored.id == profile.id ||
                        ConnectionProfileIdentity.sameConnectionIdentity(
                            stored.baseUrl,
                            stored.username,
                            profile.baseUrl,
                            profile.username,
                        )
                } ?: return
            writePassword(saved, profile.password)
        }

        override suspend fun applyProfile(id: String) {
            mutateCatalog { catalog -> catalog.apply(id) }
        }

        override suspend fun removeProfile(id: String) {
            val existing = migratedCatalog().profiles.firstOrNull { it.id == id }
            mutateCatalog { catalog -> catalog.remove(id) }
            if (existing != null) {
                secureCredentialStore.deletePassword(existing.id)
                secureCredentialStore.deletePassword(existing.baseUrl)
                context.connectionProfileDataStore.edit { prefs ->
                    prefs.remove(worktreeKey(existing.id))
                    prefs.remove(stringPreferencesKey(existing.baseUrl))
                }
            }
        }

        override suspend fun addedWorktrees(serverBaseUrl: String): List<String> {
            val prefs = context.connectionProfileDataStore.data.first()
            val catalog = catalogFrom(prefs)
            val profile = resolveProfile(catalog, serverBaseUrl)
            if (profile != null) {
                val byId = decodeWorktrees(prefs[worktreeKey(profile.id)])
                if (byId.isNotEmpty()) return byId
            }
            return decodeWorktrees(prefs[stringPreferencesKey(ConnectionProfileIdentity.normalizeBaseUrl(serverBaseUrl))])
        }

        override suspend fun addWorktree(
            serverBaseUrl: String,
            worktree: String,
        ) {
            context.connectionProfileDataStore.edit { prefs ->
                val catalog = catalogFrom(prefs)
                val profile = resolveProfile(catalog, serverBaseUrl)
                val key = worktreeStorageKey(profile, serverBaseUrl)
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
                val catalog = catalogFrom(prefs)
                val profile = resolveProfile(catalog, serverBaseUrl)
                val key = worktreeStorageKey(profile, serverBaseUrl)
                val current = decodeWorktrees(prefs[key]).toMutableList()
                if (current.remove(worktree)) {
                    prefs[key] = json.encodeToString(current)
                }
            }
        }

        override suspend fun setAddedWorktrees(
            serverBaseUrl: String,
            worktrees: List<String>,
        ) {
            context.connectionProfileDataStore.edit { prefs ->
                val catalog = catalogFrom(prefs)
                val profile = resolveProfile(catalog, serverBaseUrl)
                prefs[worktreeStorageKey(profile, serverBaseUrl)] = json.encodeToString(worktrees.distinct())
            }
        }

        override suspend fun clearAll() {
            context.connectionProfileDataStore.edit { prefs ->
                prefs.clear()
            }
        }

        private suspend fun migratedCatalog(): ConnectionProfileCatalog {
            var result = ConnectionProfileCatalog()
            context.connectionProfileDataStore.edit { prefs ->
                result = migrateLocked(prefs)
            }
            return result
        }

        private suspend fun mutateCatalog(transform: (ConnectionProfileCatalog) -> ConnectionProfileCatalog) {
            context.connectionProfileDataStore.edit { prefs ->
                val current = migrateLocked(prefs)
                val next = transform(current)
                writeCatalog(prefs, next)
                copyLegacyWorktrees(prefs, current, next)
            }
        }

        private fun migrateLocked(prefs: MutablePreferences): ConnectionProfileCatalog {
            val existing = prefs[PROFILES_KEY]
            if (existing != null) {
                return catalogFrom(prefs)
            }
            val baseUrl = prefs[ACTIVE_BASE_URL_KEY]?.takeIf { it.isNotBlank() } ?: return ConnectionProfileCatalog()
            val username = prefs[ACTIVE_USERNAME_KEY]?.takeIf { it.isNotBlank() } ?: ServerConnectionDefaults.USERNAME
            val protocol = prefs[ACTIVE_PROTOCOL_KEY]
            val id = UUID.randomUUID().toString()
            val migrated =
                ConnectionProfile(
                    id = id,
                    name = ConnectionProfileIdentity.hostLabel(baseUrl),
                    baseUrl = ConnectionProfileIdentity.normalizeBaseUrl(baseUrl),
                    username = username,
                    serverProtocol = protocol?.let { wire -> ServerProtocol.entries.firstOrNull { it.wireValue == wire } },
                )
            val catalog = ConnectionProfileCatalog(profiles = listOf(migrated), activeId = id)
            val legacyWorktrees = decodeWorktrees(prefs[stringPreferencesKey(migrated.baseUrl)])
            writeCatalog(prefs, catalog)
            if (legacyWorktrees.isNotEmpty()) {
                prefs[worktreeKey(id)] = json.encodeToString(legacyWorktrees)
            }
            val password = secureCredentialStore.readPassword(migrated.baseUrl)
            if (password != null) {
                secureCredentialStore.writePassword(id, password)
                secureCredentialStore.deletePassword(migrated.baseUrl)
            }
            prefs.remove(ACTIVE_BASE_URL_KEY)
            prefs.remove(ACTIVE_USERNAME_KEY)
            prefs.remove(ACTIVE_PROTOCOL_KEY)
            return catalog
        }

        private fun catalogFrom(prefs: Preferences): ConnectionProfileCatalog {
            val raw = prefs[PROFILES_KEY] ?: return ConnectionProfileCatalog()
            val stored =
                runCatching { json.decodeFromString<StoredConnectionProfiles>(raw) }
                    .getOrDefault(StoredConnectionProfiles())
            return ConnectionProfileCatalog(
                activeId = stored.activeId,
                profiles =
                    stored.profiles.map { record ->
                        ConnectionProfile(
                            id = record.id,
                            name = record.name,
                            baseUrl = record.baseUrl,
                            username = record.username,
                            serverProtocol =
                                record.serverProtocol?.let { wire ->
                                    ServerProtocol.entries.firstOrNull { it.wireValue == wire }
                                },
                        )
                    },
            )
        }

        private fun writeCatalog(
            prefs: MutablePreferences,
            catalog: ConnectionProfileCatalog,
        ) {
            prefs[PROFILES_KEY] =
                json.encodeToString(
                    StoredConnectionProfiles(
                        activeId = catalog.activeId,
                        profiles =
                            catalog.profiles.map { profile ->
                                StoredConnectionProfile(
                                    id = profile.id,
                                    name = profile.name,
                                    baseUrl = profile.baseUrl,
                                    username = profile.username,
                                    serverProtocol = profile.serverProtocol?.wireValue,
                                )
                            },
                    ),
                )
        }

        private fun copyLegacyWorktrees(
            prefs: MutablePreferences,
            previous: ConnectionProfileCatalog,
            next: ConnectionProfileCatalog,
        ) {
            next.profiles.forEach { profile ->
                if (previous.profiles.any { it.id == profile.id }) return@forEach
                val inherited = decodeWorktrees(prefs[stringPreferencesKey(profile.baseUrl)])
                if (inherited.isNotEmpty() && decodeWorktrees(prefs[worktreeKey(profile.id)]).isEmpty()) {
                    prefs[worktreeKey(profile.id)] = json.encodeToString(inherited)
                }
            }
        }

        private fun resolveProfile(
            catalog: ConnectionProfileCatalog,
            serverBaseUrl: String,
        ): ConnectionProfile? {
            val normalized = ConnectionProfileIdentity.normalizeBaseUrl(serverBaseUrl)
            val active = catalog.activeProfile()
            if (active != null && ConnectionProfileIdentity.normalizeBaseUrl(active.baseUrl) == normalized) {
                return active
            }
            return catalog.profiles.firstOrNull {
                ConnectionProfileIdentity.normalizeBaseUrl(it.baseUrl) == normalized
            }
        }

        private fun worktreeStorageKey(
            profile: ConnectionProfile?,
            serverBaseUrl: String,
        ) = if (profile != null) {
            worktreeKey(profile.id)
        } else {
            stringPreferencesKey(ConnectionProfileIdentity.normalizeBaseUrl(serverBaseUrl))
        }

        private fun worktreeKey(profileId: String) = stringPreferencesKey("worktrees.$profileId")

        private fun decodeWorktrees(raw: String?): List<String> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
        }

        private fun ConnectionProfile.withPassword(): ConnectionProfile {
            val password =
                secureCredentialStore.readPassword(id)
                    ?: secureCredentialStore.readPassword(baseUrl)
                    ?: ""
            return copy(password = password)
        }

        private fun writePassword(
            profile: ConnectionProfile,
            password: String,
        ) {
            secureCredentialStore.writePassword(profile.id, password)
            secureCredentialStore.deletePassword(profile.baseUrl)
        }

        private companion object {
            val PROFILES_KEY = stringPreferencesKey("connection_profiles_v2")
            val ACTIVE_BASE_URL_KEY = stringPreferencesKey("active_profile_base_url")
            val ACTIVE_USERNAME_KEY = stringPreferencesKey("active_profile_username")
            val ACTIVE_PROTOCOL_KEY = stringPreferencesKey("active_profile_protocol")
        }
    }

object ServerConnectionDefaults {
    const val USERNAME = "opencode"
    const val DEFAULT_BASE_URL = "http://127.0.0.1:4096/"
}
