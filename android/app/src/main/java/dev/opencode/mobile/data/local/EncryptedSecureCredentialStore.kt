package dev.opencode.mobile.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.opencode.mobile.domain.connection.SecureCredentialStore
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

        private fun preferences(): SharedPreferences? {
            cachedPreferences?.let { return it }
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
                        "Unable to open encrypted credential store; degrading to empty",
                        error,
                    )
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
            preferences()?.edit()?.putString(passwordKey(serverBaseUrl), password)?.apply()
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
