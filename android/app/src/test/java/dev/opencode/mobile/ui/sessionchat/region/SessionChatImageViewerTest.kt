package dev.opencode.mobile.ui.sessionchat.region

import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.ui.components.OpenCodeAttachmentImageSource
import dev.opencode.mobile.ui.components.OpenCodeAttachmentVariant
import dev.opencode.mobile.ui.components.OpenCodeImageGalleryLayout
import dev.opencode.mobile.ui.components.OpenCodeImageViewerPageStatus
import dev.opencode.mobile.ui.components.openCodeGalleryImageLabel
import dev.opencode.mobile.ui.sessionchat.SessionChatAttachmentItem
import dev.opencode.mobile.ui.sessionchat.SessionChatByteLru
import dev.opencode.mobile.ui.sessionchat.SessionChatGalleryImageLoader
import dev.opencode.mobile.ui.sessionchat.SessionChatImageGalleries
import dev.opencode.mobile.ui.sessionchat.SessionChatImageViewerState
import dev.opencode.mobile.ui.sessionchat.SessionChatMessageItem
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineBlock
import dev.opencode.mobile.ui.sessionchat.view.SessionChatImageShare
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Viewer state actions, gallery lookup, the gallery loader, and layout selection (#898). */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionChatImageViewerTest {
    private val gallery =
        SessionChatTimelineBlock.ImageGallery(blockID = "img:p:t1", images = listOf(item("a"), item("b"), item("c")), fromTool = true)
    private val messages = listOf(message(gallery))

    @Test
    fun open_showPage_dismiss() {
        val viewer = SessionChatImageViewer()

        viewer.open("img:p:t1", 1)
        assertEquals(SessionChatImageViewerState("img:p:t1", 1), viewer.reconcile(messages))

        viewer.showPage(2)
        assertEquals(SessionChatImageViewerState("img:p:t1", 2), viewer.reconcile(messages))

        viewer.dismiss()
        assertNull(viewer.reconcile(messages))
    }

    @Test
    fun reconcile_closesWhenTheGalleryLeavesTheTimeline() {
        val viewer = SessionChatImageViewer()
        viewer.open("img:p:t1", 0)

        assertNull(viewer.reconcile(emptyList()))
        assertNull(viewer.state)
    }

    @Test
    fun reconcile_clampsThePageWhenTheGalleryShrinks() {
        val viewer = SessionChatImageViewer()
        viewer.open("img:p:t1", 2)

        val shrunk = listOf(message(gallery.copy(images = gallery.images.take(2))))

        assertEquals(1, viewer.reconcile(shrunk)?.index)
    }

    @Test
    fun images_findsGalleriesAndLoneImageAttachments() {
        val lone = SessionChatTimelineBlock.Attachment(blockID = "p:f1", attachment = item("f1"))
        val pdf = SessionChatTimelineBlock.Attachment(blockID = "p:d1", attachment = item("d1", OpenCodeAttachmentVariant.Pdf))
        val timeline = listOf(message(gallery), message(lone, id = "m2"), message(pdf, id = "m3"))

        assertEquals(listOf("a", "b", "c"), SessionChatImageGalleries.images(timeline, "img:p:t1")?.map { it.id })
        assertEquals(listOf("f1"), SessionChatImageGalleries.images(timeline, "p:f1")?.map { it.id })
        assertNull(SessionChatImageGalleries.images(timeline, "p:d1"))
        assertNull(SessionChatImageGalleries.images(timeline, "missing"))
    }

    @Test
    fun loader_resolvesTimelineImagesAndDecodesDataUrls() =
        runBlocking {
            val loader = SessionChatGalleryImageLoader { null }
            loader.update(messages)

            assertArrayEquals(PNG_BYTES, loader.load("b"))
            assertNull(loader.load("missing"))

            loader.update(emptyList(), extra = listOf(item("route")))
            assertNull(loader.item("b"))
            assertArrayEquals(PNG_BYTES, loader.load("route"))
        }

    @Test
    fun loader_readsUploadedImagesThroughTheServerLoader() =
        runBlocking {
            val uploaded = item("u").copy(imageSource = OpenCodeAttachmentImageSource.ServerPath("/tmp/example/u.png"))
            val loader = SessionChatGalleryImageLoader { source -> if (source.path == "/tmp/example/u.png") byteArrayOf(7) else null }
            loader.update(listOf(message(SessionChatTimelineBlock.Attachment("p:u", uploaded))))

            assertArrayEquals(byteArrayOf(7), loader.load("u"))
        }

    @Test
    fun loader_thumbnailOnlyImage_readsThumbnailAndFetchesFullThroughItsMessage() =
        runBlocking {
            val cached = item("t").copy(imageSource = null, thumbnailPath = "/cache/tool-thumbs/c/s/t.jpg")
            val fetched = mutableListOf<Pair<String, String>>()
            val memory = SessionChatByteLru(maxBytes = 1_024)
            val loader =
                SessionChatGalleryImageLoader(
                    fullImageLoader = { messageId, imageId ->
                        fetched += messageId to imageId
                        byteArrayOf(9, 9)
                    },
                    thumbnailReader = { path -> if (path.endsWith("t.jpg")) byteArrayOf(4) else null },
                    memory = memory,
                ) { null }
            val gallery = SessionChatTimelineBlock.ImageGallery("img:p:t", listOf(cached), fromTool = true)
            loader.update(listOf(message(gallery, id = "msg_tool")))

            assertTrue(cached.isGalleryImage)
            assertArrayEquals(byteArrayOf(4), loader.thumbnail("t"))
            assertArrayEquals(byteArrayOf(9, 9), loader.loadFull("t"))
            // A second load is a memory hit: no second fetch.
            assertArrayEquals(byteArrayOf(9, 9), loader.loadFull("t"))
            assertEquals(listOf("msg_tool" to "t"), fetched)
        }

    @Test
    fun loader_fullFetchFailureThrowsAndLoadReturnsNull() =
        runBlocking {
            val cached = item("t").copy(imageSource = null, thumbnailPath = "/cache/tool-thumbs/c/s/t.jpg")
            val loader =
                SessionChatGalleryImageLoader(
                    fullImageLoader = { _, _ -> error("offline") },
                    memory = SessionChatByteLru(maxBytes = 1_024),
                ) { null }
            loader.update(listOf(message(SessionChatTimelineBlock.ImageGallery("img:p:t", listOf(cached), fromTool = true))))

            assertNull(loader.load("t"))
            assertTrue(runCatching { loader.loadFull("t") }.isFailure)
        }

    @Test
    fun galleryImage_loadKeyChangesWhenFullDataArrives() {
        val thumbOnly = item("t").copy(imageSource = null, thumbnailPath = "/cache/tool-thumbs/c/s/t.jpg")
        val hydrated = thumbOnly.copy(imageSource = OpenCodeAttachmentImageSource.DataUrl(PNG_URL, "image/png"))
        assertNotEquals(
            SessionChatImageGalleries.galleryImage(thumbOnly).loadKey,
            SessionChatImageGalleries.galleryImage(hydrated).loadKey,
        )
    }

    @Test
    fun byteLru_evictsLeastRecentlyUsedUnderTheCap() {
        val lru = SessionChatByteLru(maxBytes = 10)
        lru.put("a", ByteArray(4))
        lru.put("b", ByteArray(4))
        lru.get("a")
        lru.put("c", ByteArray(4))
        assertNull(lru.get("b"))
        assertEquals(8L, lru.totalBytes)
        lru.put("huge", ByteArray(11))
        assertNull(lru.get("huge"))
        lru.clear()
        assertNull(lru.get("a"))
    }

    @Test
    fun fullImageModel_movesThumbnailLoadingFull() =
        runTest {
            val gate = CompletableDeferred<ByteArray>()
            val model = SessionChatFullImageModel(backgroundScope) { gate.await() }
            assertEquals(OpenCodeImageViewerPageStatus.Thumbnail, model.state.value.status("a"))

            model.show("a")
            runCurrent()
            assertEquals(OpenCodeImageViewerPageStatus.Loading, model.state.value.status("a"))

            gate.complete(byteArrayOf(1))
            runCurrent()
            assertEquals(OpenCodeImageViewerPageStatus.Full, model.state.value.status("a"))
        }

    @Test
    fun fullImageModel_failureStaysUntilRetry() =
        runTest {
            var attempts = 0
            val model =
                SessionChatFullImageModel(backgroundScope) {
                    attempts += 1
                    if (attempts == 1) error("offline") else byteArrayOf(1)
                }

            model.show("a")
            runCurrent()
            assertEquals(OpenCodeImageViewerPageStatus.Failed, model.state.value.status("a"))
            model.show("a")
            runCurrent()
            assertEquals(1, attempts)

            model.retry("a")
            runCurrent()
            assertEquals(OpenCodeImageViewerPageStatus.Full, model.state.value.status("a"))
            assertEquals(2, attempts)
        }

    @Test
    fun layout_byCount() {
        assertNull(OpenCodeImageGalleryLayout.forCount(0))
        assertEquals(OpenCodeImageGalleryLayout.Single, OpenCodeImageGalleryLayout.forCount(1))
        assertEquals(OpenCodeImageGalleryLayout.Pair, OpenCodeImageGalleryLayout.forCount(2))
        assertEquals(OpenCodeImageGalleryLayout.Feature, OpenCodeImageGalleryLayout.forCount(3))
        assertEquals(OpenCodeImageGalleryLayout.Grid, OpenCodeImageGalleryLayout.forCount(4))
        assertEquals(OpenCodeImageGalleryLayout.Grid, OpenCodeImageGalleryLayout.forCount(18))
        assertEquals(0, OpenCodeImageGalleryLayout.overflowCount(4))
        assertEquals(2, OpenCodeImageGalleryLayout.overflowCount(5))
        assertEquals(15, OpenCodeImageGalleryLayout.overflowCount(18))
    }

    @Test
    fun accessibilityLabel_namesThePositionAndFile() {
        assertEquals("Image 2 of 5, screen.png", openCodeGalleryImageLabel(1, 5, "screen.png"))
        assertEquals("Image 1 of 1", openCodeGalleryImageLabel(0, 1, null))
    }

    @Test
    fun share_usesAPlainFileName() {
        assertEquals("shot.png", SessionChatImageShare.safeFilename("/Users/example/project/shot.png"))
        assertEquals("a_b.png", SessionChatImageShare.safeFilename("a?b.png"))
        assertEquals("image.png", SessionChatImageShare.safeFilename("..."))
    }

    private fun message(
        block: SessionChatTimelineBlock,
        id: String = "m1",
    ): SessionChatMessageItem =
        SessionChatMessageItem(id = id, role = MessageRole.Assistant, text = "", metadataLabel = "", blocks = listOf(block))

    private fun item(
        id: String,
        variant: OpenCodeAttachmentVariant = OpenCodeAttachmentVariant.Image,
    ): SessionChatAttachmentItem =
        SessionChatAttachmentItem(
            id = id,
            filename = "$id.png",
            variant = variant,
            signature = id,
            imageSource = OpenCodeAttachmentImageSource.DataUrl(PNG_URL, "image/png").takeIf { variant == OpenCodeAttachmentVariant.Image },
        )

    private companion object {
        const val PNG_URL = "data:image/png;base64,AQID"
        val PNG_BYTES = byteArrayOf(1, 2, 3)
    }
}
