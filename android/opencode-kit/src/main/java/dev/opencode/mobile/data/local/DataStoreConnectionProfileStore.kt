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
import dev.opencode.mobile.domain.connection.SecureCredentialStoreException
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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
        override fun configurationRevisions(): Flow<Unit> = context.connectionProfileDataStore.data.map { }

        override suspend fun activeProfile(): ConnectionProfile? {
            val active = migratedCatalog().activeProfile() ?: return null
            return withPasswords(listOf(active)).single()
        }

        override suspend fun listProfiles(): List<ConnectionProfile> = withPasswords(migratedCatalog().profiles)

        override suspend fun catalogWithoutPasswords(): ConnectionProfileCatalog = migratedCatalog()

        override suspend fun saveProfile(profile: ConnectionProfile) {
            val previous = migratedCatalog()
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
            try {
                withContext(Dispatchers.IO) { writePassword(saved, profile.password) }
            } catch (error: SecureCredentialStoreException) {
                // No password, no profile: put the catalog back the way it was.
                mutateCatalog { previous }
                throw error
            }
        }

        override suspend fun updateStoredProfile(
            id: String,
            transform: (ConnectionProfile) -> ConnectionProfile,
        ): Boolean {
            var updated = false
            // One edit, so a removal cannot land between the check and the write. The catalog
            // holds no passwords, and this path never touches the secure store.
            mutateCatalog { catalog ->
                val stored = catalog.profiles.firstOrNull { it.id == id } ?: return@mutateCatalog catalog
                updated = true
                catalog.save(transform(stored).copy(id = id))
            }
            return updated
        }

        override suspend fun applyProfile(id: String) {
            mutateCatalog { catalog -> catalog.apply(id) }
        }

        override suspend fun removeProfile(id: String) {
            val existing = migratedCatalog().profiles.firstOrNull { it.id == id }
            mutateCatalog { catalog -> catalog.remove(id) }
            if (existing != null) {
                withContext(Dispatchers.IO) {
                    secureCredentialStore.deletePassword(existing.id)
                    secureCredentialStore.deletePassword(existing.baseUrl)
                }
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
                val raw = prefs[worktreeKey(profile.id)]
                if (raw != null) return decodeWorktrees(raw)
            }
            return decodeWorktrees(prefs[legacyWorktreeKey(serverBaseUrl)])
        }

        override suspend fun addedWorktreesForProfile(
            profileId: String,
            serverBaseUrl: String,
        ): List<String> {
            val prefs = context.connectionProfileDataStore.data.first()
            prefs[worktreeKey(profileId)]?.let { return decodeWorktrees(it) }
            return decodeWorktrees(prefs[legacyWorktreeKey(serverBaseUrl)])
        }

        override suspend fun addWorktree(
            serverBaseUrl: String,
            worktree: String,
        ) {
            val current = addedWorktrees(serverBaseUrl)
            if (worktree in current) return
            setAddedWorktrees(serverBaseUrl, current + worktree)
        }

        override suspend fun removeWorktree(
            serverBaseUrl: String,
            worktree: String,
        ) {
            val current = addedWorktrees(serverBaseUrl)
            if (worktree !in current) return
            setAddedWorktrees(serverBaseUrl, current.filterNot { it == worktree })
        }

        override suspend fun setAddedWorktrees(
            serverBaseUrl: String,
            worktrees: List<String>,
        ) {
            context.connectionProfileDataStore.edit { prefs ->
                val catalog = catalogFrom(prefs)
                val profile = resolveProfile(catalog, serverBaseUrl)
                prefs[worktreeStorageKey(profile, serverBaseUrl)] = json.encodeToString(worktrees.distinct())
                if (profile != null) {
                    prefs.remove(legacyWorktreeKey(serverBaseUrl))
                    prefs.remove(legacyWorktreeKey(profile.baseUrl))
                }
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
                // Legacy key migration: if the new key cannot be written, keep the old one so the
                // password is not lost (reads fall back to it); the next save surfaces the failure.
                try {
                    secureCredentialStore.writePassword(id, password)
                    secureCredentialStore.deletePassword(migrated.baseUrl)
                } catch (error: SecureCredentialStoreException) {
                    DiagnosticLog.warning(
                        DiagnosticLogArea.Connection,
                        "Kept the legacy password key; the encrypted store rejected the migrated key",
                        error,
                    )
                }
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
                            serverUrls = record.serverUrls,
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
                                    serverUrls = profile.serverUrls,
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
                val inherited =
                    decodeWorktrees(prefs[legacyWorktreeKey(profile.baseUrl)])
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
            if (active != null && normalized in ConnectionProfileIdentity.addressesOf(active)) {
                return active
            }
            return catalog.profiles.firstOrNull {
                normalized in ConnectionProfileIdentity.addressesOf(it)
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

        private fun legacyWorktreeKey(serverBaseUrl: String) =
            stringPreferencesKey(ConnectionProfileIdentity.normalizeBaseUrl(serverBaseUrl))

        private fun decodeWorktrees(raw: String?): List<String> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
        }

        // Each read is an Android Keystore decrypt, several binder calls to keystore2 (tens of ms per
        // profile on a Pixel 9). Keep them off the caller's thread, which is often Main.
        private suspend fun withPasswords(profiles: List<ConnectionProfile>): List<ConnectionProfile> =
            if (profiles.isEmpty()) profiles else withContext(Dispatchers.IO) { profiles.map { it.withPassword() } }

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
