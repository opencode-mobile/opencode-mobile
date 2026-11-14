package dev.opencode.mobile.domain.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tool images the session cache drops: tail identity, store hydration, and merge order. */
class ToolImageHydrationTest {
    private val store = InMemoryMessageStore(MessageDomainAdapter())
    private val engine = SessionMessageEngine(store)

    @Test
    fun imageLessCachedTail_doesNotMatchFetchedTailWithImages() {
        val cached = listOf(assistant(images = listOf(cachedImage(thumbnail = "/cache/a.jpg"))))
        val fetched = listOf(assistant(images = listOf(fullImage())))
        assertFalse(cached.matchesTailIdentity(fetched))
        // No images at all in the cache copy (an old cache row) does not match either.
        assertFalse(listOf(assistant(images = emptyList())).matchesTailIdentity(fetched))
        // Two server copies still confirm cheaply.
        assertTrue(fetched.matchesTailIdentity(listOf(assistant(images = listOf(fullImage())))))
    }

    @Test
    fun blankedUserFile_stillMatchesTail() {
        val cached = listOf(userWithFile(dataUrl = ""))
        val fetched = listOf(userWithFile(dataUrl = PNG_URL))
        assertTrue(cached.matchesTailIdentity(fetched))
    }

    @Test
    fun hydrateMedia_fillsToolImagesAndFileDataFromFetchedPage() {
        engine.replaceAll(
            "ses-1",
            listOf(userWithFile(dataUrl = ""), assistant(images = listOf(cachedImage(thumbnail = "/cache/a.jpg")))),
        )
        val before = engine.revision("ses-1")

        val changed =
            engine.hydrateMedia(
                "ses-1",
                listOf(userWithFile(dataUrl = PNG_URL), assistant(images = listOf(fullImage()))),
            )

        assertTrue(changed)
        assertTrue(engine.revision("ses-1") > before)
        val messages = engine.messages("ses-1")
        val file = messages[0].parts.single() as MessagePart.File
        assertEquals(PNG_URL, file.dataUrl)
        val image = (messages[1].parts.single() as MessagePart.Tool).images.single()
        assertEquals(PNG_URL, image.dataUrl)
        assertEquals("/cache/a.jpg", image.thumbnailPath)
        // The text and the rest of the tool stay the cached copy's.
        assertEquals("read", (messages[1].parts.single() as MessagePart.Tool).toolName)
    }

    @Test
    fun hydrateMedia_isNoChangeWhenNothingIsMissing() {
        engine.replaceAll("ses-1", listOf(assistant(images = listOf(fullImage()))))
        val before = engine.revision("ses-1")
        assertFalse(engine.hydrateMedia("ses-1", listOf(assistant(images = listOf(fullImage())))))
        assertFalse(engine.hydrateMedia("ses-1", listOf(assistant(images = listOf(cachedImage(thumbnail = null))))))
        assertEquals(before, engine.revision("ses-1"))
        assertEquals(PNG_URL, soleToolImage().dataUrl)
    }

    @Test
    fun merge_neverDowngradesFullImageToThumbnailOnly() {
        engine.replaceAll("ses-1", listOf(assistant(images = listOf(fullImage()))))
        val revision = engine.revision("ses-1")

        assertTrue(
            engine.mergeAuthoritativeIfRevision(
                "ses-1",
                listOf(assistant(images = listOf(cachedImage(thumbnail = "/cache/a.jpg")))),
                revision,
            ),
        )

        val image = soleToolImage()
        assertEquals(PNG_URL, image.dataUrl)
        assertEquals("/cache/a.jpg", image.thumbnailPath)
    }

    @Test
    fun merge_fullImageReplacesThumbnailOnlyCopy() {
        engine.replaceAll("ses-1", listOf(assistant(images = listOf(cachedImage(thumbnail = "/cache/a.jpg")))))
        val revision = engine.revision("ses-1")

        assertTrue(engine.mergeAuthoritativeIfRevision("ses-1", listOf(assistant(images = listOf(fullImage()))), revision))

        val image = soleToolImage()
        assertEquals(PNG_URL, image.dataUrl)
        assertEquals("/cache/a.jpg", image.thumbnailPath)
    }

    @Test
    fun toolResultImagesMerge_keepsIncomingOrderAndExistingWhenIncomingEmpty() {
        val a = fullImage(id = "a")
        val b = cachedImage(id = "b", thumbnail = "/cache/b.jpg")
        assertEquals(listOf(a, b), ToolResultImages.merge(listOf(a, b), emptyList()))
        val merged = ToolResultImages.merge(listOf(a, fullImage(id = "b")), listOf(b, cachedImage(id = "a", thumbnail = null)))
        assertEquals(listOf("b", "a"), merged.map { it.id })
        assertTrue(merged.all { it.dataUrl == PNG_URL })
        assertEquals("/cache/b.jpg", merged[0].thumbnailPath)
    }

    private fun soleToolImage(): MessagePart.File {
        val message = engine.messages("ses-1").single()
        val tool = message.parts.single() as MessagePart.Tool
        return tool.images.single()
    }

    private fun assistant(images: List<MessagePart.File>) =
        Message(
            id = "msg-a",
            role = MessageRole.Assistant,
            text = "",
            parts =
                listOf(
                    MessagePart.Tool(
                        toolName = "read",
                        status = ToolStatus.Completed,
                        id = "prt-tool",
                        output = "Image read successfully",
                        images = images,
                    ),
                ),
            sentAtEpochMs = 1_000L,
            completedAtEpochMs = 2_000L,
        )

    private fun userWithFile(dataUrl: String) =
        Message(
            id = "msg-u",
            role = MessageRole.User,
            text = "",
            parts =
                listOf(
                    MessagePart.File(
                        id = "prt-file",
                        filename = "photo.png",
                        mime = "image/png",
                        kind = AttachmentKind.Image,
                        dataUrl = dataUrl,
                    ),
                ),
            sentAtEpochMs = 500L,
        )

    private fun fullImage(id: String = ToolResultImages.id("prt-tool", 0)) =
        MessagePart.File(id = id, filename = "shot.png", mime = "image/png", kind = AttachmentKind.Image, dataUrl = PNG_URL)

    private fun cachedImage(
        id: String = ToolResultImages.id("prt-tool", 0),
        thumbnail: String?,
    ) = MessagePart.File(
        id = id,
        filename = "shot.png",
        mime = "image/png",
        kind = AttachmentKind.Image,
        dataUrl = "",
        thumbnailPath = thumbnail,
    )

    private companion object {
        const val PNG_URL = "data:image/png;base64,iVBORw0KGgo="
    }
}
