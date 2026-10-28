package dev.opencode.mobile.domain.message

import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * Client-made user message ids, built like opencode's own ascending ids so v1 and v2
 * accept them: `msg_`, 12 hex characters for the low 48 bits of `epochMillis * 4096 +
 * counter`, then 14 base62 characters. A prompt sent with one comes back under the same id, so the
 * optimistic row retires on its echo however the server reshapes the text.
 */
object ClientMessageId {
    private const val PREFIX = "msg_"
    private const val RANDOM_LENGTH = 14
    private const val TIME_MASK = 0xFFFF_FFFF_FFFFL
    private const val BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    private val secureRandom = SecureRandom().asKotlinRandom()
    private var lastTimestamp = 0L
    private var counter = 0L

    @Synchronized
    fun next(
        nowMs: Long = System.currentTimeMillis(),
        random: Random = secureRandom,
    ): String {
        if (nowMs != lastTimestamp) {
            lastTimestamp = nowMs
            counter = 0
        }
        counter++
        val time = ((nowMs * 0x1000L + counter) and TIME_MASK).toString(16).padStart(12, '0')
        val suffix = buildString { repeat(RANDOM_LENGTH) { append(BASE62[random.nextInt(BASE62.length)]) } }
        return PREFIX + time + suffix
    }

    /** True for an optimistic row whose send carried its id, so only that id retires it. */
    fun isClientId(id: String): Boolean = id.startsWith(PREFIX)
}
