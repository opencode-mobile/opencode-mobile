package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.AttachmentDropItem
import dev.opencode.mobile.domain.message.AttachmentDropKind
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionChatAttachmentDropTest {
    @Test
    fun textTypesAreNeverDropItems() {
        assertEquals(emptyList<AttachmentDropItem>(), sessionChatDropItems(setOf("text/plain", "text/html", "text/uri-list")))
    }

    @Test
    fun eachOtherTypeIsOneItemOfItsKind() {
        assertEquals(
            listOf(
                AttachmentDropItem(AttachmentDropKind.Image),
                AttachmentDropItem(AttachmentDropKind.Pdf),
                AttachmentDropItem(AttachmentDropKind.Folder),
                AttachmentDropItem(AttachmentDropKind.File),
            ),
            sessionChatDropItems(
                linkedSetOf("image/png", "text/plain", "application/pdf", "vnd.android.document/directory", "application/zip"),
            ),
        )
    }
}
