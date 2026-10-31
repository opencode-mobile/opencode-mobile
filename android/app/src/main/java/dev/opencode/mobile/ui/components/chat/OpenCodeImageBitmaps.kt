package dev.opencode.mobile.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Tallest an inline timeline image gets, so a phone screenshot does not take over the thread. */
val OpenCodeMessageImageMaxHeight: Dp = 220.dp

/** Longest edge a timeline image or gallery tile decodes to. */
internal const val INLINE_DECODE_MAX_DIMENSION = 1280

/** Longest edge the full-screen viewer decodes to. */
internal const val VIEWER_DECODE_MAX_DIMENSION = 2560

/**
 * Decoded timeline and viewer images, bounded by bytes so an image-heavy session cannot
 * exhaust the heap. Keyed by image id and decode size; scrolling back to a gallery paints
 * from here without decoding again.
 */
internal object OpenCodeImageBitmapCache {
    private const val MAX_BYTES = 96 * 1024 * 1024

    private val maxBytes: Int =
        (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(MAX_BYTES.toLong()).toInt().coerceAtLeast(4 * 1024 * 1024)

    private val cache =
        object : LruCache<String, Bitmap>(maxBytes) {
            override fun sizeOf(
                key: String,
                value: Bitmap,
            ): Int = value.allocationByteCount
        }

    fun get(
        id: String,
        maxDimension: Int,
    ): Bitmap? = cache.get(key(id, maxDimension))

    fun put(
        id: String,
        maxDimension: Int,
        bitmap: Bitmap,
    ) {
        cache.put(key(id, maxDimension), bitmap)
    }

    /** The largest cached decode of [id], for a first frame while a sharper one loads. */
    fun best(id: String): Bitmap? = get(id, VIEWER_DECODE_MAX_DIMENSION) ?: get(id, INLINE_DECODE_MAX_DIMENSION)

    private fun key(
        id: String,
        maxDimension: Int,
    ): String = "$maxDimension:$id"
}

/** Decodes [bytes] with power-of-two subsampling so neither edge goes far past [maxDimension]. Call off main. */
internal fun decodeScaledBitmap(
    bytes: ByteArray,
    maxDimension: Int,
): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxDimension || bounds.outHeight / (sample * 2) >= maxDimension) {
        sample *= 2
    }
    return runCatching {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}
