package dev.opencode.mobile.data.local.secure

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import java.io.File

/**
 * One-time copy of values written by the deprecated `security-crypto`
 * `EncryptedSharedPreferences` into an [EncryptedValueStore] (#755), after which the legacy
 * file is deleted. This is the only remaining use of `security-crypto`; it can go once every
 * installed build has run the migration.
 */
object LegacyEncryptedPreferencesMigration {
    fun migrate(
        context: Context,
        legacyPreferencesName: String,
        into: EncryptedValueStore,
    ) {
        val legacyFile = File(File(context.applicationInfo.dataDir, "shared_prefs"), "$legacyPreferencesName.xml")
        if (!legacyFile.exists()) return
        val entries =
            runCatching {
                @Suppress("DEPRECATION")
                EncryptedSharedPreferences
                    .create(
                        context,
                        legacyPreferencesName,
                        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                    ).all
                    .mapNotNull { (key, value) -> (value as? String)?.let { key to it } }
            }.getOrElse { error ->
                DiagnosticLog.warning(DiagnosticLogArea.Connection, "legacy credential store unreadable; skipping migration", error)
                return
            }
        val allWritten = entries.all { (key, value) -> into.write(key, value) }
        if (allWritten) {
            context.deleteSharedPreferences(legacyPreferencesName)
            DiagnosticLog.info(DiagnosticLogArea.Connection, "migrated ${entries.size} legacy credential entries")
        }
    }
}
