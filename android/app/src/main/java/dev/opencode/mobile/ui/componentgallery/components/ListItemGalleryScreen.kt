package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.StartDecorator

private enum class GalleryDecoratorKind {
  None,
  Letter,
  Icon,
}

@Composable
fun ListItemGalleryScreen(modifier: Modifier = Modifier) {
  var selected by rememberSaveable { mutableStateOf(false) }
  var enabled by rememberSaveable { mutableStateOf(true) }
  var subtitleVisible by rememberSaveable { mutableStateOf(true) }
  var decoratorKindName by rememberSaveable { mutableStateOf(GalleryDecoratorKind.None.name) }
  var letterAccentName by rememberSaveable { mutableStateOf(LetterAccent.Violet.name) }

  val decoratorKind =
    try {
      GalleryDecoratorKind.valueOf(decoratorKindName)
    } catch (_: IllegalArgumentException) {
      GalleryDecoratorKind.None
    }
  val letterAccent =
    try {
      LetterAccent.valueOf(letterAccentName)
    } catch (_: IllegalArgumentException) {
      LetterAccent.Violet
    }

  val startDecorator: StartDecorator =
    when (decoratorKind) {
      GalleryDecoratorKind.None -> StartDecorator.None
      GalleryDecoratorKind.Letter -> StartDecorator.Letter('A', letterAccent)
      GalleryDecoratorKind.Icon ->
        StartDecorator.Icon(imageVector = Icons.Filled.Folder, contentDescription = "Folder")
    }

  val subtitle: String? =
    when {
      !subtitleVisible -> null
      !enabled -> "Unavailable"
      else -> "http://127.0.0.1:4096/"
    }

  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(text = "List item", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OpenCodeChoiceBadge(label = "Selected", selected = selected, onClick = { selected = !selected })
        OpenCodeChoiceBadge(label = "Enabled", selected = enabled, onClick = { enabled = !enabled })
        OpenCodeChoiceBadge(
          label = "Subtitle",
          selected = subtitleVisible,
          onClick = { subtitleVisible = !subtitleVisible },
        )
      }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(text = "Start decorator", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GalleryDecoratorKind.entries.forEach { kind ->
          OpenCodeChoiceBadge(
            label = kind.name,
            selected = decoratorKind == kind,
            onClick = { decoratorKindName = kind.name },
          )
        }
      }
    }

    if (decoratorKind == GalleryDecoratorKind.Letter) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "Letter accent", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        Row(
          modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          LetterAccent.entries.forEach { accent ->
            OpenCodeChoiceBadge(
              label = accent.name,
              selected = letterAccent == accent,
              onClick = { letterAccentName = accent.name },
            )
          }
        }
      }
    }

    OpenCodeListItem(
      title = "Material Design Icons integration plan",
      subtitle = subtitle,
      selected = selected,
      enabled = enabled,
      startDecorator = startDecorator,
      trailing = { Text("...", style = MaterialTheme.typography.titleMedium) },
    )
  }
}
