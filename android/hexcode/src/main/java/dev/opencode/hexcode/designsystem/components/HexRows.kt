package dev.opencode.hexcode.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.designsystem.HexIcons
import dev.opencode.hexcode.designsystem.HexTheme

/** What ends a [HexRow]. */
enum class HexRowTrailing { None, Chevron, Check }

/**
 * A settings or first-run row (`.row`), [minHeight] 48 at least (model rows pass `HexTheme.size.modelRow`): a leading
 * [icon], the [title] with an optional mono [badge] and [subtitle], an optional [value] on the right, and a chevron or
 * a check. [selected] sets the title in semibold (the chosen model). It sits on the screen's 18 pt edge.
 */
@Composable
fun HexRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    value: String? = null,
    badge: String? = null,
    trailing: HexRowTrailing = HexRowTrailing.None,
    selected: Boolean = false,
    minHeight: Dp = HexTheme.size.row,
    onClick: (() -> Unit)? = null,
    /** Read after the row with a screen reader: "Not in this version yet" on a placeholder. */
    accessibilityNote: String? = null,
) {
    val colors = HexTheme.colors
    val type = HexTheme.type
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    // A screen reader hears "<title>, <value>", then the second line, then any note (app-plan.md, Accessibility).
    val spoken = listOfNotNull(listOfNotNull(title, value).joinToString(", "), subtitle, accessibilityNote).joinToString(". ")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .background(if (pressed) colors.hover else Color.Transparent)
                .then(
                    if (onClick != null) Modifier.clickable(source, null, role = Role.Button, onClick = onClick) else Modifier,
                ).clearAndSetSemantics {
                    this.selected = selected
                    contentDescription = spoken
                    if (onClick != null) role = Role.Button
                }.padding(horizontal = HexTheme.space.screen, vertical = 6.dp),
    ) {
        if (icon != null) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = colors.text2, modifier = Modifier.size(HexTheme.size.icon))
            }
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    title,
                    style = if (selected) type.row.copy(fontWeight = FontWeight.SemiBold) else type.row,
                    color = colors.text1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (badge != null) HexBadge(badge, mono = true)
            }
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = type.footnote,
                    color = colors.text3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        if (value != null) {
            Text(
                value,
                style = type.row,
                color = colors.text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = (LocalConfiguration.current.screenWidthDp * 0.4f).dp),
            )
        }
        when (trailing) {
            HexRowTrailing.None -> Unit
            HexRowTrailing.Chevron -> Icon(HexIcons.chevR, null, tint = colors.text3, modifier = Modifier.size(HexTheme.size.icon))
            HexRowTrailing.Check -> Icon(HexIcons.check, null, tint = colors.text1, modifier = Modifier.size(HexTheme.size.icon))
        }
    }
}

/**
 * A 62 pt project or folder row (`.pk-row`): a folder [icon], the [title] with an optional mono [badge], a
 * [subtitle], and a top-aligned [caption] (how long ago) or a [chevron]. Without [onClick] it only shows (Browse
 * folders' pinned bar).
 */
@Composable
fun HexPickerRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = HexIcons.folder,
    subtitle: String? = null,
    subtitleMono: Boolean = false,
    badge: String? = null,
    caption: String? = null,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = HexTheme.colors
    val type = HexTheme.type
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shape = RoundedCornerShape(HexTheme.radius.l)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp)
                .heightIn(min = HexTheme.size.pickerRow)
                .then(if (onClick != null) Modifier.pressScale(source, 0.975f) else Modifier)
                .clip(shape)
                .background(if (pressed) colors.hover else Color.Transparent)
                .then(
                    if (onClick != null) Modifier.clickable(source, null, role = Role.Button, onClick = onClick) else Modifier,
                ).semantics(mergeDescendants = true) {}
                .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = colors.text1, modifier = Modifier.size(HexTheme.size.iconButton))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    title,
                    style = type.bodyStrong,
                    color = colors.text1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (badge != null) HexBadge(badge, mono = true)
            }
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = if (subtitleMono) type.mono else type.caption,
                    color = colors.text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (caption != null) {
            Text(caption, style = type.caption, color = colors.text2, modifier = Modifier.align(Alignment.Top).padding(top = 4.dp))
        }
        if (chevron) Icon(HexIcons.chevR, null, tint = colors.text2, modifier = Modifier.size(HexTheme.size.icon))
    }
}

/** An upper-case section heading (`.pk-sec`). */
@Composable
fun HexSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text.uppercase(),
        style = HexTheme.type.sectionLabel,
        color = HexTheme.colors.text3,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = HexTheme.space.screen, end = HexTheme.space.screen, top = 18.dp, bottom = 6.dp)
                .semantics { heading() },
    )
}

/** A small bordered label (`.rbadge`, `experiment`); [mono] is the `git` and `default` tag (`.git`). */
@Composable
fun HexBadge(
    text: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
) {
    val shape = RoundedCornerShape(HexTheme.radius.xs)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .heightIn(min = if (mono) 17.dp else 20.dp)
                .border(1.dp, HexTheme.colors.line2, shape)
                .padding(horizontal = if (mono) 5.dp else 7.dp),
    ) {
        Text(
            text,
            style = if (mono) HexTheme.type.monoSmall else HexTheme.type.badge,
            color = HexTheme.colors.text2,
            maxLines = 1,
        )
    }
}
