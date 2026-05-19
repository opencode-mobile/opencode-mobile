package dev.opencode.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.badgeColors

private val ListItemStartDecoratorSize = 24.dp
private val ListItemLetterBadgeCornerRadius = 4.dp
private val ListItemCornerRadius = 8.dp

@Composable
fun OpenCodeListItem(
  title: String,
  subtitle: String? = null,
  selected: Boolean = false,
  enabled: Boolean = true,
  startDecorator: StartDecorator = StartDecorator.None,
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
    shape = RoundedCornerShape(ListItemCornerRadius),
    color = containerColor,
    contentColor = contentColor,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      ListItemStartDecoration(decorator = startDecorator, enabled = enabled)

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

@Composable
private fun ListItemStartDecoration(decorator: StartDecorator, enabled: Boolean) {
  if (decorator is StartDecorator.None) return
  val dark = isSystemInDarkTheme()
  val emphasize =
    Modifier.then(
      if (enabled) Modifier else Modifier.alpha(0.7f)
    )
  when (decorator) {
    is StartDecorator.Letter -> {
      val (fill, tint) = decorator.accent.badgeColors(dark)
      Box(
        modifier =
          Modifier.size(ListItemStartDecoratorSize)
            .clip(RoundedCornerShape(ListItemLetterBadgeCornerRadius))
            .background(fill)
            .semantics { hideFromAccessibility() }
            .then(emphasize),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = normalizedBadgeLetter(decorator.character),
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.SemiBold,
          color = tint,
          textAlign = TextAlign.Center,
          maxLines = 1,
        )
      }
    }
    is StartDecorator.Icon -> {
      val tint =
        if (enabled) {
          MaterialTheme.colorScheme.onSurfaceVariant
        } else {
          MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        }
      Icon(
        imageVector = decorator.imageVector,
        contentDescription = decorator.contentDescription,
        modifier = Modifier.size(ListItemStartDecoratorSize).then(emphasize),
        tint = tint,
      )
    }
    is StartDecorator.None -> Unit
  }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeListItemPreview() {
  OpenCodeTheme(darkTheme = false) {
    OpenCodeListItem(
      title = "Material Design Icons integration plan",
      subtitle = "http://127.0.0.1:4096/",
    )
  }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeListItemLetterPreview() {
  OpenCodeTheme(darkTheme = false) {
    OpenCodeListItem(
      title = "Purple accent",
      subtitle = "Letter badge",
      startDecorator = StartDecorator.Letter('m', LetterAccent.Violet),
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
      startDecorator = StartDecorator.Letter('x', LetterAccent.Teal),
    )
  }
}
