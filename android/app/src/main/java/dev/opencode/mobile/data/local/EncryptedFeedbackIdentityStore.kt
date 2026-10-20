package dev.opencode.mobile.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.opencode.mobile.data.local.secure.AesGcmValueCipher
import dev.opencode.mobile.data.local.secure.EncryptedValueStore
import dev.opencode.mobile.data.local.secure.KeystoreSecretKeys
import dev.opencode.mobile.data.local.secure.LegacyEncryptedPreferencesMigration
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.feedback.FeedbackIdentityStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The feedback anonymous user's refresh token, sealed with an Android Keystore AES-GCM key
 * like the connection credentials ([EncryptedSecureCredentialStore]).
 */
@Singleton
class EncryptedFeedbackIdentityStore
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : FeedbackIdentityStore {
        @Volatile
        private var cachedStore: EncryptedValueStore? = null

        @Volatile
        private var openFailed = false

        private fun store(): EncryptedValueStore? {
            cachedStore?.let { return it }
            if (openFailed) return null
            // One open per process: concurrent first callers must not race a failure over a success.
            synchronized(this) {
                cachedStore?.let { return it }
                if (openFailed) return null
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
                    ).also { LegacyEncryptedPreferencesMigration.migrate(context, LEGACY_PREFS_NAME, it) }
                }.getOrElse { error ->
                    DiagnosticLog.warning(
                        DiagnosticLogArea.Connection,
                        "Unable to open the Keystore feedback identity store",
                        error,
                    )
                    openFailed = true
                    null
                }
            cachedStore = built
            return built
        }

        override fun readRefreshToken(): String? = store()?.read(KEY_REFRESH_TOKEN)

        override fun writeRefreshToken(token: String?) {
            val store = store() ?: return
            if (token.isNullOrEmpty()) {
                store.remove(KEY_REFRESH_TOKEN)
                return
            }
            // A Keystore failure while sealing only loses the cached token; the next upload signs in again.
            runCatching { store.write(KEY_REFRESH_TOKEN, token) }
                .onFailure { DiagnosticLog.warning(DiagnosticLogArea.Connection, "Unable to seal the feedback refresh token", it) }
        }

        private companion object {
            const val PREFS_NAME = "keystore_feedback_identity"
            const val LEGACY_PREFS_NAME = "secure_feedback_identity"
            const val KEY_ALIAS = "dev.opencode.mobile.feedback.v1"
            const val KEY_REFRESH_TOKEN = "feedback.refreshToken"
        }
    }
