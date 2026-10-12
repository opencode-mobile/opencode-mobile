package dev.opencode.mobile.domain.t3

import dev.opencode.mobile.domain.connection.ConnectionEngine
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.network.ActiveConnectionManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class T3FileAttachmentTest {
    @Test
    fun descriptorClampsTheAdvertisedFileLimit() {
        val parsed =
            T3Json.environmentDescriptor(
                """
                {"environmentId":"env-1","label":"Desk","orchestrationProtocolVersion":2,
                "capabilities":{"attachmentUploads":true,"fileAttachments":{"maxUploadBytes":104857600}}}
                """.trimIndent(),
            )
        assertEquals(T3Assets.MAX_FILE_BYTES.toLong(), parsed.maxFileUploadBytes)
    }

    @Test
    fun descriptorWithoutFileAttachmentsStaysImagesOnly() {
        val parsed =
            T3Json.environmentDescriptor(
                """{"environmentId":"env-1","label":"Desk","orchestrationProtocolVersion":2}""",
            )
        assertNull(parsed.maxFileUploadBytes)
    }

    @Test
    fun filesPickerFollowsTheActiveEnvironmentLimit() =
        runTest {
            val profile =
                ConnectionProfile(
                    baseUrl = "https://t3.example.com/",
                    username = ConnectionEngine.T3_USERNAME,
                    password = "token",
                    engine = ConnectionEngine.T3,
                    id = "profile-1",
                )
            val limit = T3FileAttachmentLimit()
            val manager = ActiveConnectionManager(FakeProfileStore(profile), fileAttachmentLimit = limit)
            manager.ensureLoaded()

            assertFalse(manager.currentCapabilities().supportsDocumentAttachments)
            limit.note(profile.id, 1024)
            assertTrue(manager.currentCapabilities().supportsDocumentAttachments)
            limit.activate("other")
            assertFalse(manager.currentCapabilities().supportsDocumentAttachments)
            limit.activate(profile.id)
            assertTrue(manager.currentCapabilities().supportsDocumentAttachments)
        }
}
