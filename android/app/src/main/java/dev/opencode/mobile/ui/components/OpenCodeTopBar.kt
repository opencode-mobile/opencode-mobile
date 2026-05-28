package dev.opencode.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private val TopBarMinHeight = 56.dp
private val TopBarHorizontalPadding = 12.dp
private val TopBarVerticalPadding = 12.dp
private val TopBarItemSpacing = 8.dp
private val TopBarTitleSpacing = 12.dp

sealed interface OpenCodeTopBarLeading {
    data object None : OpenCodeTopBarLeading

    class Back(
        val onClick: () -> Unit,
        val contentDescription: String = "Back",
    ) : OpenCodeTopBarLeading

    class Close(
        val onClick: () -> Unit,
        val contentDescription: String = "Close",
    ) : OpenCodeTopBarLeading

    class CustomIcon(
        val imageVector: androidx.compose.ui.graphics.vector.ImageVector,
        val contentDescription: String,
        val onClick: () -> Unit,
    ) : OpenCodeTopBarLeading
}

sealed interface OpenCodeTopBarAction {
    class Icon(
        val imageVector: androidx.compose.ui.graphics.vector.ImageVector,
        val contentDescription: String,
        val enabled: Boolean = true,
        val onClick: () -> Unit,
    ) : OpenCodeTopBarAction

    class Label(
        val title: String,
        val enabled: Boolean = true,
        val onClick: () -> Unit,
    ) : OpenCodeTopBarAction
}

@Composable
fun OpenCodeTopBar(
    modifier: Modifier = Modifier,
    title: String? = null,
    leading: OpenCodeTopBarLeading = OpenCodeTopBarLeading.None,
    actions: List<OpenCodeTopBarAction> = emptyList(),
    showDivider: Boolean = false,
) {
    require(actions.size <= 3) { "OpenCodeTopBar supports up to 3 trailing actions." }
    require(actions.count { it is OpenCodeTopBarAction.Label } <= 1) {
        "OpenCodeTopBar supports at most 1 labeled trailing action."
    }

    val resolvedTitle = title?.takeIf { it.isNotBlank() }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = TopBarMinHeight)
                    .padding(horizontal = TopBarHorizontalPadding, vertical = TopBarVerticalPadding),
            horizontalArrangement = Arrangement.spacedBy(TopBarItemSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LeadingControl(leading = leading)

            if (resolvedTitle != null) {
                Text(
                    text = resolvedTitle,
                    modifier = Modifier.weight(1f).padding(start = if (leading == OpenCodeTopBarLeading.None) 0.dp else TopBarTitleSpacing),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            TrailingActions(actions = actions)
        }

        if (showDivider) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun LeadingControl(leading: OpenCodeTopBarLeading) {
    when (leading) {
        OpenCodeTopBarLeading.None -> Unit
        is OpenCodeTopBarLeading.Back ->
            OpenCodeButton(
                variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.ArrowBack, leading.contentDescription),
                contrast = OpenCodeButtonContrast.Low,
                onClick = leading.onClick,
            )
        is OpenCodeTopBarLeading.Close ->
            OpenCodeButton(
                variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.Close, leading.contentDescription),
                contrast = OpenCodeButtonContrast.Low,
                onClick = leading.onClick,
            )
        is OpenCodeTopBarLeading.CustomIcon ->
            OpenCodeButton(
                variant = OpenCodeButtonVariant.IconCircle(leading.imageVector, leading.contentDescription),
                contrast = OpenCodeButtonContrast.Low,
                onClick = leading.onClick,
            )
    }
}

@Composable
private fun TrailingActions(actions: List<OpenCodeTopBarAction>) {
    if (actions.isEmpty()) return

    Row(
        horizontalArrangement = Arrangement.spacedBy(TopBarItemSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEach { action ->
            when (action) {
                is OpenCodeTopBarAction.Icon ->
                    OpenCodeButton(
                        variant = OpenCodeButtonVariant.IconCircle(action.imageVector, action.contentDescription),
                        enabled = action.enabled,
                        contrast = OpenCodeButtonContrast.Low,
                        onClick = action.onClick,
                    )
                is OpenCodeTopBarAction.Label ->
                    OpenCodeButton(
                        variant = OpenCodeButtonVariant.CompactText(action.title),
                        enabled = action.enabled,
                        contrast = OpenCodeButtonContrast.Low,
                        onClick = action.onClick,
                    )
            }
        }
    }
}

@Composable
private fun OpenCodeTopBarPreviewContent() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = "Title only", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        OpenCodeTopBar(title = "Component Gallery")

        Text(text = "Back + divider", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        OpenCodeTopBar(
            title = "Session",
            leading = OpenCodeTopBarLeading.Back(onClick = {}),
            showDivider = true,
        )

        Text(text = "Close + icon actions", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        OpenCodeTopBar(
            title = "Session actions",
            leading = OpenCodeTopBarLeading.Close(onClick = {}),
            actions =
                listOf(
                    OpenCodeTopBarAction.Icon(OpenCodeIcons.Search, "Search", onClick = {}),
                    OpenCodeTopBarAction.Icon(OpenCodeIcons.MoreVert, "More actions", onClick = {}),
                ),
        )

        Text(text = "Labeled action", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        OpenCodeTopBar(
            title = "Project settings",
            actions = listOf(OpenCodeTopBarAction.Label("Done", onClick = {})),
        )

        Text(text = "Disabled action", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        OpenCodeTopBar(
            title = "Rename session",
            leading = OpenCodeTopBarLeading.CustomIcon(OpenCodeIcons.Edit, "Edit", onClick = {}),
            actions = listOf(OpenCodeTopBarAction.Label("Save", enabled = false, onClick = {})),
            showDivider = true,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeTopBarLightPreview() {
    OpenCodeTheme(darkTheme = false) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OpenCodeTopBarPreviewContent()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeTopBarDarkPreview() {
    OpenCodeTheme(darkTheme = true) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OpenCodeTopBarPreviewContent()
        }
    }
}
