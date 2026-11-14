package dev.opencode.hexcode.home

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

/** A prepared image's thumbnail, [side] square at most, decoded off the main thread; null until it's ready. */
@Composable
fun rememberThumbnail(
    file: File?,
    side: Dp,
): Painter? {
    val px = with(LocalDensity.current) { side.roundToPx() }
    val bitmap by produceState(file?.let { Thumbnails.cached(it, px) }, file, px) {
        if (file == null || value != null) return@produceState
        value = withContext(Dispatchers.IO) { Thumbnails.load(file, px) }
    }
    return remember(bitmap) { bitmap?.let { BitmapPainter(it) } }
}

/** Small decodes of the prepared JPEGs, kept while they're shown, so a recomposition never decodes again. */
private object Thumbnails {
    private val cache = LruCache<String, ImageBitmap>(24)

    fun cached(
        file: File,
        px: Int,
    ): ImageBitmap? = cache.get(key(file, px))

    fun load(
        file: File,
        px: Int,
    ): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return bitmap.asImageBitmap().also { cache.put(key(file, px), it) }
    }

    private fun key(
        file: File,
        px: Int,
    ) = "${file.path}@$px"
}
