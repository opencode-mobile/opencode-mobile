package dev.opencode.hexcode.home

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** The size rules of app-plan.md, Attach › Preparing. */
object ImageFit {
    /** The longest side sent, the server's own limit, so it keeps the image as it is. */
    const val MAX_SIDE = 2_000

    /** JPEG quality, the server's first choice. */
    const val QUALITY = 80

    /** A prepared image over this is refused (it shouldn't happen), far below the server's 20 MiB. */
    const val MAX_BYTES = 5 * 1024 * 1024

    /** [width] × [height] scaled down, never up, so the longest side is at most [max]. */
    fun size(
        width: Int,
        height: Int,
        max: Int = MAX_SIDE,
    ): Pair<Int, Int> {
        val longest = max(width, height)
        if (longest <= max) return width to height
        val scale = max.toDouble() / longest
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }

    /** The largest power of two that decodes [width] × [height] still at least [max] on its longest side. */
    fun sampleSize(
        width: Int,
        height: Int,
        max: Int = MAX_SIDE,
    ): Int {
        var sample = 1
        while (max(width, height) / (sample * 2) >= max) sample *= 2
        return sample
    }

    /** What an EXIF orientation (1 to 8) asks for: a turn clockwise, then a mirror left to right. */
    data class Upright(
        val degrees: Int,
        val mirror: Boolean,
    ) {
        /** Width and height swap for a quarter turn. */
        val swaps: Boolean get() = degrees % 180 != 0
    }

    fun upright(orientation: Int): Upright =
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Upright(0, true)
            ExifInterface.ORIENTATION_ROTATE_180 -> Upright(180, false)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> Upright(180, true)
            ExifInterface.ORIENTATION_TRANSPOSE -> Upright(90, true)
            ExifInterface.ORIENTATION_ROTATE_90 -> Upright(90, false)
            ExifInterface.ORIENTATION_TRANSVERSE -> Upright(270, true)
            ExifInterface.ORIENTATION_ROTATE_270 -> Upright(270, false)
            else -> Upright(0, false)
        }

    /**
     * A picked PNG is a screenshot: that's how phones save them, while cameras write JPEG or HEIC. (The Photo Picker
     * hides the file's own name, `Screenshot_….png` becoming `40.png`.) Anything else, and anything from the camera,
     * is a photo. [mime] is the type the decoder found in the image itself.
     */
    fun kindOf(
        mime: String?,
        fromCamera: Boolean = false,
    ): ImageKind = if (!fromCamera && mime == "image/png") ImageKind.Screenshot else ImageKind.Photo
}

/**
 * app-plan.md, Attach › Preparing, on Android: decode the picked image (HEIC too), turn it upright from its EXIF
 * orientation, scale its longest side to at most 2000 px, and encode it as JPEG quality 80 in sRGB. Re-encoding keeps
 * no metadata (no EXIF, GPS or camera). The JPEG goes into [dir] in the app's cache until [keepOnly] lets it go.
 *
 * API 28 and later use `ImageDecoder`, which reads HEIF and applies the orientation itself; API 26 and 27 use
 * `BitmapFactory` with `ExifInterface` (phones that old don't take HEIC photos).
 */
class AndroidImagePreparer(
    context: Context,
    private val dir: File = attachDir(context),
) : ImagePreparer {
    private val resolver: ContentResolver = context.contentResolver
    private val cameraDir = cameraDir(context)

    override suspend fun prepare(source: String): PreparedImage =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(source)
            val (bitmap, mime) = if (Build.VERSION.SDK_INT >= 28) decodeWithImageDecoder(uri) else decodeWithBitmapFactory(uri)
            val kind = ImageFit.kindOf(mime, fromCamera = cameraFile(uri) != null)
            try {
                dir.mkdirs()
                val out = File(dir, "${UUID.randomUUID()}.jpg")
                val written = out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, ImageFit.QUALITY, it) }
                if (!written || out.length() == 0L) throw IOException("JPEG encoding failed").also { out.delete() }
                if (out.length() > ImageFit.MAX_BYTES) throw IOException("${out.length()} bytes after encoding").also { out.delete() }
                Log.i(TAG, "prepared $kind ${bitmap.width}×${bitmap.height}, ${out.length() / 1024} KB")
                PreparedImage(out, kind, bitmap.width, bitmap.height)
            } finally {
                bitmap.recycle()
                // A photo the camera took for this draft has done its job once it's prepared.
                cameraFile(uri)?.delete()
            }
        }

    override suspend fun keepOnly(files: Set<File>) =
        withContext(Dispatchers.IO) {
            val keep = files.map { it.absoluteFile }.toSet()
            dir.listFiles()?.filter { it.absoluteFile !in keep }?.forEach { it.delete() }
            Unit
        }

    /** The image upright and scaled, with the type found in its own bytes (`image/png`, `image/heif`). */
    private fun decodeWithImageDecoder(uri: Uri): Pair<Bitmap, String?> {
        val source = ImageDecoder.createSource(resolver, uri)
        var mime: String? = null
        val bitmap =
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                mime = info.mimeType
                // Software, 8-bit sRGB: what the JPEG encoder takes, whatever the photo's colour space (Display P3, HDR).
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                val (width, height) = ImageFit.size(info.size.width, info.size.height)
                if (width != info.size.width) decoder.setTargetSize(width, height)
            }
        return bitmap to mime
    }

    private fun decodeWithBitmapFactory(uri: Uri): Pair<Bitmap, String?> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Not an image")
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = ImageFit.sampleSize(bounds.outWidth, bounds.outHeight)
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            }
        val decoded = open(uri).use { BitmapFactory.decodeStream(it, null, options) } ?: throw IOException("Couldn't decode")
        val orientation =
            runCatching {
                open(uri).use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        return upright(decoded, ImageFit.upright(orientation)) to bounds.outMimeType
    }

    private fun open(uri: Uri) = resolver.openInputStream(uri) ?: throw IOException("Can't open $uri")

    /** The camera's own picture behind [uri], when Take photo wrote it. */
    private fun cameraFile(uri: Uri): File? {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority != cameraAuthority) return null
        return File(cameraDir, uri.lastPathSegment ?: return null)
    }

    private val cameraAuthority = authority(context)

    companion object {
        private const val TAG = "HexCode.Attach"

        /** Where prepared images wait to be sent. */
        fun attachDir(context: Context) = File(context.cacheDir, "attach")

        /** Where Take photo writes the camera's picture. */
        fun cameraDir(context: Context) = File(context.cacheDir, "attach-camera")

        /** A new file for the camera to write, as a `content://` URI it can open, under this app's file provider. */
        fun cameraTarget(context: Context): Uri {
            val dir = cameraDir(context).apply { mkdirs() }
            val file = File(dir, "${UUID.randomUUID()}.jpg")
            return FileProvider.getUriForFile(context, authority(context), file)
        }

        /** The file provider in the manifest that hands the camera its file. */
        private fun authority(context: Context) = "${context.packageName}.attach"
    }
}

/** [bitmap] scaled so its longest side is at most [ImageFit.MAX_SIDE] and turned [upright]; recycles [bitmap]. */
internal fun upright(
    bitmap: Bitmap,
    upright: ImageFit.Upright,
): Bitmap {
    val (width, height) = ImageFit.size(bitmap.width, bitmap.height)
    if (upright.degrees == 0 && !upright.mirror && width == bitmap.width) return bitmap
    val matrix =
        Matrix().apply {
            postScale(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
            if (upright.degrees != 0) postRotate(upright.degrees.toFloat())
            if (upright.mirror) postScale(-1f, 1f)
        }
    val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (turned !== bitmap) bitmap.recycle()
    return turned
}
