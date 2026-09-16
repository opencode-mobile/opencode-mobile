package dev.opencode.mobile.domain.message

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentIngestionServiceTest {
    private val thumbnail = byteArrayOf(9, 8, 7)
    private val service = AttachmentIngestionService { thumbnail }

    @Test
    fun ingest_png_createsImageAttachment() = assertImage("photo.png", "image/png")

    @Test
    fun ingest_jpeg_createsImageAttachment() = assertImage("photo.jpeg", "image/jpeg")

    @Test
    fun ingest_gif_createsImageAttachment() = assertImage("animation.gif", "image/gif")

    @Test
    fun ingest_webp_createsImageAttachment() = assertImage("photo.webp", "image/webp")

    @Test
    fun ingest_pdf_createsPdfWithoutPreview() {
        val attachment = service.ingest("brief.pdf", "application/pdf", byteArrayOf(1, 2, 3))

        assertEquals(AttachmentKind.Pdf, attachment.kind)
        assertEquals("application/pdf", attachment.mime)
        assertNull(attachment.previewBytes)
    }

    @Test
    fun ingest_textLikeExtensions_normalizeToPlainText() {
        listOf("json", "xml", "yaml", "kt").forEach { extension ->
            val attachment = service.ingest("source.$extension", "application/octet-stream", byteArrayOf(1, 2, 3))

            assertEquals(AttachmentKind.Text, attachment.kind)
            assertEquals("text/plain", attachment.mime)
            assertNull(attachment.previewBytes)
        }
    }

    @Test
    fun ingest_unsupportedType_rejectsSelection() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                service.ingest("archive.zip", "application/zip", byteArrayOf(1, 2, 3))
            }

        assertEquals(AttachmentIngestionService.UNSUPPORTED_ERROR, error.message)
    }

    @Test
    fun ingest_imageRetainsDataUrlAndOnlyBoundedThumbnail() {
        val source = ByteArray(AttachmentIngestionService.MAX_PREVIEW_BYTES * 2) { (it % 251).toByte() }
        val attachment = service.ingest("large.png", "image/png", source)

        assertEquals("", attachment.dataUrl)
        assertArrayEquals(source, java.io.File(requireNotNull(attachment.localPath)).readBytes())
        assertArrayEquals(thumbnail, attachment.previewBytes)
        assertTrue(attachment.previewBytes !== source)
        assertTrue(requireNotNull(attachment.previewBytes).size <= AttachmentIngestionService.MAX_PREVIEW_BYTES)
    }

    @Test
    fun ingest_rejectsDecodedBytesOverTwentyMebibytes() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                service.ingest(
                    "huge.pdf",
                    "application/pdf",
                    ByteArray((AttachmentLimits.MAX_DECODED_BYTES + 1).toInt()),
                )
            }

        assertEquals(AttachmentLimits.TOO_LARGE_ERROR, error.message)
    }

    @Test
    fun discard_deletesOwnedPendingFileAndIgnoresForeignPaths() {
        val owned =
            java.io
                .File(
                    java.io.File(System.getProperty("java.io.tmpdir"), AttachmentLimits.STORE_DIRECTORY_NAME).apply { mkdirs() },
                    "pending.bin",
                ).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val foreign =
            kotlin.io.path
                .createTempFile(prefix = "foreign", suffix = ".bin")
                .toFile()
                .apply { writeBytes(byteArrayOf(4)) }

        AttachmentLimits.discard(owned.absolutePath)
        AttachmentLimits.discard(foreign.absolutePath)

        assertTrue(!owned.exists())
        assertTrue(foreign.exists())
        foreign.delete()
    }

    @Test
    fun ingest_discardsThumbnailAboveBound() {
        val oversizedService =
            AttachmentIngestionService {
                ByteArray(AttachmentIngestionService.MAX_PREVIEW_BYTES + 1)
            }

        val attachment = oversizedService.ingest("large.png", "image/png", byteArrayOf(1, 2, 3))

        assertNull(attachment.previewBytes)
    }

    private fun assertImage(
        filename: String,
        expectedMime: String,
    ) {
        val attachment = service.ingest(filename, expectedMime, byteArrayOf(1, 2, 3))

        assertEquals(AttachmentKind.Image, attachment.kind)
        assertEquals(expectedMime, attachment.mime)
        assertArrayEquals(thumbnail, attachment.previewBytes)
    }
}
