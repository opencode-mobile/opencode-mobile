package dev.opencode.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.badgeColors
import dev.opencode.mobile.theme.openCodeColor

private val ListItemLetterBadgeSize = 32.dp
private val ListItemLetterGlyphSize = 14.sp
private val ListItemIconDecoratorSize = 24.dp
private val ListItemCornerRadius = 8.dp
private val ListItemVerticalPadding = 12.dp
private val ListItemTitleSubtitleSpacing = 2.dp
private val ListItemMetaSpacing = 4.dp
private val StatusChipShape = RoundedCornerShape(999.dp)

enum class OpenCodeListItemStyle {
    Plain,
    Card,
}

enum class OpenCodeListItemSubtitleTone {
    Secondary,
    Addition,
    Deletion,
}

data class OpenCodeListItemSubtitleSegment(
    val text: String,
    val tone: OpenCodeListItemSubtitleTone,
)

enum class OpenCodeListItemStatusTone {
    Interactive,
    Success,
    Warning,
    Error,
    Info,
}

data class OpenCodeListItemStatus(
    val text: String,
    val tone: OpenCodeListItemStatusTone,
)

@Composable
fun OpenCodeListItem(
    title: String,
    subtitle: String? = null,
    subtitleSegments: List<OpenCodeListItemSubtitleSegment>? = null,
    status: OpenCodeListItemStatus? = null,
    caption: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    style: OpenCodeListItemStyle = OpenCodeListItemStyle.Plain,
    startDecorator: StartDecorator = StartDecorator.None,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    require(subtitle == null || subtitleSegments == null) {
        "List Item accepts either a plain subtitle or semantic segments"
    }
    val containerColor =
        when {
            style == OpenCodeListItemStyle.Card && selected -> MaterialTheme.colorScheme.surfaceVariant
            style == OpenCodeListItemStyle.Card -> MaterialTheme.colorScheme.surface
            selected -> MaterialTheme.colorScheme.surfaceVariant
            else -> Color.Transparent
        }
    val containerShape =
        if (style == OpenCodeListItemStyle.Plain && !selected) {
            RectangleShape
        } else {
            RoundedCornerShape(ListItemCornerRadius)
        }
    val contentColor =
        if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    val textStrong = openCodeColor("text-strong")
    val textBase = openCodeColor("text-base")
    val textWeaker = openCodeColor("text-weaker")
    val surfaceRaised = openCodeColor("surface-raised-base")
    val hasMeta = status != null || !caption.isNullOrEmpty()

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null) {
                        Modifier.clickable(enabled = enabled, onClick = onClick)
                    } else {
                        Modifier
                    },
                ),
        shape = containerShape,
        color = containerColor,
        contentColor = contentColor,
        border =
            if (style == OpenCodeListItemStyle.Card) {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            } else {
                null
            },
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = ListItemVerticalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ListItemStartDecoration(decorator = startDecorator, enabled = enabled)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ListItemTitleSubtitleSpacing),
            ) {
                Text(
                    text = title,
                    color = if (enabled) textStrong else textWeaker,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitleSegments != null) {
                    val disabledColor = textWeaker
                    Text(
                        text =
                            buildAnnotatedString {
                                subtitleSegments.forEach { segment ->
                                    val color =
                                        if (!enabled) {
                                            disabledColor
                                        } else {
                                            when (segment.tone) {
                                                OpenCodeListItemSubtitleTone.Secondary -> textBase
                                                OpenCodeListItemSubtitleTone.Addition -> openCodeColor("diff-add")
                                                OpenCodeListItemSubtitleTone.Deletion -> openCodeColor("diff-delete")
                                            }
                                        }
                                    withStyle(SpanStyle(color = color)) { append(segment.text) }
                                }
                            },
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (enabled) textBase else textWeaker,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (hasMeta) {
                ListItemMetaColumn(
                    status = status,
                    caption = caption,
                    enabled = enabled,
                    textWeaker = textWeaker,
                    surfaceRaised = surfaceRaised,
                )
            }

            if (trailing != null) {
                Box(contentAlignment = Alignment.Center) { trailing() }
            }
        }
    }
}

@Composable
private fun ListItemMetaColumn(
    status: OpenCodeListItemStatus?,
    caption: String?,
    enabled: Boolean,
    textWeaker: Color,
    surfaceRaised: Color,
) {
    Column(
        modifier = Modifier.width(IntrinsicSize.Max),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(ListItemMetaSpacing),
    ) {
        if (!caption.isNullOrEmpty()) {
            Text(
                text = caption,
                color = textWeaker,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (status != null) {
            val foreground = if (enabled) status.tone.foreground() else textWeaker
            val background = if (enabled) status.tone.background(surfaceRaised) else surfaceRaised
            Text(
                text = status.text,
                color = foreground,
                textAlign = TextAlign.Center,
                style =
                    MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                maxLines = 1,
                modifier =
                    Modifier
                        .clip(StatusChipShape)
                        .background(background)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun OpenCodeListItemStatusTone.foreground(): Color =
    when (this) {
        OpenCodeListItemStatusTone.Interactive -> openCodeColor("interactive")
        OpenCodeListItemStatusTone.Success -> openCodeColor("success")
        OpenCodeListItemStatusTone.Warning -> openCodeColor("warning")
        OpenCodeListItemStatusTone.Error -> openCodeColor("error")
        OpenCodeListItemStatusTone.Info -> openCodeColor("text-weaker")
    }

@Composable
private fun OpenCodeListItemStatusTone.background(surfaceRaised: Color): Color =
    when (this) {
        OpenCodeListItemStatusTone.Interactive -> openCodeColor("interactive").copy(alpha = 0.12f)
        OpenCodeListItemStatusTone.Success -> openCodeColor("success").copy(alpha = 0.12f)
        OpenCodeListItemStatusTone.Warning -> openCodeColor("warning").copy(alpha = 0.12f)
        OpenCodeListItemStatusTone.Error -> openCodeColor("error").copy(alpha = 0.12f)
        OpenCodeListItemStatusTone.Info -> surfaceRaised
    }

@Composable
private fun ListItemStartDecoration(
    decorator: StartDecorator,
    enabled: Boolean,
) {
    if (decorator is StartDecorator.None) return
    val dark = isSystemInDarkTheme()
    val emphasize =
        Modifier.then(
            if (enabled) Modifier else Modifier.alpha(0.7f),
        )
    when (decorator) {
        is StartDecorator.Letter -> {
            val (fill, tint) = decorator.accent.badgeColors(dark)
            Box(
                modifier =
                    Modifier
                        .size(ListItemLetterBadgeSize)
                        .clip(CircleShape)
                        .background(fill)
                        .semantics { hideFromAccessibility() }
                        .then(emphasize),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = normalizedBadgeLetter(decorator.character),
                    fontSize = ListItemLetterGlyphSize,
                    fontWeight = FontWeight.Bold,
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
                modifier = Modifier.size(ListItemIconDecoratorSize).then(emphasize),
                tint = tint,
            )
        }
        is StartDecorator.None -> Unit
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeListItemPreview() {
    OpenCodeTheme(darkTheme = true) {
        Column {
            OpenCodeListItem(
                title = "Material Design Icons integration plan",
                subtitle = "http://127.0.0.1:4096/",
                status = OpenCodeListItemStatus(text = "Working · 14m", tone = OpenCodeListItemStatusTone.Interactive),
                caption = "sidebar-v2-concepts",
                startDecorator = StartDecorator.Letter('A', LetterAccent.Violet),
            )
            OpenCodeListItem(
                title = "contact.html",
                subtitleSegments =
                    listOf(
                        OpenCodeListItemSubtitleSegment("Modified · ", OpenCodeListItemSubtitleTone.Secondary),
                        OpenCodeListItemSubtitleSegment("+51", OpenCodeListItemSubtitleTone.Addition),
                        OpenCodeListItemSubtitleSegment(" ", OpenCodeListItemSubtitleTone.Secondary),
                        OpenCodeListItemSubtitleSegment("−7", OpenCodeListItemSubtitleTone.Deletion),
                    ),
            )
            OpenCodeListItem(
                title = "Sidebar v2 UX Redesign",
                subtitle = "~/Projects/opencode-mobile",
                status = OpenCodeListItemStatus(text = "Running", tone = OpenCodeListItemStatusTone.Interactive),
                caption = "now",
                startDecorator = StartDecorator.Letter('t', LetterAccent.Blue),
            )
            OpenCodeListItem(
                title = "Idle session",
                subtitle = "~/Projects/playground",
                status = OpenCodeListItemStatus(text = "Idle", tone = OpenCodeListItemStatusTone.Info),
                caption = "14m ago",
                startDecorator = StartDecorator.Letter('P', LetterAccent.Teal),
            )
        }
    }
}
