package dev.opencode.hexcode.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * app-plan.md, Attach › Preparing, through Android's real image code (Robolectric's native graphics): on API 27
 * (`BitmapFactory` and `ExifInterface`) and API 35 (`ImageDecoder`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [27, 35])
class ImagePrepTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun preparer() = AndroidImagePreparer(context, folder.newFolder("attach"), decodeFromMemory = true)

    /** A [width] × [height] image, grey with a red square in its stored top left corner. */
    private fun picture(
        width: Int,
        height: Int,
    ): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            canvas.drawColor(Color.GRAY)
            canvas.drawRect(0f, 0f, width / 5f, height / 5f, Paint().apply { color = Color.RED })
        }

    private fun save(
        bitmap: Bitmap,
        name: String,
        format: Bitmap.CompressFormat,
    ): File = File(folder.root, name).apply { outputStream().use { bitmap.compress(format, 95, it) } }

    private fun isRed(color: Int) = Color.red(color) > 180 && Color.green(color) < 90 && Color.blue(color) < 90

    /** The JPEG's marker segments before the image data: APP1 holds EXIF or XMP, APP13 IPTC, APP2 ICC. */
    private fun segments(jpeg: ByteArray): List<Int> {
        val markers = mutableListOf<Int>()
        var at = 2
        while (at + 4 <= jpeg.size && jpeg[at] == 0xFF.toByte()) {
            val marker = jpeg[at + 1].toInt() and 0xFF
            if (marker == 0xDA) break
            markers += marker
            at += 2 + (((jpeg[at + 2].toInt() and 0xFF) shl 8) or (jpeg[at + 3].toInt() and 0xFF))
        }
        return markers
    }

    @Test
    fun aRotatedPhotoComesOutUprightAt2000AsJpegWithNoMetadata() =
        runBlocking {
            // A 4000 × 3000 camera photo stored sideways (EXIF orientation 6, turn 90° clockwise), with GPS.
            val photo = save(picture(4000, 3000), "PXL_20261106.jpg", Bitmap.CompressFormat.JPEG)
            ExifInterface(photo.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setLatLong(52.37, 4.89)
                setAttribute(ExifInterface.TAG_MAKE, "Google")
                saveAttributes()
            }

            val prepared = preparer().prepare(Uri.fromFile(photo).toString())

            assertEquals(ImageKind.Photo, prepared.kind)
            assertEquals(1500 to 2000, prepared.width to prepared.height)
            val bytes = prepared.file.readBytes()
            assertEquals("JPEG", 0xFFD8, ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF))
            val markers = segments(bytes)
            assertTrue("no EXIF, XMP or IPTC: $markers", markers.none { it == 0xE1 || it == 0xED })
            val exif = ExifInterface(prepared.file.path)
            assertNull(exif.latLong)
            assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
            val decoded = BitmapFactory.decodeFile(prepared.file.path)
            assertEquals(1500 to 2000, decoded.width to decoded.height)
            // The stored top left is the upright top right after a quarter turn clockwise.
            assertTrue("red top right", isRed(decoded.getPixel(1500 - 20, 20)))
            assertTrue("grey top left", !isRed(decoded.getPixel(20, 20)))
            assertTrue("sRGB", decoded.colorSpace?.isSrgb != false)
        }

    @Test
    fun aScreenshotBecomesAJpegScaledTo2000() =
        runBlocking {
            val shot = save(picture(1080, 2424), "Screenshot_20261106-101500.png", Bitmap.CompressFormat.PNG)

            val prepared = preparer().prepare(Uri.fromFile(shot).toString())

            assertEquals(ImageKind.Screenshot, prepared.kind)
            assertEquals(891 to 2000, prepared.width to prepared.height)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(prepared.file.path, options)
            assertEquals("image/jpeg", options.outMimeType)
            assertTrue(isRed(BitmapFactory.decodeFile(prepared.file.path).getPixel(10, 10)))
        }

    @Test
    fun aSmallImageIsNeverScaledUp() =
        runBlocking {
            val small = save(picture(640, 480), "small.jpg", Bitmap.CompressFormat.JPEG)
            val prepared = preparer().prepare(Uri.fromFile(small).toString())
            assertEquals(640 to 480, prepared.width to prepared.height)
        }

    @Test
    fun aFileThatIsNotAnImageFails() =
        runBlocking {
            val text = File(folder.root, "notes.jpg").apply { writeText("not an image") }
            try {
                preparer().prepare(Uri.fromFile(text).toString())
                fail("prepared a text file")
            } catch (_: Exception) {
                // "Couldn't add this image"
            }
        }

    @Test
    fun keepOnlyDeletesTheOthers() =
        runBlocking {
            val images = preparer()
            val a = images.prepare(Uri.fromFile(save(picture(100, 100), "a.jpg", Bitmap.CompressFormat.JPEG)).toString())
            val b = images.prepare(Uri.fromFile(save(picture(100, 100), "b.jpg", Bitmap.CompressFormat.JPEG)).toString())
            images.keepOnly(setOf(b.file))
            assertTrue(!a.file.exists())
            assertTrue(b.file.exists())
        }
}
