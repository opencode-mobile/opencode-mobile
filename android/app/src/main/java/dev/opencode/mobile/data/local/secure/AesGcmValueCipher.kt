package dev.opencode.mobile.data.local.secure

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * AES-256-GCM for individual preference values (#755). The cipher picks a fresh IV for every
 * value (Keystore keys refuse a caller-provided IV); the stored form is
 * `base64(iv || ciphertext || tag)`. The key comes from [keyProvider] so the Android Keystore
 * key and a plain test key share one code path.
 */
@OptIn(ExperimentalEncodingApi::class)
class AesGcmValueCipher(
    private val keyProvider: () -> SecretKey,
) {
    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        val iv = cipher.iv
        require(iv != null && iv.size == IV_BYTES) { "unexpected GCM IV length ${iv?.size}" }
        val sealed = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encode(iv + sealed)
    }

    /** Null when the value is not in the expected form or does not authenticate. */
    fun decrypt(stored: String): String? {
        val bytes = runCatching { Base64.decode(stored) }.getOrNull() ?: return null
        if (bytes.size <= IV_BYTES) return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_BYTES)))
            String(cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size)), Charsets.UTF_8)
        }.getOrNull()
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
