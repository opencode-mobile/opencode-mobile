package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeTaskRow
import dev.opencode.mobile.ui.components.OpenCodeTaskRowState

@Composable
fun TaskRowGalleryScreen(modifier: Modifier = Modifier) {
    var showTag by rememberSaveable { mutableStateOf(true) }
    var showDetail by rememberSaveable { mutableStateOf(true) }
    var tappable by rememberSaveable { mutableStateOf(true) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text =
                "A background task, such as a subagent: the title leads, a tag marks a non-default agent, one detail line " +
                    "(the current step or the result) and a trailing time over a caption. The state sets the glyph and the detail tone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenCodeChoiceBadge(label = "Tag", selected = showTag, onClick = { showTag = !showTag })
            OpenCodeChoiceBadge(label = "Detail", selected = showDetail, onClick = { showDetail = !showDetail })
            OpenCodeChoiceBadge(label = "Tappable", selected = tappable, onClick = { tappable = !tappable })
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            taskRowGallerySamples.forEach { sample ->
                OpenCodeTaskRow(
                    title = sample.title,
                    state = sample.state,
                    tag = sample.tag.takeIf { showTag },
                    detail = sample.detail.takeIf { showDetail },
                    detailCaption = sample.detailCaption,
                    time = sample.time,
                    onClick = if (tappable) ({}) else null,
                )
            }
        }
    }
}

private data class TaskRowGallerySample(
    val title: String,
    val state: OpenCodeTaskRowState,
    val tag: String?,
    val detail: String,
    val detailCaption: String?,
    val time: String?,
)

private val taskRowGallerySamples =
    listOf(
        TaskRowGallerySample("Find the session store", OpenCodeTaskRowState.Running, "explore", "Searching SessionStore", "7 tools", "0:42"),
        TaskRowGallerySample("Run the test suite", OpenCodeTaskRowState.WaitingForInput, null, "Waiting for input", null, "1:35"),
        TaskRowGallerySample("Review the diff", OpenCodeTaskRowState.Done, "review", "No blocking issues; two nits in the parser", "4m ago", "1:20"),
        TaskRowGallerySample("Migrate the fixtures", OpenCodeTaskRowState.Failed, null, "Rate limited", "6m ago", "0:20"),
        TaskRowGallerySample("Profile the parser", OpenCodeTaskRowState.Stopped, "explore", "Stopped", "8m ago", null),
    )
