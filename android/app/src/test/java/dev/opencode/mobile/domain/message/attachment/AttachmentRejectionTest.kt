package dev.opencode.mobile.domain.message

import dev.opencode.mobile.ui.text.english
import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentRejectionTest {
    @Test
    fun everyRejectionReadsItsKeyedEnglish() {
        val expected =
            mapOf(
                AttachmentRejection.Folder to "Folders can't be attached.",
                AttachmentRejection.Duplicate to "This file has already been uploaded",
                AttachmentRejection.TooLarge() to "This file is larger than 20 MB.",
                AttachmentRejection.UnsupportedType to "Only images, PDFs, or text files can be attached here.",
                AttachmentRejection.ModelWithoutImages to "This model does not support image attachments.",
                AttachmentRejection.ModelWithoutPdf to "This model does not support PDF attachments.",
                AttachmentRejection.ModelWithoutSome to "This model does not support some of the selected attachments.",
                AttachmentRejection.Unreadable to "The attachment could not be read.",
                AttachmentRejection.UploadFailed to "Upload failed",
            )
        expected.forEach { (rejection, english) -> assertEquals(rejection.logMessage, english, rejection.text.english()) }
    }

    @Test
    fun theSizeLimitIsAnArgument() {
        assertEquals("This file is larger than 5 MB.", AttachmentRejection.TooLarge(5L * 1024 * 1024).text.english())
    }

    @Test
    fun onlyAnAttachmentRejectionKeepsItsReason() {
        assertEquals(AttachmentRejection.Folder, AttachmentRejectedException(AttachmentRejection.Folder).attachmentRejection())
        assertEquals(AttachmentRejection.Unreadable, SecurityException("Permission Denial: provider detail").attachmentRejection())
    }
}
