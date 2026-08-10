package dev.opencode.mobile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.badgeColors
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private val TopBarMinHeight = 56.dp
private val TopBarHorizontalPadding = 12.dp
private val TopBarVerticalPadding = 12.dp
private val TopBarItemSpacing = 8.dp
private val TopBarTitleSpacing = 12.dp

/** Screen-edge inset for the title when leading is None. */
private val TopBarTitleEdgeInsetWhenNoLeading = 18.dp
private val TopBarSubtitleSpacing = 0.dp
private val TopBarTitleBlockHeight = 48.dp
private val TopBarSubtitleMetaSpacing = 6.dp
private val TopBarLetterBadgeSize = 16.dp
private val TopBarLetterBadgeRadius = 4.dp
private val TopBarLetterGlyphSize = 9.sp

sealed interface OpenCodeTopBarSubtitleAccessory {
    data class StatusDot(
        val status: OpenCodeSessionConnectionStatus,
    ) : OpenCodeTopBarSubtitleAccessory
}

sealed interface OpenCodeTopBarSubtitleLeading {
    data class Letter(
        val character: Char,
        val accent: LetterAccent,
    ) : OpenCodeTopBarSubtitleLeading
}

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
    subtitle: String? = null,
    subtitleLeading: OpenCodeTopBarSubtitleLeading? = null,
    subtitleAccessory: OpenCodeTopBarSubtitleAccessory? = null,
    leading: OpenCodeTopBarLeading = OpenCodeTopBarLeading.None,
    actions: List<OpenCodeTopBarAction> = emptyList(),
    showDivider: Boolean = false,
) {
    require(actions.size <= 3) { "OpenCodeTopBar supports up to 3 trailing actions." }
    require(actions.count { it is OpenCodeTopBarAction.Label } <= 1) {
        "OpenCodeTopBar supports at most 1 labeled trailing action."
    }

    val resolvedTitle = title?.takeIf { it.isNotBlank() }
    val resolvedSubtitle = subtitle?.takeIf { it.isNotBlank() }
    val resolvedLeading =
        if (resolvedSubtitle != null || subtitleAccessory != null) subtitleLeading else null

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
                val titleStartPadding =
                    if (leading == OpenCodeTopBarLeading.None) {
                        (TopBarTitleEdgeInsetWhenNoLeading - TopBarHorizontalPadding).coerceAtLeast(0.dp)
                    } else {
                        TopBarTitleSpacing
                    }
                TopBarTitleBlock(
                    title = resolvedTitle,
                    subtitle = resolvedSubtitle,
                    subtitleLeading = resolvedLeading,
                    subtitleAccessory = subtitleAccessory,
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(start = titleStartPadding),
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
private fun TopBarTitleBlock(
    title: String,
    subtitle: String?,
    subtitleLeading: OpenCodeTopBarSubtitleLeading?,
    subtitleAccessory: OpenCodeTopBarSubtitleAccessory?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .height(TopBarTitleBlockHeight)
                .semantics {
                    contentDescription =
                        titleBlockAccessibilityLabel(title, subtitle, subtitleLeading, subtitleAccessory)
                },
        contentAlignment = Alignment.CenterStart,
    ) {
        if (subtitle != null || subtitleAccessory != null) {
            Column(
                modifier = Modifier.align(Alignment.CenterStart),
                verticalArrangement = Arrangement.spacedBy(TopBarSubtitleSpacing),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = openCodeColor("text-strong"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TopBarSubtitleLine(
                    subtitle = subtitle,
                    subtitleLeading = subtitleLeading,
                    subtitleAccessory = subtitleAccessory,
                )
            }
        } else {
            Text(
                text = title,
                modifier = Modifier.align(Alignment.CenterStart),
                style = MaterialTheme.typography.titleLarge,
                color = openCodeColor("text-strong"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TopBarSubtitleLine(
    subtitle: String?,
    subtitleLeading: OpenCodeTopBarSubtitleLeading?,
    subtitleAccessory: OpenCodeTopBarSubtitleAccessory?,
) {
    val hasSubtitle = subtitle != null
    val status =
        when (subtitleAccessory) {
            is OpenCodeTopBarSubtitleAccessory.StatusDot -> subtitleAccessory.status
            null -> null
        }
    val letter = subtitleLeading as? OpenCodeTopBarSubtitleLeading.Letter

    if (!hasSubtitle && status != null) {
        OpenCodeConnectionStatusLabel(status = status)
        return
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TopBarSubtitleMetaSpacing),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (letter != null && hasSubtitle) {
            TopBarLetterBadge(character = letter.character, accent = letter.accent)
        }
        if (subtitle != null) {
            Text(
                text = subtitle,
                modifier = Modifier.weight(1f, fill = false).widthIn(max = 220.dp),
                style = MaterialTheme.typography.bodySmall,
                color = openCodeColor("text-base"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (hasSubtitle && status != null) {
            Text(
                text = "·",
                style = MaterialTheme.typography.bodySmall,
                color = openCodeColor("text-weaker"),
            )
        }
        if (status != null) {
            OpenCodeConnectionStatusLabel(status = status)
        }
    }
}

@Composable
private fun TopBarLetterBadge(
    character: Char,
    accent: LetterAccent,
) {
    val dark = isSystemInDarkTheme()
    val (fill, tint) = accent.badgeColors(dark)
    val letter = character.uppercaseChar().toString()
    val textMeasurer = rememberTextMeasurer()
    val textStyle =
        TextStyle(
            color = tint,
            fontSize = TopBarLetterGlyphSize,
            fontWeight = FontWeight.Bold,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle =
                LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                ),
        )
    Canvas(
        modifier =
            Modifier
                .size(TopBarLetterBadgeSize)
                .clip(RoundedCornerShape(TopBarLetterBadgeRadius))
                .background(fill)
                .semantics { hideFromAccessibility() },
    ) {
        val layout = textMeasurer.measure(text = AnnotatedString(letter), style = textStyle)
        // Center on glyph ink bounds; layout width/side bearings leave O/etc. visually left-biased.
        val ink = layout.getBoundingBox(0)
        drawText(
            textLayoutResult = layout,
            topLeft =
                Offset(
                    x = (size.width - ink.width) / 2f - ink.left,
                    y = (size.height - ink.height) / 2f - ink.top,
                ),
        )
    }
}

private fun titleBlockAccessibilityLabel(
    title: String,
    subtitle: String?,
    subtitleLeading: OpenCodeTopBarSubtitleLeading?,
    subtitleAccessory: OpenCodeTopBarSubtitleAccessory?,
): String {
    val parts = mutableListOf<String>()
    if (subtitle != null) {
        val letter =
            (subtitleLeading as? OpenCodeTopBarSubtitleLeading.Letter)
                ?.character
                ?.uppercaseChar()
                ?.toString()
        parts += if (letter != null) "Project $letter $subtitle" else subtitle
    }
    when (subtitleAccessory) {
        is OpenCodeTopBarSubtitleAccessory.StatusDot ->
            parts += "Live updates ${subtitleAccessory.status.label}"
        null -> Unit
    }
    return if (parts.isEmpty()) title else parts.joinToString(prefix = "$title, ", separator = ", ")
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

        Text(text = "Two-line title", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        OpenCodeTopBar(
            title = "Session",
            subtitle = "Importing icons explanation",
            leading = OpenCodeTopBarLeading.Back(onClick = {}),
            actions = listOf(OpenCodeTopBarAction.Icon(OpenCodeIcons.MoreVert, "More actions", onClick = {})),
            showDivider = true,
        )

        Text(text = "Status subtitle", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        OpenCodeTopBar(
            title = "Importing icons explanation",
            subtitleAccessory = OpenCodeTopBarSubtitleAccessory.StatusDot(OpenCodeSessionConnectionStatus.Live),
            leading = OpenCodeTopBarLeading.Back(onClick = {}),
            actions = listOf(OpenCodeTopBarAction.Icon(OpenCodeIcons.MoreVert, "More actions", onClick = {})),
            showDivider = true,
        )

        Text(text = "Project + status", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        OpenCodeTopBar(
            title = "Wire session project chrome",
            subtitle = "opencode-mobile",
            subtitleLeading = OpenCodeTopBarSubtitleLeading.Letter('O', LetterAccent.Blue),
            subtitleAccessory = OpenCodeTopBarSubtitleAccessory.StatusDot(OpenCodeSessionConnectionStatus.Live),
            leading = OpenCodeTopBarLeading.Back(onClick = {}),
            actions = listOf(OpenCodeTopBarAction.Icon(OpenCodeIcons.MoreVert, "More actions", onClick = {})),
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
