package dev.opencode.hexcode.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** app-plan.md, Attach: images on the draft, the 4-image limit, when Send can go, and the names in the request. */
class AttachDraftTest {
    private fun file(name: String) = File("/cache/attach/$name.jpg")

    @Test
    fun aDraftTakesFourImagesAtMost() {
        val (three, first) = Draft().addImages(3)
        assertEquals(listOf(1L, 2L, 3L), first)
        assertEquals(1, three.roomForImages)
        val (four, second) = three.addImages(3)
        assertEquals("only the room left is taken", listOf(4L), second)
        assertEquals(4, four.images.size)
        assertEquals(0, four.roomForImages)
        assertEquals(emptyList<Long>(), four.addImages(1).second)
    }

    @Test
    fun anImageIsPreparingUntilItsJpegIsReadyAndSendWaitsForIt() {
        val (draft, ids) = Draft().withText("What is this?").addImages(1)
        assertTrue(draft.isPreparing)
        assertFalse(draft.isSettled)
        assertFalse(draft.canSend(seesImages = true))
        val ready = draft.imagePrepared(ids[0], file("a"), ImageKind.Screenshot)
        assertFalse(ready.isPreparing)
        assertTrue(ready.canSend(seesImages = true))
        assertEquals(ImageKind.Screenshot, ready.images.single().kind)
    }

    @Test
    fun aDraftOfOnlyImagesShowsAndSendsOnlyToAModelThatSeesThem() {
        val (draft, ids) = Draft().addImages(1)
        assertFalse(draft.isEmpty)
        val ready = draft.imagePrepared(ids[0], file("a"), ImageKind.Photo)
        assertTrue(ready.canSend(seesImages = true))
        assertFalse("nothing would be left to send", ready.canSend(seesImages = false))
        assertTrue(ready.withText("Look").canSend(seesImages = false))
    }

    @Test
    fun removingAnImageAndClearingTakeItOff() {
        val (draft, ids) = Draft().addImages(2)
        assertEquals(listOf(ids[1]), draft.removeImage(ids[0]).images.map { it.id })
        assertTrue(draft.clear().isEmpty)
    }

    @Test
    fun aRestoredDraftBringsItsImagesBackFirstAndKeepsTheLimit() {
        val (sent, sentIds) = Draft().withText("One").addImages(3)
        val (since, sinceIds) = sent.clear().addImages(2)
        val restored = since.restored(sent)
        assertEquals("One", restored.text)
        assertEquals(sentIds + sinceIds.take(1), restored.images.map { it.id })
    }

    @Test
    fun theRequestNamesEachKindInOrder() {
        val images =
            listOf(
                DraftImage(1, file("a"), ImageKind.Photo),
                DraftImage(2, file("b"), ImageKind.Screenshot),
                DraftImage(3, file("c"), ImageKind.Photo),
            )
        assertEquals(listOf("Photo 1.jpg", "Screenshot 1.jpg", "Photo 2.jpg"), AttachNames.of(images).map { it.name })
        assertEquals(listOf(file("a"), file("b"), file("c")), AttachNames.of(images).map { it.file })
    }

    @Test
    fun aPickedPngIsAScreenshotAndEverythingElseAPhoto() {
        assertEquals(ImageKind.Screenshot, ImageFit.kindOf("image/png"))
        assertEquals(ImageKind.Photo, ImageFit.kindOf("image/jpeg"))
        assertEquals(ImageKind.Photo, ImageFit.kindOf("image/heif"))
        assertEquals(ImageKind.Photo, ImageFit.kindOf(null))
        assertEquals("the camera's pictures are photos", ImageKind.Photo, ImageFit.kindOf("image/png", fromCamera = true))
    }

    @Test
    fun theLongestSideIsScaledTo2000NeverUp() {
        assertEquals(2000 to 1500, ImageFit.size(4032, 3024))
        assertEquals(923 to 2000, ImageFit.size(1179, 2556))
        assertEquals(800 to 600, ImageFit.size(800, 600))
        assertEquals(2 to 1, ImageFit.sampleSize(4032, 3024) to ImageFit.sampleSize(3000, 2000))
    }
}
