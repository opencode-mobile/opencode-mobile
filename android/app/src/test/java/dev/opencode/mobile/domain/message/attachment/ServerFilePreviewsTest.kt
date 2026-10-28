package dev.opencode.mobile.domain.message

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerFilePreviewsTest {
    @Test
    fun aFileIsReadFromTheFolderThatHoldsIt() {
        assertEquals(
            "/srv/tmp/uploads/u1" to "shot.png",
            DefaultServerFilePreviews.previewLocation("/srv/tmp/uploads/u1/shot.png", "/repo"),
        )
        assertEquals(
            "C:\\Temp\\uploads\\u1" to "shot.png",
            DefaultServerFilePreviews.previewLocation("C:\\Temp\\uploads\\u1\\shot.png", "/repo"),
        )
    }

    @Test
    fun anUploadUnderTheTmpDirectoryIsReadFromTmpWithARelativePath() {
        assertEquals(
            "/srv/tmp" to "uploads/u1/shot.png",
            DefaultServerFilePreviews.previewLocation("/srv/tmp/uploads/u1/shot.png", "/repo", "/srv/tmp/"),
        )
        assertEquals(
            "C:\\Temp" to "uploads/u1/shot.png",
            DefaultServerFilePreviews.previewLocation("C:\\Temp\\uploads\\u1\\shot.png", "/repo", "C:\\Temp"),
        )
        // Outside tmp the file's own folder is the location.
        assertEquals(
            "/srv/other" to "x.png",
            DefaultServerFilePreviews.previewLocation("/srv/other/x.png", "/repo", "/srv/tmp"),
        )
    }

    @Test
    fun aBareNameFallsBackToTheSessionDirectory() {
        assertEquals("/repo" to "shot.png", DefaultServerFilePreviews.previewLocation("shot.png", "/repo"))
    }
}
