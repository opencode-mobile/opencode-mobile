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
    fun withNoKnownModelImagesAndPdfsGoInlineAndEverythingElseUploads() {
        assertEquals(AttachmentDestination.Inline, AttachmentUploadPolicy.destination("image/png", 10, null, true))
        assertEquals(AttachmentDestination.Inline, AttachmentUploadPolicy.destination("application/pdf", 10, null, true))
        assertEquals(AttachmentDestination.Upload, AttachmentUploadPolicy.destination("text/plain", 10, null, true))
        assertEquals(AttachmentDestination.Upload, AttachmentUploadPolicy.destination("application/zip", 10, null, true))
    }

    @Test
    fun resolveDeclaredFallsBackToTheExtensionOnlyForAGenericType() {
        assertEquals("image/png", AttachmentMime.resolveDeclared("application/octet-stream", "shot.png"))
        assertEquals("image/png", AttachmentMime.resolveDeclared(null, "shot.png"))
        assertEquals("image/jpeg", AttachmentMime.resolveDeclared("image/jpeg", "shot.png"))
        assertEquals("application/octet-stream", AttachmentMime.resolveDeclared("application/octet-stream", "data.bin"))
        assertEquals(null, AttachmentMime.resolveDeclared(null, "data.bin"))
    }

    @Test
    fun inlineRejectionNamesWhyAFileCannotRideInThePrompt() {
        val small = attachment("a", "a.pdf", 10)
        assertEquals(null, AttachmentUploadPolicy.inlineRejection(small, visionModel))
        assertEquals(
            InlineRejection.TooLarge,
            AttachmentUploadPolicy.inlineRejection(small.copy(byteCount = limit + 1), visionModel),
        )
        assertEquals(InlineRejection.Unsupported, AttachmentUploadPolicy.inlineRejection(small, textOnlyModel))
        assertEquals(
            InlineRejection.Unsupported,
            AttachmentUploadPolicy.inlineRejection(small.copy(kind = AttachmentKind.File, mime = "application/zip"), visionModel),
        )
        assertEquals(null, AttachmentUploadPolicy.inlineRejection(small.copy(kind = AttachmentKind.Text, mime = "text/plain"), textOnlyModel))
    }

    @Test
    fun uploadedServerPathIsOnlySetOnceUploaded() {
        val file = attachment("a", "a.bin", 1)
        assertEquals(null, file.uploadedServerPath)
        assertEquals("/srv/a.bin", file.copy(upload = AttachmentUpload.Uploaded("/srv/a.bin")).uploadedServerPath)
        assertEquals(null, file.copy(upload = AttachmentUpload.Uploading(10)).uploadedServerPath)
        val mixed = listOf(file, file.copy(id = "b", upload = AttachmentUpload.Uploaded("/srv/b")))
        assertEquals(listOf("a"), mixed.inlineAttachments().map { it.id })
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
        listOf(404, 405, 400, 415).forEach {
            assertEquals("status $it", AttachmentUploadResult.ContractMiss, AttachmentUploadFailure.classify(it))
        }
        // An unlisted status is a failure of this upload, never a reason to switch uploads off.
        listOf(401, 403, 408, 409, 413, 422, 429, 500, 502, 503).forEach {
            assertEquals("status $it", AttachmentUploadResult.TransportFailure, AttachmentUploadFailure.classify(it))
        }
    }

    @Test
    fun serverPathGivesEachFileItsOwnDirectoryUnderTmp() {
        assertEquals("/tmp/uploads/u1/a_b.txt", UploadedAttachments.serverPath("/tmp/", "u1", "a/b.txt"))
        assertEquals("C:\\Temp\\uploads\\u1\\a_b.txt", UploadedAttachments.serverPath("C:\\Temp", "u1", "a\\b.txt"))
    }

    @Test
    fun promptTextAppendsOneAttachedFileLinePerUpload() {
        assertEquals("hi", UploadedAttachments.promptText("hi", emptyList()))
        assertEquals("hi\nAttached file: `/tmp/a`", UploadedAttachments.promptText("hi", listOf("/tmp/a")))
        assertEquals(
            "Attached file: `/tmp/a`\nAttached file: `/tmp/b`",
            UploadedAttachments.promptText("  ", listOf("/tmp/a", "/tmp/b")),
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
