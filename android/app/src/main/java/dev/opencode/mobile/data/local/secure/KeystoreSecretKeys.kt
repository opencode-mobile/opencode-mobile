package dev.opencode.mobile.data.local.secure

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * AES-256 keys that never leave the Android Keystore (#755). StrongBox is requested first and
 * falls back to the TEE when the device has none. No user-authentication binding: the stores
 * are written while the user is in the app and read at launch before any unlock could happen.
 */
object KeystoreSecretKeys {
    private const val PROVIDER = "AndroidKeyStore"

    fun getOrCreate(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return try {
            generate(alias, strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        } catch (_: StrongBoxUnavailableException) {
            generate(alias, strongBox = false)
        }
    }

    private fun generate(
        alias: String,
        strongBox: Boolean,
    ): SecretKey {
        val spec =
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
                .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply { init(spec) }.generateKey()
    }
}
