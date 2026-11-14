package dev.opencode.mobile.data.local.secure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class AesGcmValueCipherTest {
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = AesGcmValueCipher({ key })

    @Test
    fun roundTripsAndUsesAFreshIvPerValue() {
        val first = cipher.encrypt("hunter2")
        val second = cipher.encrypt("hunter2")
        assertNotEquals(first, second)
        assertEquals("hunter2", cipher.decrypt(first))
        assertEquals("hunter2", cipher.decrypt(second))
    }

    @Test
    fun tamperedOrForeignValuesDecryptToNull() {
        val sealed = cipher.encrypt("hunter2")
        val tampered = sealed.dropLast(2) + "AA"
        assertNull(cipher.decrypt(tampered))
        assertNull(cipher.decrypt("not base64!"))
        assertNull(cipher.decrypt(""))
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNull(AesGcmValueCipher({ otherKey }).decrypt(sealed))
    }
}
