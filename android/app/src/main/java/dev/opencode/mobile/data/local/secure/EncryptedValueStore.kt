package dev.opencode.mobile.data.local.secure

import android.content.SharedPreferences
import java.security.MessageDigest

/**
 * Plain [SharedPreferences] whose keys are hashed and whose values are sealed with
 * [AesGcmValueCipher] (#755). Replaces `EncryptedSharedPreferences` from the deprecated
 * `security-crypto` artifact with the same shape: string values by key.
 */
class EncryptedValueStore(
    private val preferences: SharedPreferences,
    private val cipher: AesGcmValueCipher,
) {
    fun read(key: String): String? = preferences.getString(hashed(key), null)?.let(cipher::decrypt)?.takeIf { it.isNotEmpty() }

    /** False when the preferences file refused the write. */
    fun write(
        key: String,
        value: String,
    ): Boolean = preferences.edit().putString(hashed(key), cipher.encrypt(value)).commit()

    fun remove(key: String) {
        preferences.edit().remove(hashed(key)).apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun hashed(key: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
