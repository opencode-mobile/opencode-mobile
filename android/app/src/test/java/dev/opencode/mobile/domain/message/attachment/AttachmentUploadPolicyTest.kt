package dev.opencode.mobile.domain.message

import dev.opencode.mobile.domain.model.ModelSelectionOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentUploadPolicyTest {
    private val visionModel = model(image = true, pdf = true)
    private val textOnlyModel = model(image = false, pdf = false)
    private val limit = AttachmentLimits.MAX_DECODED_BYTES

    @Test
    fun nativeTypeUnderTheLimitStaysInline() {
        assertEquals(AttachmentDestination.Inline, destination("image/png", 1_000, visionModel, true))
        assertEquals(AttachmentDestination.Inline, destination("application/pdf", limit, visionModel, true))
    }

    @Test
    fun nativeTypeOverTheLimitIsUploaded() {
        assertEquals(AttachmentDestination.Upload, destination("image/png", limit + 1, visionModel, true))
    }

    @Test
    fun typesTheModelDoesNotReadAreUploaded() {
        assertEquals(AttachmentDestination.Upload, destination("image/png", 10, textOnlyModel, true))
        assertEquals(AttachmentDestination.Upload, destination("application/pdf", 10, textOnlyModel, true))
        assertEquals(AttachmentDestination.Upload, destination("text/plain", 10, visionModel, true))
        assertEquals(AttachmentDestination.Upload, destination("application/zip", 10, visionModel, true))
    }

    @Test
    fun withoutUploadsEverythingGoesTheDataUrlWayWithTheOldLimit() {
        assertEquals(AttachmentDestination.Inline, destination("text/plain", 10, visionModel, false))
        assertEquals(
            AttachmentDestination.Reject(AttachmentLimits.TOO_LARGE_ERROR),
            destination("image/png", limit + 1, visionModel, false),
        )
    }

    @Test
    fun duplicateNeedsTheSameNameAndSizeAsAnotherDraftFile() {
        val draft = listOf(attachment("a", "report.pdf", 10))
        assertTrue(AttachmentUploadPolicy.isDuplicate(attachment("b", "report.pdf", 10), draft))
        assertFalse(AttachmentUploadPolicy.isDuplicate(attachment("b", "report.pdf", 11), draft))
        assertFalse(AttachmentUploadPolicy.isDuplicate(attachment("b", "other.pdf", 10), draft))
        // A replaced attachment keeps its id and is not its own duplicate.
        assertFalse(AttachmentUploadPolicy.isDuplicate(attachment("a", "report.pdf", 10), draft))
    }

    @Test
    fun uploadedDuplicatesCountToo() {
        val draft = listOf(attachment("a", "report.pdf", 10).copy(upload = AttachmentUpload.Uploaded("/tmp/x")))
        assertTrue(AttachmentUploadPolicy.isDuplicate(attachment("b", "report.pdf", 10), draft))
    }

    @Test
    fun failureClassificationFollowsTheTable() {
        listOf(404, 405, 400, 415, 422).forEach {
            assertEquals("status $it", AttachmentUploadResult.ContractMiss, AttachmentUploadFailure.classify(it))
        }
        listOf(401, 403, 408, 429, 500, 502, 503).forEach {
            assertEquals("status $it", AttachmentUploadResult.TransportFailure, AttachmentUploadFailure.classify(it))
        }
    }

    @Test
    fun serverPathGivesEachFileItsOwnDirectoryUnderTmp() {
        assertEquals("/tmp/uploads/u1/a_b.txt", AttachmentUploadFailure.serverPath("/tmp/", "u1", "a/b.txt"))
        assertEquals("C:\\Temp\\uploads\\u1\\a_b.txt", AttachmentUploadFailure.serverPath("C:\\Temp", "u1", "a\\b.txt"))
    }

    @Test
    fun promptTextAppendsOneAttachedFileLinePerUpload() {
        assertEquals("hi", AttachmentUploadFailure.promptText("hi", emptyList()))
        assertEquals("hi\nAttached file: `/tmp/a`", AttachmentUploadFailure.promptText("hi", listOf("/tmp/a")))
        assertEquals(
            "Attached file: `/tmp/a`\nAttached file: `/tmp/b`",
            AttachmentUploadFailure.promptText("  ", listOf("/tmp/a", "/tmp/b")),
        )
    }

    private fun destination(
        mime: String,
        bytes: Long,
        model: ModelSelectionOption,
        upload: Boolean,
    ) = AttachmentUploadPolicy.destination(mime, bytes, model, upload)

    private fun model(
        image: Boolean,
        pdf: Boolean,
    ) = ModelSelectionOption(
        providerId = "p",
        providerName = "P",
        modelId = "m",
        modelName = "M",
        supportsImageInput = image,
        supportsPdfInput = pdf,
    )

    private fun attachment(
        id: String,
        name: String,
        size: Long,
    ) = Attachment(
        id = id,
        filename = name,
        mime = "application/pdf",
        kind = AttachmentKind.Pdf,
        dataUrl = "",
        byteCount = size,
    )
}
