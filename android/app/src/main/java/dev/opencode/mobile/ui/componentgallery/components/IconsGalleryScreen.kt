package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.icons.OpenCodeIcons

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IconsGalleryScreen(modifier: Modifier = Modifier) {
  val icons =
    listOf(
      "add" to OpenCodeIcons.Add,
      "arrow_back" to OpenCodeIcons.ArrowBack,
      "arrow_upward" to OpenCodeIcons.ArrowUpward,
      "check" to OpenCodeIcons.Check,
      "chevron_right" to OpenCodeIcons.ChevronRight,
      "close" to OpenCodeIcons.Close,
      "content_copy" to OpenCodeIcons.ContentCopy,
      "collapse_content" to OpenCodeIcons.CollapseContent,
      "description" to OpenCodeIcons.Description,
      "edit" to OpenCodeIcons.Edit,
      "expand_content" to OpenCodeIcons.ExpandContent,
      "folder" to OpenCodeIcons.Folder,
      "more_vert" to OpenCodeIcons.MoreVert,
      "search" to OpenCodeIcons.Search,
      "stop_circle" to OpenCodeIcons.StopCircle,
      "undo" to OpenCodeIcons.Undo,
    )

  Column(
    modifier = modifier.padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
      text = "Material Icons catalog from icons/manifest.yaml",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      icons.forEach { (name, vector) ->
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Icon(
            imageVector = vector,
            contentDescription = name,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurface,
          )
          Text(text = name, style = MaterialTheme.typography.labelSmall)
        }
      }
    }
  }
}
