package dev.opencode.mobile.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.opencode.mobile.data.local.secure.AesGcmValueCipher
import dev.opencode.mobile.data.local.secure.EncryptedValueStore
import dev.opencode.mobile.data.local.secure.KeystoreSecretKeys
import dev.opencode.mobile.data.local.secure.LegacyEncryptedPreferencesMigration
import dev.opencode.mobile.domain.connection.SecureCredentialStore
import dev.opencode.mobile.domain.connection.SecureCredentialStoreException
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Server passwords sealed with an Android Keystore AES-GCM key (#755), replacing
 * `EncryptedSharedPreferences`. Values written by the old store are migrated once.
 */
@Singleton
class EncryptedSecureCredentialStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : SecureCredentialStore {
        @Volatile
        private var cachedStore: EncryptedValueStore? = null

        @Volatile
        private var openFailure: Throwable? = null

        /**
         * Null only when the Keystore key cannot be created or used. That failure is remembered
         * for the process lifetime instead of being retried on every read; writes then throw
         * [SecureCredentialStoreException] so the profile save stops and the user sees why.
         */
        private fun store(): EncryptedValueStore? {
            cachedStore?.let { return it }
            if (openFailure != null) return null
            // One open per process: concurrent first callers must not race a failure over a success.
            synchronized(this) {
                cachedStore?.let { return it }
                if (openFailure != null) return null
                return openStore()
            }
        }

        private fun openStore(): EncryptedValueStore? {
            val built =
                runCatching {
                    val key = KeystoreSecretKeys.getOrCreate(KEY_ALIAS)
                    EncryptedValueStore(
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
                        AesGcmValueCipher({ key }),
                    )
                }.getOrElse { error ->
                    DiagnosticLog.warning(
                        DiagnosticLogArea.Connection,
                        "Unable to open the Keystore credential store; writes will fail",
                        error,
                    )
                    openFailure = error
                    null
                }
            if (built != null) migrateLegacy(built)
            cachedStore = built
            return built
        }

        // Outside the open: a failure while copying legacy values keeps them in the legacy file for the
        // next launch, and must not mark a store that opened fine as unavailable.
        private fun migrateLegacy(store: EncryptedValueStore) {
            runCatching { LegacyEncryptedPreferencesMigration.migrate(context, LEGACY_PREFS_NAME, store) }
                .onFailure { DiagnosticLog.warning(DiagnosticLogArea.Connection, "Kept legacy encrypted values; copying them failed", it) }
        }

        override fun readPassword(serverBaseUrl: String): String? = store()?.read(passwordKey(serverBaseUrl))

        override fun writePassword(
            serverBaseUrl: String,
            password: String,
        ) {
            val store =
                store()
                    ?: throw SecureCredentialStoreException("keystore credential store unavailable", openFailure)
            val written =
                try {
                    store.write(passwordKey(serverBaseUrl), password)
                } catch (error: Exception) {
                    // Keystore or cipher failure while sealing: same outcome as a refused write,
                    // so saveProfile rolls the catalog back and the setup screen explains it.
                    throw SecureCredentialStoreException("keystore credential store could not seal the password", error)
                }
            if (!written) {
                throw SecureCredentialStoreException("keystore credential store rejected the write")
            }
        }

        override fun deletePassword(serverBaseUrl: String) {
            store()?.remove(passwordKey(serverBaseUrl))
        }

        override fun clearAll() {
            store()?.clear()
        }

        private fun passwordKey(serverBaseUrl: String): String = "connection.password.$serverBaseUrl"

        private companion object {
            const val PREFS_NAME = "keystore_connection_credentials"
            const val LEGACY_PREFS_NAME = "secure_connection_credentials"
            const val KEY_ALIAS = "dev.opencode.mobile.credentials.v1"
        }
    }
