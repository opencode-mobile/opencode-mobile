package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeSessionTodoItem
import dev.opencode.mobile.ui.components.OpenCodeSessionTodoItemStatus

@Composable
fun SessionTodoItemGalleryScreen(modifier: Modifier = Modifier) {
    var status by rememberSaveable { mutableStateOf(OpenCodeSessionTodoItemStatus.InProgress) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "Interactive", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Review pending, in progress, completed, and cancelled Session Todo rows.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenCodeSessionTodoItemStatus.entries.forEach { option ->
                OpenCodeChoiceBadge(
                    option.galleryLabel,
                    status == option,
                    onClick = { status = option },
                )
            }
        }
        OpenCodeSessionTodoItem(
            content = status.galleryContent,
            status = status,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(openCodeColor("surface-raised-base"), RoundedCornerShape(12.dp)),
        )
    }
}

private val OpenCodeSessionTodoItemStatus.galleryLabel: String
    get() =
        when (this) {
            OpenCodeSessionTodoItemStatus.Pending -> "Pending"
            OpenCodeSessionTodoItemStatus.InProgress -> "Active"
            OpenCodeSessionTodoItemStatus.Completed -> "Done"
            OpenCodeSessionTodoItemStatus.Cancelled -> "Cancel"
        }

private val OpenCodeSessionTodoItemStatus.galleryContent: String
    get() =
        when (this) {
            OpenCodeSessionTodoItemStatus.Pending -> "Summarize what the README says in 2 sentences"
            OpenCodeSessionTodoItemStatus.InProgress -> "List the top-level files in this repo"
            OpenCodeSessionTodoItemStatus.Completed -> "Suggest one small follow-up improvement"
            OpenCodeSessionTodoItemStatus.Cancelled -> "Optional deep dive that was cancelled"
        }
