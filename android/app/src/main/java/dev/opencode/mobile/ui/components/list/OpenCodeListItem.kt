package dev.opencode.mobile.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.theme.OpenCodePillShape
import dev.opencode.mobile.theme.OpenCodeRadius
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.badgeColors
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private val ListItemLetterBadgeSize = 32.dp
private val ListItemLetterGlyphSize = 14.sp
private val ListItemLetterStatusDotSize = 10.dp
private val ListItemIconDecoratorSize = 24.dp
private val ListItemIconCircleGlyphSize = 16.dp
private val ListItemCornerRadius = OpenCodeRadius.small

// Plain-style selection and press highlights are inset from the row edges so they read as
// a compact rounded highlight; the row frame, padding, and tap target are unchanged.
private val ListItemSelectionHorizontalInset = 8.dp
private val ListItemSelectionVerticalInset = 2.dp
private val ListItemSelectionShape = RoundedCornerShape(OpenCodeRadius.medium)
private val ListItemVerticalPadding = 12.dp
private val ListItemTitleSubtitleSpacing = 2.dp
private val ListItemMetaSpacing = 4.dp
private val SubtitleIconWidth = 1.15.em
private val SubtitleIconHeight = 1.em
private val ListItemTitleTagSpacing = 6.dp
private val StatusChipShape = OpenCodePillShape
private const val ListItemPressedScale = 0.965f
private const val ListItemPopPeakScale = 1.035f

private fun subtitleIconId(index: Int): String = "subtitle-icon-$index"

enum class OpenCodeListItemStyle {
    Plain,
    Card,
}

enum class OpenCodeListItemVariant {
    /** Title, subtitle and detail lines, with caption and status stacked at the end. */
    Standard,

    /**
     * Two lines of fixed height: the title and caption, then a path and a coloured status label.
     * Pairs with [StartDecorator.Tile].
     */
    Compact,
}

enum class OpenCodeListItemSubtitleTone {
    Secondary,

    /** Quieter than [Secondary], for the detail line under the subtitle. */
    Tertiary,
    Addition,
    Deletion,
}

data class OpenCodeListItemSubtitleSegment(
    val text: String,
    val tone: OpenCodeListItemSubtitleTone,
    /** Small mark drawn before [text] in the segment's color (e.g. a branch). */
    val icon: ImageVector? = null,
)

enum class OpenCodeListItemStatusTone {
    Interactive,
    Success,
    Warning,
    Error,
    Info,
    Stale,
}

data class OpenCodeListItemStatus(
    val text: String,
    val tone: OpenCodeListItemStatusTone,
)

/** Built-in trailing control. Custom trailing composables stay available for anything else. */
sealed class OpenCodeListItemAccessory {
    /** Navigation chevron. The row click owns the tap. */
    data object Disclosure : OpenCodeListItemAccessory()

    /** Switch. The row should not also take a click. */
    data class Toggle(
        val checked: Boolean,
        val onCheckedChange: (Boolean) -> Unit,
    ) : OpenCodeListItemAccessory()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OpenCodeListItem(
    title: String,
    subtitle: String? = null,
    subtitleSegments: List<OpenCodeListItemSubtitleSegment>? = null,
    /** Optional third line under the subtitle, such as a branch. Truncates on its own. */
    detailSegments: List<OpenCodeListItemSubtitleSegment>? = null,
    status: OpenCodeListItemStatus? = null,
    caption: String? = null,
    /** Small tag after the title, such as "git". The title truncates; the tag never does. */
    titleTag: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    style: OpenCodeListItemStyle = OpenCodeListItemStyle.Plain,
    startDecorator: StartDecorator = StartDecorator.None,
    accessory: OpenCodeListItemAccessory? = null,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    /** [OpenCodeListItemVariant.Compact] draws two fixed lines: title and caption, then [path] and status. */
    variant: OpenCodeListItemVariant = OpenCodeListItemVariant.Standard,
    /** Compact line 2: `project / worktree`. Falls back to [subtitle]. */
    path: OpenCodeListItemPath? = null,
    /** Compact title in a heavier weight, for finished work not opened yet. */
    titleEmphasized: Boolean = false,
) {
    require(subtitle == null || subtitleSegments == null) {
        "List Item accepts either a plain subtitle or semantic segments"
    }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current
    val popScale = remember { Animatable(1f) }
    var popTick by remember { mutableIntStateOf(0) }
    var isPopping by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (enabled && isPressed && !isPopping) ListItemPressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMedium),
        label = "listItemPressScale",
    )
    LaunchedEffect(popTick) {
        if (popTick == 0) return@LaunchedEffect
        isPopping = true
        popScale.snapTo(ListItemPressedScale)
        popScale.animateTo(
            ListItemPopPeakScale,
            animationSpec = spring(dampingRatio = 0.48f, stiffness = Spring.StiffnessMediumLow),
        )
        popScale.animateTo(
            1f,
            animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium),
        )
        isPopping = false
    }
    val combinedScale = if (isPopping) popScale.value else pressScale
    val surfaceBase = openCodeColor("surface-base")
    val surfaceRaised = openCodeColor("surface-raised-base")
    val isPlain = style == OpenCodeListItemStyle.Plain
    val containerColor =
        when {
            style == OpenCodeListItemStyle.Card && selected -> surfaceRaised
            style == OpenCodeListItemStyle.Card -> surfaceBase
            else -> Color.Transparent
        }
    val plainHighlightColor =
        when {
            selected -> surfaceRaised
            enabled && isPressed -> surfaceRaised.copy(alpha = 0.92f)
            else -> Color.Transparent
        }
    // Plain rows draw their ripple on the inset highlight layer below, so the row-level
    // clickable keeps the full-width tap target without a full-bleed ripple.
    val rowIndication = if (isPlain) null else ripple(bounded = true)
    val containerShape = RoundedCornerShape(ListItemCornerRadius)
    val contentColor =
        if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    val textStrong = openCodeColor("text-strong")
    val textBase = openCodeColor("text-base")
    val textWeaker = openCodeColor("text-weaker")
    val borderWeaker = openCodeColor("border-weaker-base")
    val hasMeta = status != null || !caption.isNullOrEmpty()
    val playContextPop: () -> Unit = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        popTick += 1
    }
    val interactionModifier =
        when {
            onClick != null && onLongPress != null ->
                Modifier.combinedClickable(
                    interactionSource = interactionSource,
                    indication = rowIndication,
                    enabled = enabled,
                    onClick = onClick,
                    onLongClick = {
                        playContextPop()
                        onLongPress()
                    },
                    onLongClickLabel = "Show actions",
                )
            onClick != null ->
                Modifier.clickable(
                    interactionSource = interactionSource,
                    indication = rowIndication,
                    enabled = enabled,
                    onClick = onClick,
                )
            onLongPress != null ->
                Modifier.combinedClickable(
                    interactionSource = interactionSource,
                    indication = rowIndication,
                    enabled = enabled,
                    onClick = {},
                    onLongClick = {
                        playContextPop()
                        onLongPress()
                    },
                    onLongClickLabel = "Show actions",
                )
            else -> Modifier
        }

    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Box(
            modifier =
                modifier
                    .fillMaxWidth()
                    .scale(combinedScale)
                    .clip(containerShape)
                    .background(containerColor, containerShape)
                    .then(
                        if (style == OpenCodeListItemStyle.Card) {
                            Modifier.border(1.dp, borderWeaker, containerShape)
                        } else {
                            Modifier
                        },
                    ).then(interactionModifier),
        ) {
            if (isPlain) {
                Box(
                    modifier =
                        Modifier
                            .matchParentSize()
                            .padding(
                                horizontal = ListItemSelectionHorizontalInset,
                                vertical = ListItemSelectionVerticalInset,
                            ).clip(ListItemSelectionShape)
                            .background(plainHighlightColor, ListItemSelectionShape)
                            .indication(interactionSource, ripple(bounded = true)),
                )
            }
            if (variant == OpenCodeListItemVariant.Compact) {
                CompactListItemContent(
                    title = title,
                    titleEmphasized = titleEmphasized,
                    path = path,
                    subtitle = subtitle,
                    status = status,
                    caption = caption,
                    startDecorator = startDecorator,
                    enabled = enabled,
                )
            } else {
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
                        val titleText = @Composable { titleModifier: Modifier ->
                            Text(
                                text = title,
                                color = if (enabled) textStrong else textWeaker,
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = titleModifier,
                            )
                        }
                        if (titleTag.isNullOrBlank()) {
                            titleText(Modifier)
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(ListItemTitleTagSpacing),
                            ) {
                                titleText(Modifier.weight(1f, fill = false))
                                OpenCodeTag(title = titleTag, size = OpenCodeTagSize.Small)
                            }
                        }
                        if (subtitleSegments != null) {
                            ListItemSegmentsText(subtitleSegments, enabled)
                        } else if (subtitle != null) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (enabled) textBase else textWeaker,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (!detailSegments.isNullOrEmpty()) {
                            ListItemSegmentsText(detailSegments, enabled)
                        }
                    }

                    if (hasMeta) {
                        ListItemMetaColumn(
                            status = status,
                            caption = caption,
                            enabled = enabled,
                            textBase = textBase,
                            textWeaker = textWeaker,
                        )
                    }

                    if (trailing != null) {
                        Box(contentAlignment = Alignment.Center) { trailing() }
                    }

                    when (val itemAccessory = accessory) {
                        null -> Unit
                        OpenCodeListItemAccessory.Disclosure -> {
                            Icon(
                                imageVector = OpenCodeIcons.ChevronRight,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (enabled) openCodeColor("text-base") else openCodeColor("text-weaker"),
                            )
                        }
                        is OpenCodeListItemAccessory.Toggle -> {
                            Switch(
                                checked = itemAccessory.checked,
                                onCheckedChange = itemAccessory.onCheckedChange,
                                enabled = enabled,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ListItemMetaColumn(
    status: OpenCodeListItemStatus?,
    caption: String?,
    enabled: Boolean,
    textBase: Color,
    textWeaker: Color,
) {
    Column(
        modifier = Modifier.width(IntrinsicSize.Max),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(ListItemMetaSpacing),
    ) {
        if (!caption.isNullOrEmpty()) {
            Text(
                text = caption,
                color = if (enabled) textBase else textWeaker,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth().padding(end = 2.dp),
            )
        }
        if (status != null) {
            ListItemStatusPill(status = status, enabled = enabled)
        }
    }
}

/** Status chip. */
@Composable
internal fun ListItemStatusPill(
    status: OpenCodeListItemStatus,
    enabled: Boolean,
) {
    val foreground = if (enabled) status.tone.foreground() else openCodeColor("text-weaker")
    val background = if (enabled) status.tone.background() else openCodeColor("surface-raised-base")
    Text(
        text = status.text,
        color = foreground,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        maxLines = 1,
        modifier =
            Modifier
                .clip(StatusChipShape)
                .background(background)
                .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
internal fun OpenCodeListItemStatusTone.foreground(): Color =
    when (this) {
        OpenCodeListItemStatusTone.Interactive -> openCodeColor("interactive")
        OpenCodeListItemStatusTone.Success -> openCodeColor("success")
        OpenCodeListItemStatusTone.Warning -> openCodeColor("warning")
        OpenCodeListItemStatusTone.Error -> openCodeColor("error")
        OpenCodeListItemStatusTone.Info -> openCodeColor("text-base")
        OpenCodeListItemStatusTone.Stale -> openCodeColor("text-weak")
    }

@Composable
internal fun OpenCodeListItemStatusTone.background(): Color =
    when (this) {
        OpenCodeListItemStatusTone.Interactive -> openCodeColor("interactive").copy(alpha = 0.12f)
        OpenCodeListItemStatusTone.Success -> openCodeColor("success").copy(alpha = 0.12f)
        OpenCodeListItemStatusTone.Warning -> openCodeColor("warning").copy(alpha = 0.12f)
        OpenCodeListItemStatusTone.Error -> openCodeColor("error").copy(alpha = 0.12f)
        OpenCodeListItemStatusTone.Info -> openCodeColor("text-strong").copy(alpha = 0.10f)
        OpenCodeListItemStatusTone.Stale -> openCodeColor("text-strong").copy(alpha = 0.08f)
    }

@Composable
internal fun ListItemStartDecoration(
    decorator: StartDecorator,
    enabled: Boolean,
) {
    if (decorator is StartDecorator.None) return
    val emphasize =
        Modifier.then(
            if (enabled) Modifier else Modifier.alpha(0.7f),
        )
    when (decorator) {
        is StartDecorator.Letter -> {
            val backgroundBase = openCodeColor("background-base")
            Box(
                modifier =
                    Modifier
                        .size(ListItemLetterBadgeSize)
                        .then(emphasize),
            ) {
                ListItemLetterCircle(
                    character = decorator.character,
                    accent = decorator.accent,
                    glyphSize = ListItemLetterGlyphSize,
                    modifier = Modifier.fillMaxSize(),
                )
                val statusTone = decorator.statusDot
                val showWorktree = decorator.worktree
                if (statusTone != null && showWorktree) {
                    Box(
                        modifier =
                            Modifier
                                .align(Alignment.BottomEnd)
                                .offset(x = 1.dp, y = 1.dp)
                                .size(ListItemLetterStatusDotSize + 4.dp)
                                .border(2.dp, backgroundBase, CircleShape)
                                .clip(CircleShape)
                                .background(statusTone.foreground()),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = OpenCodeIcons.CallSplit,
                            contentDescription = "Worktree",
                            modifier = Modifier.size(10.dp),
                            tint = backgroundBase,
                        )
                    }
                } else {
                    statusTone?.let { tone ->
                        Box(
                            modifier =
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .offset(x = 1.dp, y = 1.dp)
                                    .size(ListItemLetterStatusDotSize)
                                    .border(2.dp, backgroundBase, CircleShape)
                                    .clip(CircleShape)
                                    .background(tone.foreground())
                                    .semantics { hideFromAccessibility() },
                        )
                    }
                    if (showWorktree) {
                        Box(
                            modifier =
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .offset(x = 1.dp, y = 1.dp)
                                    .size(ListItemLetterStatusDotSize + 4.dp)
                                    .border(2.dp, backgroundBase, CircleShape)
                                    .clip(CircleShape)
                                    .background(openCodeColor("surface-raised-base")),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = OpenCodeIcons.CallSplit,
                                contentDescription = "Worktree",
                                modifier = Modifier.size(10.dp),
                                tint = openCodeColor("icon-strong-base"),
                            )
                        }
                    }
                }
            }
        }
        is StartDecorator.Icon -> {
            val tint =
                if (enabled) {
                    openCodeColor("icon-strong-base")
                } else {
                    openCodeColor("icon-base")
                }
            Icon(
                imageVector = decorator.imageVector,
                contentDescription = decorator.contentDescription,
                modifier = Modifier.size(ListItemIconDecoratorSize).then(emphasize),
                tint = tint,
            )
        }
        is StartDecorator.IconCircle -> {
            val textStrong = openCodeColor("text-strong")
            Box(
                modifier =
                    Modifier
                        .size(ListItemLetterBadgeSize)
                        .clip(CircleShape)
                        .background(textStrong.copy(alpha = 0.10f))
                        .then(emphasize),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = decorator.imageVector,
                    contentDescription = decorator.contentDescription,
                    modifier = Modifier.size(ListItemIconCircleGlyphSize),
                    tint = textStrong,
                )
            }
        }
        is StartDecorator.Tile -> ListItemTile(decorator = decorator, modifier = emphasize)
        is StartDecorator.None -> Unit
    }
}

/** Round project letter badge in its letter accent colours; the caller sets the size. */
@Composable
internal fun ListItemLetterCircle(
    character: Char,
    accent: LetterAccent,
    glyphSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    val (fill, tint) = accent.badgeColors(isSystemInDarkTheme())
    Box(
        modifier =
            modifier
                .clip(CircleShape)
                .background(fill)
                .semantics { hideFromAccessibility() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = normalizedBadgeLetter(character),
            fontSize = glyphSize,
            fontWeight = FontWeight.Bold,
            color = tint,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/** One line of semantic segments with inline icons, cut with an ellipsis at its end. */
@Composable
private fun ListItemSegmentsText(
    segments: List<OpenCodeListItemSubtitleSegment>,
    enabled: Boolean,
) {
    val textBase = openCodeColor("text-base")
    val textWeak = openCodeColor("text-weak")
    val textWeaker = openCodeColor("text-weaker")
    val diffAdd = openCodeColor("diff-add")
    val diffDelete = openCodeColor("diff-delete")
    val segmentColors =
        segments.map { segment ->
            if (!enabled) {
                textWeaker
            } else {
                when (segment.tone) {
                    OpenCodeListItemSubtitleTone.Secondary -> textBase
                    OpenCodeListItemSubtitleTone.Tertiary -> textWeak
                    OpenCodeListItemSubtitleTone.Addition -> diffAdd
                    OpenCodeListItemSubtitleTone.Deletion -> diffDelete
                }
            }
        }
    val inlineIcons =
        segments
            .mapIndexedNotNull { index, segment ->
                val icon = segment.icon ?: return@mapIndexedNotNull null
                subtitleIconId(index) to
                    InlineTextContent(
                        Placeholder(
                            width = SubtitleIconWidth,
                            height = SubtitleIconHeight,
                            placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                        ),
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = segmentColors[index],
                            modifier = Modifier.fillMaxSize().padding(end = 2.dp),
                        )
                    }
            }.toMap()
    Text(
        text =
            buildAnnotatedString {
                segments.forEachIndexed { index, segment ->
                    // Decorative icon: alternate text must be non-empty, so use a zero-width space
                    // rather than the default replacement character screen readers announce.
                    if (segment.icon != null) appendInlineContent(subtitleIconId(index), "\u200B")
                    withStyle(SpanStyle(color = segmentColors[index])) { append(segment.text) }
                }
            },
        inlineContent = inlineIcons,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
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
                title = "Retry provider error",
                subtitle = "~/Projects/opencode-mobile",
                status = OpenCodeListItemStatus(text = "Retrying", tone = OpenCodeListItemStatusTone.Warning),
                caption = "now",
                startDecorator = StartDecorator.Letter('O', LetterAccent.Violet),
            )
            OpenCodeListItem(
                title = "Idle session",
                subtitle = "~/Projects/playground",
                status = OpenCodeListItemStatus(text = "Idle", tone = OpenCodeListItemStatusTone.Info),
                caption = "14m ago",
                startDecorator = StartDecorator.Letter('P', LetterAccent.Teal),
            )
            OpenCodeListItem(
                title = "Cached running session",
                subtitle = "~/Projects/opencode-mobile",
                status = OpenCodeListItemStatus(text = "Running", tone = OpenCodeListItemStatusTone.Stale),
                caption = "now",
                startDecorator = StartDecorator.Letter('O', LetterAccent.Violet),
            )
            OpenCodeListItem(
                title = "Keep split pane in step with the sidebar",
                subtitleSegments =
                    listOf(
                        OpenCodeListItemSubtitleSegment(
                            "opencode-mobile-enterprise-edition",
                            OpenCodeListItemSubtitleTone.Secondary,
                            OpenCodeIcons.Folder,
                        ),
                    ),
                detailSegments =
                    listOf(
                        OpenCodeListItemSubtitleSegment(
                            "feature/ope-1042-show-worktree-and-project-on-home-rows",
                            OpenCodeListItemSubtitleTone.Tertiary,
                            OpenCodeIcons.CallSplit,
                        ),
                        OpenCodeListItemSubtitleSegment(" · opencode-mobile-1042", OpenCodeListItemSubtitleTone.Tertiary),
                    ),
                status = OpenCodeListItemStatus(text = "Running", tone = OpenCodeListItemStatusTone.Interactive),
                caption = "13m ago",
                startDecorator = StartDecorator.Letter('O', LetterAccent.Red),
            )
            OpenCodeListItem(
                title = "All projects",
                subtitle = "8 projects",
                startDecorator = StartDecorator.IconCircle(OpenCodeIcons.Widgets, null),
            )
        }
    }
}
