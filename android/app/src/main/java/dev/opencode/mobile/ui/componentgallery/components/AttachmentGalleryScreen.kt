package dev.opencode.mobile.ui.componentgallery.components

import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeAttachmentChip
import dev.opencode.mobile.ui.components.OpenCodeAttachmentPresentation
import dev.opencode.mobile.ui.components.OpenCodeAttachmentVariant
import dev.opencode.mobile.ui.components.OpenCodeMessageAttachment

@Composable
fun AttachmentGalleryScreen() {
    var chips by remember { mutableStateOf(AttachmentExamples) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Pending", style = MaterialTheme.typography.titleMedium)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            chips.forEach { attachment ->
                OpenCodeAttachmentChip(
                    attachment = attachment,
                    onRemove = { id -> chips = chips.filterNot { it.id == id } },
                )
            }
        }

        Text("Sent", style = MaterialTheme.typography.titleMedium)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AttachmentExamples.forEach { attachment ->
                OpenCodeMessageAttachment(attachment)
            }
        }
    }
}

private val AttachmentExamples =
    listOf(
        OpenCodeAttachmentPresentation(
            id = "image",
            filename = "workspace-screenshot.png",
            variant = OpenCodeAttachmentVariant.Image,
            imageBytes =
                Base64.decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
                    Base64.DEFAULT,
                ),
        ),
        OpenCodeAttachmentPresentation("pdf", "architecture-notes.pdf", OpenCodeAttachmentVariant.Pdf),
        OpenCodeAttachmentPresentation("text", "debug-output.txt", OpenCodeAttachmentVariant.Text),
        OpenCodeAttachmentPresentation(
            "long",
            "a-very-long-filename-that-demonstrates-single-line-truncation-in-the-chip.md",
            OpenCodeAttachmentVariant.Text,
        ),
    )
