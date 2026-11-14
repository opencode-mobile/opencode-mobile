package dev.opencode.hexcode.home

import dev.opencode.hexcode.session.PromptFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * app-plan.md, Attach files (M7): naming, the server path, the draft's files, the 4-attachment limit, uploads and the
 * caption.
 */
class AttachFilesTest {
    private fun jpeg(name: String) = File("/cache/attach/$name.jpg")

    @Test
    fun aNameKeepsItsOwnTextWithSlashesAndControlCharactersReplaced() {
        assertEquals("report.pdf", FileNames.safe("report.pdf"))
        assertEquals("a_b_c.txt", FileNames.safe("a/b\\c.txt"))
        assertEquals("line_break_tab.txt", FileNames.safe("line\nbreak\ttab.txt"))
        assertEquals("Ünïcode name (1) #?.txt", FileNames.safe("  Ünïcode name (1) #?.txt  "))
    }

    @Test
    fun aBlankNameIsFile() {
        assertEquals("File", FileNames.safe(null))
        assertEquals("File", FileNames.safe(""))
        assertEquals("File", FileNames.safe("   "))
    }

    @Test
    fun aLongNameIsCutTo120KeepingItsExtension() {
        val name = "a".repeat(200) + ".tar.gz"
        val safe = FileNames.safe(name)
        assertEquals(120, safe.length)
        assertTrue(safe.endsWith("a.gz"))
        val noExtension = FileNames.safe("b".repeat(130))
        assertEquals("b".repeat(120), noExtension)
    }

    @Test
    fun aCutNeverSplitsACharacter() {
        val name = "x" + "😀".repeat(70) + ".png"
        val safe = FileNames.safe(name)
        assertTrue(safe.length <= 120)
        assertTrue(safe.endsWith(".png"))
        assertFalse("no lone surrogate", safe.dropLast(4).last().isHighSurrogate())
    }

    @Test
    fun theServerPathIsOneFolderPerFileUnderUploads() {
        assertEquals(
            "/private/tmp/opencode/uploads/0b5c/report.pdf",
            FileNames.serverPath("/private/tmp/opencode/", "0b5c", "report.pdf"),
        )
        assertEquals("C:\\Temp\\opencode\\uploads\\0b5c\\a.txt", FileNames.serverPath("C:\\Temp\\opencode", "0b5c", "a.txt"))
    }

    @Test
    fun theTileShowsAtMostFourCapitalsOfTheExtension() {
        assertEquals("PDF", FileNames.extension("report.pdf"))
        assertEquals("ZIP", FileNames.extension("Logs.ZIP"))
        assertEquals("XLSX", FileNames.extension("sheet.xlsx"))
        assertEquals("JSON", FileNames.extension("data.jsonl"))
        assertEquals("", FileNames.extension("Makefile"))
        assertEquals("", FileNames.extension(".env"))
        assertEquals("", FileNames.extension("archive."))
    }

    @Test
    fun imagesVideosAndFilesShareTheFourPlaces() {
        val (images, _) = Draft().addImages(2)
        assertEquals(2, images.room)
        val (withFiles, ids) = images.addFiles(3)
        assertEquals("only the room left is taken", 2, ids.size)
        assertEquals(0, withFiles.room)
        assertEquals(emptyList<Long>(), withFiles.addImages(1).second)
        assertEquals(emptyList<Long>(), withFiles.addFiles(1).second)
    }

    @Test
    fun aFileUploadsUntilItHasItsPathAndSendWaitsForIt() {
        val (draft, ids) = Draft().withText("Read this").addFiles(1)
        val id = ids.single()
        assertTrue(draft.isUploading)
        assertFalse(draft.isSettled)
        assertFalse(draft.canSend(seesImages = true))
        val staged = draft.fileStaged(id, "report.pdf", "application/pdf", 1_000)
        assertEquals("report.pdf", staged.files.single().name)
        val halfway = staged.fileProgress(id, 450, 1_000)
        assertEquals(45, halfway.uploadPercent)
        val done = halfway.fileUploaded(id, "/t/uploads/u/report.pdf")
        assertFalse(done.isUploading)
        assertNull(done.uploadPercent)
        assertTrue(done.canSend(seesImages = true))
        assertEquals(PromptFile("report.pdf", "application/pdf", "/t/uploads/u/report.pdf"), done.files.single().prompt)
    }

    @Test
    fun theUploadPercentIsEveryRunningUploadTogether() {
        val (draft, ids) = Draft().addFiles(2)
        val both =
            draft
                .fileStaged(ids[0], "a.zip", "application/zip", 1_000)
                .fileStaged(ids[1], "b.zip", "application/zip", 3_000)
                .fileProgress(ids[0], 1_000, 1_000)
                .fileProgress(ids[1], 1_000, 3_000)
        assertEquals(50, both.uploadPercent)
        val oneDone = both.fileUploaded(ids[0], "/t/a.zip")
        assertEquals("only the running one counts", 33, oneDone.uploadPercent)
        assertEquals("before its size is known it's at 0", 0, Draft().addFiles(1).first.uploadPercent)
    }

    @Test
    fun aDraftOfOnlyFilesSendsWhateverTheModel() {
        val (draft, ids) = Draft().addFiles(1)
        val done = draft.fileStaged(ids[0], "a.csv", "text/csv", 10).fileUploaded(ids[0], "/t/a.csv")
        assertFalse(done.isEmpty)
        assertTrue(done.canSend(seesImages = false))
    }

    @Test
    fun aFileFromThePhotoPickersVideosIsAVideo() {
        val (draft, ids) = Draft().addFiles(1, video = true)
        assertTrue(draft.files.single().video)
        assertTrue(
            draft
                .fileStaged(ids[0], "clip.mp4", "video/mp4", 5)
                .files
                .single()
                .video,
        )
        val (file, fileIds) = Draft().addFiles(1)
        assertTrue(
            "a video picked from Files is one too",
            file
                .fileStaged(fileIds[0], "clip.mov", "video/quicktime", 5)
                .files
                .single()
                .video,
        )
    }

    @Test
    fun removingAFileAndClearingTakeItOff() {
        val (draft, ids) = Draft().addFiles(2)
        assertEquals(listOf(ids[1]), draft.removeFile(ids[0]).files.map { it.id })
        assertTrue(draft.hasFile(ids[0]))
        assertFalse(draft.removeFile(ids[0]).hasFile(ids[0]))
        assertTrue(draft.clear().isEmpty)
    }

    @Test
    fun aRestoredDraftBringsItsFilesBackWithTheirPathsAndKeepsTheLimit() {
        val (sent, sentIds) = Draft().withText("One").addImages(1)
        val (sentFiles, fileIds) = sent.imagePrepared(sentIds[0], jpeg("a"), ImageKind.Photo).addFiles(2)
        val uploaded = fileIds.fold(sentFiles) { d, id -> d.fileStaged(id, "f$id.txt", "text/plain", 1).fileUploaded(id, "/t/f$id.txt") }
        val (since, sinceFiles) = uploaded.clear().addFiles(2)
        val restored = since.restored(uploaded)
        assertEquals("One", restored.text)
        assertEquals(sentIds, restored.images.map { it.id })
        assertEquals(fileIds + sinceFiles.take(1), restored.files.map { it.id })
        assertEquals(listOf("/t/f${fileIds[0]}.txt", "/t/f${fileIds[1]}.txt"), restored.files.take(2).map { it.path })
    }

    @Test
    fun theCaptionNamesOneFileCountsSeveralAndMixesKinds() {
        val (one, id) = Draft().addFiles(1)
        val named = one.fileStaged(id[0], "report.pdf", "application/pdf", 1).fileUploaded(id[0], "/t/report.pdf")
        assertEquals(AttachCaption.File("report.pdf"), AttachCaption.of(named, sees = true))
        val (two, ids) = named.addFiles(1)
        val both = two.fileStaged(ids[0], "b.zip", "application/zip", 1).fileUploaded(ids[0], "/t/b.zip")
        assertEquals(AttachCaption.Files(2), AttachCaption.of(both, sees = true))
        val (mixed, imageIds) = both.addImages(1)
        val ready = mixed.imagePrepared(imageIds[0], jpeg("c"), ImageKind.Photo)
        assertEquals(AttachCaption.Mixed(1, 2), AttachCaption.of(ready, sees = true))
        val (images, _) = Draft().addImages(2)
        assertEquals(AttachCaption.Images(2), AttachCaption.of(images, sees = true))
        assertEquals(AttachCaption.NoVision, AttachCaption.of(images, sees = false))
    }

    @Test
    fun whileAnyUploadRunsTheCaptionIsItsPercent() {
        val (draft, ids) = Draft().addImages(1).first.addFiles(1)
        val running = draft.fileStaged(ids[0], "a.zip", "application/zip", 200).fileProgress(ids[0], 90, 200)
        assertEquals(AttachCaption.Uploading(45), AttachCaption.of(running, sees = false))
    }

    @Test
    fun aLongNameInTheCaptionIsShortenedInTheMiddle() {
        assertEquals("report.pdf", MiddleEllipsis.of("report.pdf", 28))
        val short = MiddleEllipsis.of("quarterly-revenue-summary-final-2026.pdf", 21)
        assertEquals(21, short.length)
        assertEquals("quarterly-…-2026.pdf", MiddleEllipsis.of("quarterly-revenue-summary-final-2026.pdf", 20))
        assertTrue(short.startsWith("quarterly-"))
        assertTrue(short.endsWith("2026.pdf"))
    }

    @Test
    fun anUploadIsWrittenOnlyWhenTheServerGivesItsPath() {
        assertEquals(UploadAnswer.Written("/t/uploads/u/a.zip"), UploadAnswer.of(200, "/t/uploads/u/a.zip"))
        assertEquals("a 200 without data.path is a reshaped route", UploadAnswer.RouteChanged, UploadAnswer.of(200, null))
    }

    @Test
    fun theStatusesOfAChangedRouteDimFilesAndOthersAreAFailedUpload() {
        listOf(400, 404, 405, 415).forEach { assertEquals("$it", UploadAnswer.RouteChanged, UploadAnswer.of(it, null)) }
        listOf(401, 413, 500, 502).forEach { assertEquals("$it", UploadAnswer.Failed, UploadAnswer.of(it, null)) }
    }
}
