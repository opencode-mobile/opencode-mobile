package dev.opencode.mobile.domain.message

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttachmentPreviewCacheTest {
    private val thumbnail = byteArrayOf(7, 7, 7)

    @Test
    fun preview_decodesSmallImageOnceThenServesCache() {
        var decodes = 0
        val cache = AttachmentPreviewCache(thumbnailer = { decodes++; thumbnail })
        val file = imageFile(byteCount = 1_024)

        assertArrayEquals(thumbnail, cache.preview(file))
        assertArrayEquals(thumbnail, cache.preview(file))
        assertEquals(1, decodes)
    }

    @Test
    fun preview_largeImageWaitsForWarm() {
        var decodes = 0
        val cache = AttachmentPreviewCache(thumbnailer = { decodes++; thumbnail })
        // A photo the server echoes back: well past the inline decode limit.
        val file = imageFile(byteCount = 600 * 1024)

        assertNull(cache.preview(file))
        assertEquals(0, decodes)

        cache.warm(listOf(message(file)))

        assertArrayEquals(thumbnail, cache.preview(file))
        assertEquals(1, decodes)
    }

    @Test
    fun warm_skipsNonImagesAndRemembersUndecodableImages() {
        var decodes = 0
        val cache = AttachmentPreviewCache(thumbnailer = { decodes++; null })
        val image = imageFile(byteCount = 600 * 1024)
        val pdf =
            MessagePart.File(
                id = "pdf",
                filename = "a.pdf",
                mime = "application/pdf",
                kind = AttachmentKind.Pdf,
                dataUrl = AttachmentDataUrl.make(ByteArray(8), "application/pdf").orEmpty(),
            )

        cache.warm(listOf(message(image, pdf)))
        cache.warm(listOf(message(image, pdf)))

        assertNull(cache.preview(image))
        assertNull(cache.preview(pdf))
        assertEquals(1, decodes)
    }

    @Test
    fun warm_evictsLeastRecentlyUsedPastCapacity() {
        val cache = AttachmentPreviewCache(maxEntries = 1, thumbnailer = { thumbnail })
        val first = imageFile(byteCount = 600 * 1024, id = "first")
        val second = imageFile(byteCount = 600 * 1024, id = "second")

        cache.warm(listOf(message(first, second)))

        assertNull(cache.preview(first))
        assertArrayEquals(thumbnail, cache.preview(second))
    }

    private fun imageFile(
        byteCount: Int,
        id: String = "prt-photo",
    ): MessagePart.File =
        MessagePart.File(
            id = id,
            filename = "Photo 1.png",
            mime = "image/jpeg",
            kind = AttachmentKind.Image,
            dataUrl = AttachmentDataUrl.make(ByteArray(byteCount), "image/jpeg").orEmpty(),
        )

    private fun message(vararg files: MessagePart.File): Message =
        Message(
            id = "msg-1",
            role = MessageRole.User,
            text = "",
            parts = files.toList(),
        )
}
