package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.opencode.mobile.ui.components.OpenCodeListGroup
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeListItemAccessory

@Composable
fun ListGroupGalleryScreen(modifier: Modifier = Modifier) {
    val files = listOf("Sources/App.kt", "README.md", "Tests/AppTest.kt")
    OpenCodeListGroup(itemCount = files.size, modifier = modifier) { index ->
        OpenCodeListItem(
            title = files[index],
            accessory = OpenCodeListItemAccessory.Disclosure,
        )
    }
}
