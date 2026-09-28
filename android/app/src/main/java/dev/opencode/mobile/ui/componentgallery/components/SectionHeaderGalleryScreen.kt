package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeListGroup
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeSectionHeader
import dev.opencode.mobile.ui.components.OpenCodeSectionHeaderInset
import dev.opencode.mobile.ui.components.OpenCodeSectionHeaderTrailing
import dev.opencode.mobile.ui.components.OpenCodeSectionHeaderVariant

@Composable
fun SectionHeaderGalleryScreen(modifier: Modifier = Modifier) {
    var clearCount by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GalleryHeading("Label", "Uppercase group label above rows.")
        Column {
            OpenCodeSectionHeader(title = "Server")
            OpenCodeListItem(title = "Servers", subtitle = "Manage connection profiles")
            OpenCodeListItem(title = "Reconnect on launch", subtitle = "Restore the last live session")
        }

        GalleryHeading("Plain with count", "Free text kept as written, with a quiet item count.")
        Column {
            val files = listOf("SessionChatScreen.kt", "SessionChatViewModel.kt", "Timeline.kt")
            OpenCodeSectionHeader(
                title = "app/src/main/java/ui/sessionchat",
                variant = OpenCodeSectionHeaderVariant.Plain,
                trailing = OpenCodeSectionHeaderTrailing.Count(files.size),
            )
            OpenCodeListGroup(itemCount = files.size, modifier = Modifier.padding(horizontal = 16.dp)) { index ->
                OpenCodeListItem(title = files[index])
            }
        }

        GalleryHeading("Plain with action", "Compact text action scoped to the group below.")
        Column {
            OpenCodeSectionHeader(
                title = "${maxOf(3 - clearCount, 0)} waiting",
                variant = OpenCodeSectionHeaderVariant.Plain,
                trailing = OpenCodeSectionHeaderTrailing.Action("Clear all") { clearCount += 1 },
            )
            OpenCodeListItem(title = "Add unit tests for the parser")
            OpenCodeListItem(title = "Update the README")
        }

        GalleryHeading("Flush", "No horizontal inset when the container already positions it.")
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            OpenCodeSectionHeader(title = "Context breakdown", inset = OpenCodeSectionHeaderInset.Flush)
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .background(openCodeColor("border-weak-base"), RoundedCornerShape(4.dp)),
            )
        }
    }
}

@Composable
private fun GalleryHeading(
    title: String,
    detail: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
