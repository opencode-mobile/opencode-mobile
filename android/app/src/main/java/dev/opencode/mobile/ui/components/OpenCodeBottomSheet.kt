package dev.opencode.mobile.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private val BottomSheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
private val BottomSheetHeaderHorizontalPadding = 20.dp
private val BottomSheetHeaderTopPadding = 12.dp
private val BottomSheetHeaderBottomPadding = 8.dp
private val BottomSheetContentBottomPadding = 20.dp
private val BottomSheetEntryMinHeight = 48.dp
private val BottomSheetEntryHorizontalPadding = 20.dp
private val BottomSheetEntryVerticalPadding = 12.dp
private val BottomSheetEntryDecoratorSize = 24.dp
private val BottomSheetEntryIconSize = 20.dp

sealed class BottomSheetEntryDecorator {
  data object None : BottomSheetEntryDecorator()

  data class Icon(val imageVector: ImageVector, val contentDescription: String? = null) : BottomSheetEntryDecorator()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenCodeBottomSheet(
  visible: Boolean,
  title: String,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  keyboardAware: Boolean = false,
  content: @Composable ColumnScope.() -> Unit,
) {
  if (!visible) return

  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = keyboardAware)

  if (keyboardAware) {
    LaunchedEffect(Unit) {
      sheetState.expand()
    }
  }

  ModalBottomSheet(
    onDismissRequest = onDismissRequest,
    sheetState = sheetState,
    modifier = modifier,
    shape = BottomSheetShape,
    containerColor = MaterialTheme.colorScheme.surface,
    contentColor = MaterialTheme.colorScheme.onSurface,
    dragHandle = null,
    contentWindowInsets = {
      if (keyboardAware) WindowInsets(0) else BottomSheetDefaults.windowInsets
    },
  ) {
    val bodyModifier =
      Modifier
        .fillMaxWidth()
        .padding(bottom = BottomSheetContentBottomPadding)
        .then(
          if (keyboardAware) {
            Modifier.navigationBarsPadding().imePadding()
          } else {
            Modifier
          },
        )

    Column(modifier = bodyModifier) {
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .padding(
              start = BottomSheetHeaderHorizontalPadding,
              end = BottomSheetHeaderHorizontalPadding,
              top = BottomSheetHeaderTopPadding,
              bottom = BottomSheetHeaderBottomPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          text = title,
          modifier = Modifier.weight(1f),
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        OpenCodeButton(
          variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.Close, "Close"),
          contrast = OpenCodeButtonContrast.Low,
          onClick = onDismissRequest,
        )
      }

      content()
    }
  }
}

@Composable
fun OpenCodeBottomSheetEntry(
  title: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  startDecorator: BottomSheetEntryDecorator = BottomSheetEntryDecorator.None,
  endDecorator: BottomSheetEntryDecorator = BottomSheetEntryDecorator.None,
) {
  val contentColor =
    if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
  val iconColor =
    if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)

  Row(
    modifier =
      modifier.fillMaxWidth()
        .defaultMinSize(minHeight = BottomSheetEntryMinHeight)
        .clickable(enabled = enabled, onClick = onClick)
        .padding(
          horizontal = BottomSheetEntryHorizontalPadding,
          vertical = BottomSheetEntryVerticalPadding,
        )
        .then(if (enabled) Modifier else Modifier.alpha(0.7f)),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    BottomSheetEntryDecoration(decorator = startDecorator, tint = iconColor)
    Text(
      text = title,
      modifier = Modifier.weight(1f),
      style = MaterialTheme.typography.bodyLarge,
      color = contentColor,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    BottomSheetEntryDecoration(decorator = endDecorator, tint = iconColor)
  }
}

@Composable
private fun BottomSheetEntryDecoration(
  decorator: BottomSheetEntryDecorator,
  tint: Color,
) {
  when (decorator) {
    BottomSheetEntryDecorator.None -> Unit
    is BottomSheetEntryDecorator.Icon ->
      Box(modifier = Modifier.size(BottomSheetEntryDecoratorSize), contentAlignment = Alignment.Center) {
        Icon(
          imageVector = decorator.imageVector,
          contentDescription = decorator.contentDescription,
          modifier = Modifier.size(BottomSheetEntryIconSize),
          tint = tint,
        )
      }
  }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeBottomSheetLightPreview() {
  OpenCodeTheme(darkTheme = false) {
    OpenCodeBottomSheet(visible = true, title = "Session actions", onDismissRequest = {}) {
      OpenCodeBottomSheetEntry(
        title = "New session",
        startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Add, "New session"),
        onClick = {},
      )
      OpenCodeBottomSheetEntry(
        title = "Open project",
        startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Folder, "Open project"),
        endDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.ChevronRight, null),
        onClick = {},
      )
    }
  }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeBottomSheetDarkPreview() {
  OpenCodeTheme(darkTheme = true) {
    OpenCodeBottomSheet(visible = true, title = "Session actions", onDismissRequest = {}) {
      OpenCodeBottomSheetEntry(title = "Rename session", onClick = {})
      OpenCodeBottomSheetEntry(
        title = "Fork session",
        endDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.ChevronRight, null),
        onClick = {},
      )
      OpenCodeBottomSheetEntry(
        title = "Delete session",
        enabled = false,
        startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Close, "Delete session"),
        onClick = {},
      )
    }
  }
}
