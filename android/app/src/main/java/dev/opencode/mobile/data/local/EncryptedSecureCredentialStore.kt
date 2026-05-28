package dev.opencode.mobile.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.opencode.mobile.domain.connection.SecureCredentialStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EncryptedSecureCredentialStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : SecureCredentialStore {
        private val preferences: SharedPreferences =
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )

        override fun readPassword(serverBaseUrl: String): String? {
            val value = preferences.getString(passwordKey(serverBaseUrl), null)
            return value?.takeIf { it.isNotEmpty() }
        }

        override fun writePassword(
            serverBaseUrl: String,
            password: String,
        ) {
            preferences.edit().putString(passwordKey(serverBaseUrl), password).apply()
        }

        override fun deletePassword(serverBaseUrl: String) {
            preferences.edit().remove(passwordKey(serverBaseUrl)).apply()
        }

        private fun passwordKey(serverBaseUrl: String): String = "connection.password.$serverBaseUrl"

        private companion object {
            const val PREFS_NAME = "secure_connection_credentials"
        }
    }
