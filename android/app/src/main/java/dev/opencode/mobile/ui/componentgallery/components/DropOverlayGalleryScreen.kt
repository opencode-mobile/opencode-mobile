package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeAttachmentVariant
import dev.opencode.mobile.ui.components.OpenCodeDropOverlay
import dev.opencode.mobile.ui.components.OpenCodeDropOverlayVariant

@Composable
fun DropOverlayGalleryScreen(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.fillMaxWidth().height(320.dp)) {
            OpenCodeDropOverlay(
                variant = OpenCodeDropOverlayVariant.Accept,
                title = "Drop to attach 3 images",
                subtitle = "Adds them to the message you are writing",
                previews = List(3) { OpenCodeAttachmentVariant.Image },
            )
        }
        Box(Modifier.fillMaxWidth().height(320.dp)) {
            OpenCodeDropOverlay(
                variant = OpenCodeDropOverlayVariant.Accept,
                title = "Drop to attach 2 files",
                subtitle = "1 will be skipped · Folders can't be attached.",
                previews = listOf(OpenCodeAttachmentVariant.Image, OpenCodeAttachmentVariant.Text),
            )
        }
        Box(Modifier.fillMaxWidth().height(320.dp)) {
            OpenCodeDropOverlay(
                variant = OpenCodeDropOverlayVariant.Reject,
                title = "Can't attach this",
                subtitle = "Folders can't be attached.",
            )
        }
    }
}
