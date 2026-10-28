package dev.opencode.mobile.ui.feedback

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.core.view.drawToBitmap
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogBuffer
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogExportWindow
import dev.opencode.mobile.domain.feedback.FeedbackAttachment
import dev.opencode.mobile.domain.feedback.FeedbackAttachmentRenderer
import dev.opencode.mobile.domain.feedback.FeedbackLogRedactor
import dev.opencode.mobile.domain.feedback.FeedbackScreenshot
import dev.opencode.mobile.network.ActiveConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

/** Screenshot JPEG rules from #721: longest side 1080 px, quality 0.7 stepping down to 1 MB. */
internal object FeedbackJpeg {
    const val MAX_SIDE_PX = 1080
    private const val START_QUALITY = 70
    private const val QUALITY_STEP = 10
    private const val MIN_QUALITY = 10

    fun downscaled(bitmap: Bitmap): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= MAX_SIDE_PX) return bitmap
        val scale = MAX_SIDE_PX.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    fun encode(bitmap: Bitmap): ByteArray {
        val scaled = downscaled(bitmap)
        var quality = START_QUALITY
        while (true) {
            val bytes =
                ByteArrayOutputStream().use { output ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)
                    output.toByteArray()
                }
            if (bytes.size <= FeedbackAttachment.MAX_BYTES || quality <= MIN_QUALITY) return bytes
            quality -= QUALITY_STEP
        }
    }
}

/** Window capture taken before the feedback sheet's overlay is added to the decor view. */
internal object FeedbackScreenCapture {
    suspend fun capture(activity: Activity): ByteArray? {
        val bitmap = captureBitmap(activity) ?: return null
        return withContext(Dispatchers.Default) { runCatching { FeedbackJpeg.encode(bitmap) }.getOrNull() }
    }

    private suspend fun captureBitmap(activity: Activity): Bitmap? {
        val decor = activity.window?.decorView ?: return null
        if (decor.width <= 0 || decor.height <= 0) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return runCatching { decor.drawToBitmap() }.getOrNull()
        }
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        return suspendCancellableCoroutine { continuation ->
            try {
                PixelCopy.request(
                    activity.window,
                    bitmap,
                    { result ->
                        if (continuation.isActive) {
                            continuation.resume(bitmap.takeIf { result == PixelCopy.SUCCESS })
                        }
                    },
                    Handler(Looper.getMainLooper()),
                )
            } catch (_: IllegalArgumentException) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }
}

/** A Photo Picker image, decoded upright and re-encoded under the screenshot rules. */
internal object FeedbackPhotoImport {
    suspend fun load(
        context: Context,
        uri: Uri,
    ): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                val bitmap = decode(context, uri) ?: return@runCatching null
                FeedbackJpeg.encode(bitmap)
            }.getOrNull()
        }

    private fun decode(
        context: Context,
        uri: Uri,
    ): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = max(info.size.width, info.size.height)
                if (longest > FeedbackJpeg.MAX_SIDE_PX) {
                    val scale = FeedbackJpeg.MAX_SIDE_PX.toFloat() / longest
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= FeedbackJpeg.MAX_SIDE_PX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }
}

class DeviceFeedbackAttachmentRenderer
    @Inject
    constructor(
        private val buffer: DiagnosticLogBuffer,
        private val activeConnectionManager: ActiveConnectionManager,
    ) : FeedbackAttachmentRenderer {
        override suspend fun screenshot(screenshot: FeedbackScreenshot): ByteArray =
            withContext(Dispatchers.Default) {
                if (screenshot.hiddenRects.isEmpty()) return@withContext screenshot.jpeg
                val decoded =
                    BitmapFactory.decodeByteArray(screenshot.jpeg, 0, screenshot.jpeg.size)
                        ?: error("Unreadable screenshot")
                val bitmap = decoded.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = Canvas(bitmap)
                val paint = Paint().apply { color = android.graphics.Color.BLACK }
                screenshot.hiddenRects.forEach { rect ->
                    canvas.drawRect(
                        rect.left * bitmap.width,
                        rect.top * bitmap.height,
                        rect.right * bitmap.width,
                        rect.bottom * bitmap.height,
                        paint,
                    )
                }
                FeedbackJpeg.encode(bitmap)
            }

        override suspend fun logs(window: DiagnosticLogExportWindow): ByteArray =
            withContext(Dispatchers.Default) {
                // Over 1 MB falls back to a shorter window.
                val windows = DiagnosticLogExportWindow.entries.filter { it.minutes <= window.minutes }.sortedByDescending { it.minutes }
                val known = knownValues()
                var gzip = ByteArray(0)
                for (candidate in windows) {
                    val jsonl = FeedbackLogRedactor.redactJsonl(buffer.exportJsonl(candidate), known)
                    gzip = gzip(jsonl.toByteArray(Charsets.UTF_8))
                    if (gzip.size <= FeedbackAttachment.MAX_BYTES) return@withContext gzip
                }
                gzip
            }

        override suspend fun logPreview(window: DiagnosticLogExportWindow): List<String> =
            withContext(Dispatchers.Default) {
                val cutoff = System.currentTimeMillis() - window.milliseconds
                val format = SimpleDateFormat("HH:mm:ss", Locale.US)
                val known = knownValues()
                buffer
                    .snapshot()
                    .entries
                    .filter { it.timestampEpochMilliseconds >= cutoff }
                    .map { entry ->
                        val time = format.format(Date(entry.timestampEpochMilliseconds))
                        val message = FeedbackLogRedactor.redact(entry.message, known)
                        "$time ${entry.level.padEnd(7)} ${entry.area.padEnd(10)} $message"
                    }
            }

        private fun knownValues(): List<String> = FeedbackLogRedactor.knownValues(activeConnectionManager.currentProfile())

        private fun gzip(bytes: ByteArray): ByteArray =
            ByteArrayOutputStream().use { output ->
                GZIPOutputStream(output).use { it.write(bytes) }
                output.toByteArray()
            }
    }
