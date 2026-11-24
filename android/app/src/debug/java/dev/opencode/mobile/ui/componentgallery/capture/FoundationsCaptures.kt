package dev.opencode.mobile.ui.componentgallery.capture

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.componentgallery.components.FileIconGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.IconsGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.SkeletonIllustrationGalleryScreen
import dev.opencode.mobile.ui.componentgallery.foundation.ColorGalleryScreen
import dev.opencode.mobile.ui.componentgallery.foundation.TypographyGalleryScreen

/** Reference pages: the native gallery screen itself, cut into screen-sized tiles `default`, `default-2`, … */
internal fun foundationsCaptures(): List<ComponentCaptureComponent> =
    listOf(
        referencePage("colors") { ColorGalleryScreen() },
        referencePage("icons") { IconsGalleryScreen() },
        referencePage("file-icon") { FileIconGalleryScreen() },
        referencePage("skeleton-illustration") { SkeletonIllustrationGalleryScreen() },
        referencePage("typography") { TypographyGalleryScreen() },
    )

/** Page height that keeps a tile, with its padding, inside the phone's screen. */
private val ReferencePageHeight = 800.dp

private fun referencePage(
    id: String,
    screen: @Composable () -> Unit,
) = ComponentCaptureComponent(
    id = id,
    states = listOf(ComponentCaptureState("default", pageHeight = ReferencePageHeight, content = screen)),
)
