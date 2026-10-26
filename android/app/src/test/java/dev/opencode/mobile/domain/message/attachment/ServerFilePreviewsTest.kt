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
    fun aBareNameFallsBackToTheSessionDirectory() {
        assertEquals("/repo" to "shot.png", DefaultServerFilePreviews.previewLocation("shot.png", "/repo"))
    }
}
