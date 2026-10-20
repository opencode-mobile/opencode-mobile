package dev.opencode.mobile.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.opencode.mobile.domain.connection.SecureCredentialStore
import dev.opencode.mobile.domain.connection.SecureCredentialStoreException
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EncryptedSecureCredentialStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : SecureCredentialStore {
        @Volatile
        private var cachedPreferences: SharedPreferences? = null

        @Volatile
        private var openFailure: Throwable? = null

        /**
         * Null only when the Keystore-backed store cannot be opened. That failure is remembered
         * for the process lifetime instead of being retried on every read; writes then throw
         * [SecureCredentialStoreException] so the profile save stops and the user sees why.
         */
        private fun preferences(): SharedPreferences? {
            cachedPreferences?.let { return it }
            if (openFailure != null) return null
            // One open per process: concurrent first callers must not race a failure over a success.
            synchronized(this) {
                cachedPreferences?.let { return it }
                if (openFailure != null) return null
                return openPreferences()
            }
        }

        private fun openPreferences(): SharedPreferences? {
            val built =
                runCatching {
                    EncryptedSharedPreferences.create(
                        context,
                        PREFS_NAME,
                        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                    )
                }.getOrElse { error ->
                    DiagnosticLog.warning(
                        DiagnosticLogArea.Connection,
                        "Unable to open encrypted credential store; writes will fail",
                        error,
                    )
                    openFailure = error
                    null
                }
            cachedPreferences = built
            return built
        }

        override fun readPassword(serverBaseUrl: String): String? {
            val prefs = preferences() ?: return null
            return prefs.getString(passwordKey(serverBaseUrl), null)?.takeIf { it.isNotEmpty() }
        }

        override fun writePassword(
            serverBaseUrl: String,
            password: String,
        ) {
            val prefs =
                preferences()
                    ?: throw SecureCredentialStoreException("encrypted credential store unavailable", openFailure)
            if (!prefs.edit().putString(passwordKey(serverBaseUrl), password).commit()) {
                throw SecureCredentialStoreException("encrypted credential store rejected the write")
            }
        }

        override fun deletePassword(serverBaseUrl: String) {
            preferences()?.edit()?.remove(passwordKey(serverBaseUrl))?.apply()
        }

        override fun clearAll() {
            preferences()?.edit()?.clear()?.apply()
        }

        private fun passwordKey(serverBaseUrl: String): String = "connection.password.$serverBaseUrl"

        private companion object {
            const val PREFS_NAME = "secure_connection_credentials"
        }
    }
