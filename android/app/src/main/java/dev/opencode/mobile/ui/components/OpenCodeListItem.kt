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

private val ListItemLetterBadgeSize = 36.dp
private val ListItemLetterBadgeCornerRadius = 6.dp
private val ListItemLetterGlyphSize = 18.sp
private val ListItemIconDecoratorSize = 24.dp
private val ListItemCornerRadius = 8.dp
private val ListItemStatusDotSize = 6.dp
private val ListItemStatusDotSpacing = 4.dp
private val ListItemFooterCaptionSpacing = 8.dp

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
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment =
                if (startDecorator is StartDecorator.Letter) {
                    Alignment.Top
                } else {
                    Alignment.CenterVertically
                },
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
                if (subtitleSegments != null) {
                    val disabledColor = MaterialTheme.colorScheme.onSurfaceVariant
                    Text(
                        text =
                            buildAnnotatedString {
                                subtitleSegments.forEach { segment ->
                                    val color =
                                        if (!enabled) {
                                            disabledColor
                                        } else {
                                            when (segment.tone) {
                                                OpenCodeListItemSubtitleTone.Secondary -> disabledColor
                                                OpenCodeListItemSubtitleTone.Addition -> openCodeColor("diff-add")
                                                OpenCodeListItemSubtitleTone.Deletion -> openCodeColor("diff-delete")
                                            }
                                        }
                                    withStyle(SpanStyle(color = color)) { append(segment.text) }
                                }
                            },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (status != null || caption != null) {
                    ListItemFooterRow(status = status, caption = caption, enabled = enabled)
                }
            }
            if (trailing != null) {
                Box(contentAlignment = Alignment.Center) { trailing() }
            }
        }
    }
}

@Composable
private fun ListItemFooterRow(
    status: OpenCodeListItemStatus?,
    caption: String?,
    enabled: Boolean,
) {
    val weakerColor = openCodeColor("text-weaker")
    Row(
        horizontalArrangement = Arrangement.spacedBy(ListItemFooterCaptionSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status != null) {
            val statusColor = if (enabled) status.tone.color() else weakerColor
            Row(
                horizontalArrangement = Arrangement.spacedBy(ListItemStatusDotSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(ListItemStatusDotSize)
                            .clip(CircleShape)
                            .background(statusColor),
                )
                Text(
                    text = status.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (caption != null) {
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = weakerColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun OpenCodeListItemStatusTone.color(): Color =
    when (this) {
        OpenCodeListItemStatusTone.Interactive -> openCodeColor("interactive")
        OpenCodeListItemStatusTone.Success -> openCodeColor("success")
        OpenCodeListItemStatusTone.Warning -> openCodeColor("warning")
        OpenCodeListItemStatusTone.Error -> openCodeColor("error")
        OpenCodeListItemStatusTone.Info -> openCodeColor("info")
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
                        .clip(RoundedCornerShape(ListItemLetterBadgeCornerRadius))
                        .background(fill)
                        .semantics { hideFromAccessibility() }
                        .then(emphasize),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = normalizedBadgeLetter(decorator.character),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = ListItemLetterGlyphSize),
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
    OpenCodeTheme(darkTheme = false) {
        OpenCodeListItem(
            title = "Material Design Icons integration plan",
            subtitle = "http://127.0.0.1:4096/",
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeListItemSemanticSubtitlePreview() {
    OpenCodeTheme(darkTheme = false) {
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
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeListItemStatusCaptionPreview() {
    OpenCodeTheme(darkTheme = true) {
        OpenCodeListItem(
            title = "Sidebar v2 UX Redesign",
            subtitle = "There's a local SQLite state database — I can look at the real threads…",
            status = OpenCodeListItemStatus(text = "Working · 14m", tone = OpenCodeListItemStatusTone.Interactive),
            caption = "sidebar-v2-concepts",
            startDecorator = StartDecorator.Letter('t', LetterAccent.Blue),
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
            status = OpenCodeListItemStatus(text = "Working · 14m", tone = OpenCodeListItemStatusTone.Interactive),
            caption = "sidebar-v2-concepts",
            enabled = false,
            startDecorator = StartDecorator.Letter('x', LetterAccent.Teal),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeListItemCardPreview() {
    OpenCodeTheme(darkTheme = false) {
        OpenCodeListItem(
            title = "Card style with border",
            subtitle = "http://127.0.0.1:4096/",
            style = OpenCodeListItemStyle.Card,
        )
    }
}
