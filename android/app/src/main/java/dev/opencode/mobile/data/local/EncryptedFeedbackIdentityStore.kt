package dev.opencode.mobile.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.feedback.FeedbackIdentityStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The feedback anonymous user's refresh token, in Keystore-backed encrypted preferences
 * like the connection credentials ([EncryptedSecureCredentialStore]).
 */
@Singleton
class EncryptedFeedbackIdentityStore
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : FeedbackIdentityStore {
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
                        "Unable to open encrypted feedback identity store",
                        error,
                    )
                    null
                }
            cachedPreferences = built
            return built
        }

        override fun readRefreshToken(): String? = preferences()?.getString(KEY_REFRESH_TOKEN, null)?.takeIf { it.isNotEmpty() }

        override fun writeRefreshToken(token: String?) {
            val editor = preferences()?.edit() ?: return
            if (token.isNullOrEmpty()) editor.remove(KEY_REFRESH_TOKEN) else editor.putString(KEY_REFRESH_TOKEN, token)
            editor.apply()
        }

        private companion object {
            const val PREFS_NAME = "secure_feedback_identity"
            const val KEY_REFRESH_TOKEN = "feedback.refreshToken"
        }
    }
