package dev.opencode.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme

private val ChoiceBadgeShape = RoundedCornerShape(16.dp)
private val ChoiceBadgePaddingHorizontal = 12.dp
private val ChoiceBadgePaddingVertical = 8.dp

@Composable
fun OpenCodeChoiceBadge(
  label: String,
  selected: Boolean,
  enabled: Boolean = true,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val containerColor =
    when {
      !enabled -> MaterialTheme.colorScheme.surfaceVariant
      selected -> MaterialTheme.colorScheme.surfaceVariant
      else -> MaterialTheme.colorScheme.surface
    }
  val borderColor =
    when {
      !enabled -> MaterialTheme.colorScheme.outlineVariant
      selected -> MaterialTheme.colorScheme.outline
      else -> MaterialTheme.colorScheme.outlineVariant
    }
  val contentColor =
    when {
      !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
      else -> MaterialTheme.colorScheme.onSurface
    }

  Surface(
    modifier =
      modifier
        .clip(ChoiceBadgeShape)
        .semantics { this.selected = selected }
        .clickable(enabled = enabled, onClick = onClick),
    shape = ChoiceBadgeShape,
    color = containerColor,
    contentColor = contentColor,
    border = BorderStroke(1.dp, borderColor),
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      modifier =
        Modifier.padding(
          horizontal = ChoiceBadgePaddingHorizontal,
          vertical = ChoiceBadgePaddingVertical,
        ),
    )
  }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeChoiceBadgeDefaultPreview() {
  OpenCodeTheme(darkTheme = false) {
    OpenCodeChoiceBadge(label = "Label", selected = false, onClick = {})
  }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeChoiceBadgeSelectedPreview() {
  OpenCodeTheme(darkTheme = false) {
    OpenCodeChoiceBadge(label = "Label", selected = true, onClick = {})
  }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeChoiceBadgeDisabledPreview() {
  OpenCodeTheme(darkTheme = true) {
    OpenCodeChoiceBadge(label = "Label", selected = false, enabled = false, onClick = {})
  }
}
