package dev.opencode.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme

@Composable
fun OpenCodeListItem(
  title: String,
  subtitle: String? = null,
  selected: Boolean = false,
  enabled: Boolean = true,
  onClick: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
  trailing: (@Composable () -> Unit)? = null,
) {
  val containerColor =
    if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
  val contentColor =
    if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant

  Surface(
    modifier =
      modifier.then(
        if (onClick != null) {
          Modifier.clickable(enabled = enabled, onClick = onClick)
        } else {
          Modifier
        }
      ),
    shape = RoundedCornerShape(14.dp),
    color = containerColor,
    contentColor = contentColor,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
          Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      if (trailing != null) {
        Box(contentAlignment = Alignment.Center) { trailing() }
      }
    }
  }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeListItemPreview() {
  OpenCodeTheme(darkTheme = false) {
    OpenCodeListItem(
      title = "Material Design Icons integration plan",
      subtitle = "http://127.0.0.1:4096/",
      trailing = { Text("...", style = MaterialTheme.typography.titleMedium) },
    )
  }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeListItemSelectedPreview() {
  OpenCodeTheme(darkTheme = true) {
    OpenCodeListItem(
      title = "iOS color palette and typography screens",
      subtitle = "http://127.0.0.1:4096/",
      selected = true,
      trailing = { Text("...", style = MaterialTheme.typography.titleMedium) },
    )
  }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeListItemDisabledPreview() {
  OpenCodeTheme(darkTheme = false) {
    OpenCodeListItem(
      title = "Update agents.md with notes on screenshot",
      subtitle = "Unavailable",
      enabled = false,
    )
  }
}
