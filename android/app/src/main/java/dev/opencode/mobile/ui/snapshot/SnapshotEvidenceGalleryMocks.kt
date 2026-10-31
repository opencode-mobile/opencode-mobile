package dev.opencode.mobile.ui.snapshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import dev.opencode.mobile.ui.components.INLINE_DECODE_MAX_DIMENSION
import dev.opencode.mobile.ui.components.OpenCodeGalleryImage
import dev.opencode.mobile.ui.components.OpenCodeGalleryImageLoader
import dev.opencode.mobile.ui.components.OpenCodeImageBitmapCache
import java.io.ByteArrayOutputStream

/**
 * Image seed for gallery snapshots and the Component Gallery: generated mock screens (no real
 * captures), so galleries render the same everywhere without a server. Names and order match
 * the iOS snapshot mocks.
 */
object SnapshotEvidenceGalleryMocks {
    data class Seed(
        val id: String,
        val name: String,
        val width: Int,
        val height: Int,
        val color: Int,
        /** The loader reports this image as unreadable, for the failure tile. */
        val fails: Boolean = false,
    )

    val seeds: List<Seed> =
        listOf(
            Seed("snapshot-image-1", "login-compact.png", 300, 520, 0xFF2F7D5B.toInt()),
            Seed("snapshot-image-2", "login-regular.png", 300, 520, 0xFF3B6EA8.toInt()),
            Seed("snapshot-image-3", "settings-tablet.png", 440, 330, 0xFF7A4E9A.toInt()),
            Seed("snapshot-image-4", "home-dark.png", 440, 330, 0xFFA8693B.toInt()),
            Seed("snapshot-image-5", "home-light.png", 300, 520, 0xFF8A4E4E.toInt()),
            Seed("snapshot-image-6", "chart.png", 440, 300, 0xFF4E8A8A.toInt()),
        )

    /** An image whose load fails, for the failure tile. */
    val failing: Seed = Seed("snapshot-image-missing", "after.png", 300, 520, 0xFF555555.toInt(), fails = true)

    fun images(count: Int): List<OpenCodeGalleryImage> = seeds.take(count).map { OpenCodeGalleryImage(it.id, it.name) }

    fun image(seed: Seed): OpenCodeGalleryImage = OpenCodeGalleryImage(seed.id, seed.name)

    /** Serves the seeds; [delayMs] holds every load, to show the loading tile. */
    class Loader(
        private val delayMs: Long = 0,
    ) : OpenCodeGalleryImageLoader {
        override suspend fun load(id: String): ByteArray? {
            if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
            val seed = (seeds + failing).firstOrNull { it.id == id } ?: return null
            if (seed.fails) return null
            return png(seed)
        }
    }

    /** Decodes every seed into the tile cache, so a snapshot paints them on its first frame. */
    fun warmTiles() {
        seeds.forEach { seed -> OpenCodeImageBitmapCache.put(seed.id, INLINE_DECODE_MAX_DIMENSION, bitmap(seed)) }
    }

    fun png(seed: Seed): ByteArray =
        ByteArrayOutputStream().also { bitmap(seed).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    /** A flat mock screen: a header bar, three text lines and a button, on the seed colour. */
    private fun bitmap(seed: Seed): Bitmap {
        val bitmap = createBitmap(seed.width, seed.height)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        Canvas(bitmap).apply {
            drawColor(seed.color)
            paint.color = 0x55FFFFFF
            val unit = seed.width / 10f
            drawRoundRect(RectF(unit, unit, seed.width - unit, unit * 2.2f), 12f, 12f, paint)
            paint.color = 0x33FFFFFF
            for (row in 0 until 3) {
                val top = unit * (3.2f + row * 1.4f)
                drawRoundRect(RectF(unit, top, seed.width - unit * (2 + row), top + unit * 0.6f), 8f, 8f, paint)
            }
            paint.color = 0x88FFFFFF.toInt()
            drawRoundRect(RectF(unit, seed.height - unit * 2.4f, seed.width - unit, seed.height - unit), 16f, 16f, paint)
        }
        return bitmap
    }
}
