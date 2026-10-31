package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.LocalOpenCodeGalleryImageLoader
import dev.opencode.mobile.ui.components.OpenCodeGalleryImage
import dev.opencode.mobile.ui.components.OpenCodeImageGallery
import dev.opencode.mobile.ui.components.OpenCodeImageGalleryViewer
import dev.opencode.mobile.ui.components.OpenCodeOutgoingMessage
import dev.opencode.mobile.ui.components.OpenCodeOutgoingMessageContent
import dev.opencode.mobile.ui.snapshot.SnapshotEvidenceGalleryMocks

/** Image Gallery: every layout by count, loading and failed tiles, a sent group, and the paged viewer. */
@Composable
fun ImageGalleryGalleryScreen() {
    var open by remember { mutableStateOf<Pair<List<OpenCodeGalleryImage>, Int>?>(null) }
    val loader = remember { SnapshotEvidenceGalleryMocks.Loader() }
    val slowLoader = remember { SnapshotEvidenceGalleryMocks.Loader(delayMs = Long.MAX_VALUE) }

    @Composable
    fun Example(
        title: String,
        images: List<OpenCodeGalleryImage>,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        OpenCodeImageGallery(images = images, onImageClick = { index -> open = images to index })
    }

    CompositionLocalProvider(LocalOpenCodeGalleryImageLoader provides loader) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Example("One image (aspect kept, height capped)", SnapshotEvidenceGalleryMocks.images(1))
            Example("Two images", SnapshotEvidenceGalleryMocks.images(2))
            Example("Three images", SnapshotEvidenceGalleryMocks.images(3))
            Example("Four images", SnapshotEvidenceGalleryMocks.images(4))
            Example("Six images (+3 overflow)", SnapshotEvidenceGalleryMocks.images(6))
            Example(
                "Failed tile beside loaded ones",
                SnapshotEvidenceGalleryMocks.images(2) + SnapshotEvidenceGalleryMocks.image(SnapshotEvidenceGalleryMocks.failing),
            )
            Text("Loading", style = MaterialTheme.typography.titleMedium)
            CompositionLocalProvider(LocalOpenCodeGalleryImageLoader provides slowLoader) {
                OpenCodeImageGallery(
                    images =
                        listOf(
                            OpenCodeGalleryImage("gallery-loading-1", "loading-1.png"),
                            OpenCodeGalleryImage("gallery-loading-2", "loading-2.png"),
                        ),
                    onImageClick = {},
                )
            }
            Text("Sent in a message", style = MaterialTheme.typography.titleMedium)
            val sent = SnapshotEvidenceGalleryMocks.images(3)
            OpenCodeOutgoingMessage(
                text = "Here are the three screens.",
                metadataLabel = "Build · Example Model · 9:05 PM",
                content =
                    listOf(
                        OpenCodeOutgoingMessageContent.ImageGallery(id = "sent", images = sent),
                        OpenCodeOutgoingMessageContent.Text("Here are the three screens."),
                    ),
                onImageClick = { _, index -> open = sent to index },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        open?.let { (images, index) ->
            OpenCodeImageGalleryViewer(
                images = images,
                index = index,
                onIndexChange = { page -> open = images to page },
                onDismiss = { open = null },
            )
        }
    }
}
