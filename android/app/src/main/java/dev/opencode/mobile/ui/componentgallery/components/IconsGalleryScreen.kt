package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import kotlin.math.max

private val IconCellMinWidth = 80.dp
private val IconGridSpacing = 12.dp

@Composable
fun IconsGalleryScreen(modifier: Modifier = Modifier) {
    val icons =
        listOf(
            "add" to OpenCodeIcons.Add,
            "arrow_back" to OpenCodeIcons.ArrowBack,
            "arrow_upward" to OpenCodeIcons.ArrowUpward,
            "arrow_warm_up" to OpenCodeIcons.ArrowWarmUp,
            "check" to OpenCodeIcons.Check,
            "chevron_right" to OpenCodeIcons.ChevronRight,
            "close" to OpenCodeIcons.Close,
            "content_copy" to OpenCodeIcons.ContentCopy,
            "collapse_content" to OpenCodeIcons.CollapseContent,
            "description" to OpenCodeIcons.Description,
            "edit" to OpenCodeIcons.Edit,
            "expand_content" to OpenCodeIcons.ExpandContent,
            "folder" to OpenCodeIcons.Folder,
            "folder_check" to OpenCodeIcons.FolderCheck,
            "more_vert" to OpenCodeIcons.MoreVert,
            "search" to OpenCodeIcons.Search,
            "architecture" to OpenCodeIcons.Architecture,
            "archive" to OpenCodeIcons.Archive,
            "cloud" to OpenCodeIcons.Cloud,
            "stop" to OpenCodeIcons.Stop,
            "stop_circle" to OpenCodeIcons.StopCircle,
            "subdirectory_arrow_right" to OpenCodeIcons.SubdirectoryArrowRight,
            "undo" to OpenCodeIcons.Undo,
            "widgets" to OpenCodeIcons.Widgets,
        )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Material Icons catalog from icons/manifest.yaml",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val columns =
                max(
                    1,
                    ((maxWidth + IconGridSpacing) / (IconCellMinWidth + IconGridSpacing)).toInt(),
                )
            val cellWidth = (maxWidth - IconGridSpacing * (columns - 1)) / columns

            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                icons.chunked(columns).forEach { rowIcons ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(IconGridSpacing),
                    ) {
                        rowIcons.forEach { (name, vector) ->
                            Column(
                                modifier =
                                    Modifier
                                        .width(cellWidth)
                                        .heightIn(min = 72.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = vector,
                                    contentDescription = name,
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
