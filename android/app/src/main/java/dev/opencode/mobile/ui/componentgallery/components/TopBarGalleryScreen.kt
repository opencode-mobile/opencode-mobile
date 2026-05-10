package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
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
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarAction
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading

private enum class GalleryTopBarLeading {
  None,
  Back,
  Close,
}

private enum class GalleryTopBarActions {
  None,
  Icons,
  Label,
}

@Composable
fun TopBarGalleryScreen(modifier: Modifier = Modifier) {
  var leadingName by rememberSaveable { mutableStateOf(GalleryTopBarLeading.None.name) }
  var actionsName by rememberSaveable { mutableStateOf(GalleryTopBarActions.None.name) }
  var showDivider by rememberSaveable { mutableStateOf(false) }
  var actionsEnabled by rememberSaveable { mutableStateOf(true) }

  val leadingSelection =
    try {
      GalleryTopBarLeading.valueOf(leadingName)
    } catch (_: IllegalArgumentException) {
      GalleryTopBarLeading.None
    }
  val actionsSelection =
    try {
      GalleryTopBarActions.valueOf(actionsName)
    } catch (_: IllegalArgumentException) {
      GalleryTopBarActions.None
    }
  val title =
    when (actionsSelection) {
      GalleryTopBarActions.None -> "Component Gallery"
      GalleryTopBarActions.Icons -> "Session actions"
      GalleryTopBarActions.Label -> "Project settings"
    }
  val leading =
    when (leadingSelection) {
      GalleryTopBarLeading.None -> OpenCodeTopBarLeading.None
      GalleryTopBarLeading.Back -> OpenCodeTopBarLeading.Back(onClick = {})
      GalleryTopBarLeading.Close -> OpenCodeTopBarLeading.Close(onClick = {})
    }
  val actions =
    when (actionsSelection) {
      GalleryTopBarActions.None -> emptyList()
      GalleryTopBarActions.Icons ->
        listOf(
          OpenCodeTopBarAction.Icon(Icons.Filled.Search, "Search", enabled = actionsEnabled, onClick = {}),
          OpenCodeTopBarAction.Icon(Icons.Filled.MoreVert, "More actions", enabled = actionsEnabled, onClick = {}),
        )
      GalleryTopBarActions.Label ->
        listOf(OpenCodeTopBarAction.Label("Done", enabled = actionsEnabled, onClick = {}))
    }

  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(text = "Top bar", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)

      ControlGroup(title = "Leading") {
        GalleryTopBarLeading.entries.forEach { option ->
          OpenCodeChoiceBadge(
            label =
              when (option) {
                GalleryTopBarLeading.None -> "None"
                GalleryTopBarLeading.Back -> "Back"
                GalleryTopBarLeading.Close -> "Close"
              },
            selected = option == leadingSelection,
            onClick = { leadingName = option.name },
          )
        }
      }

      ControlGroup(title = "Actions") {
        GalleryTopBarActions.entries.forEach { option ->
          OpenCodeChoiceBadge(
            label =
              when (option) {
                GalleryTopBarActions.None -> "None"
                GalleryTopBarActions.Icons -> "Icons"
                GalleryTopBarActions.Label -> "Label"
              },
            selected = option == actionsSelection,
            onClick = { actionsName = option.name },
          )
        }
      }

      ControlGroup(title = "State") {
        OpenCodeChoiceBadge(label = "Divider", selected = showDivider, onClick = { showDivider = !showDivider })
        OpenCodeChoiceBadge(
          label = "Actions enabled",
          selected = actionsEnabled,
          onClick = { actionsEnabled = !actionsEnabled },
        )
      }

      Text(
        text = "Switch controls to review the title-only, back, close, icon-action, labeled-action, divider, and disabled-action states.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    OpenCodeTopBar(
      modifier = Modifier.fillMaxWidth(),
      title = title,
      leading = leading,
      actions = actions,
      showDivider = showDivider,
    )
  }
}

@Composable
private fun ControlGroup(
  title: String,
  content: @Composable () -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(text = title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      content()
    }
  }
}
