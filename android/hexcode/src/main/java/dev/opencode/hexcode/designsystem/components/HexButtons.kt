package dev.opencode.hexcode.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.designsystem.HexTheme

/**
 * The full-width 50 pt primary button (`.primary`): `solid` fill, radius 12. [loading] shows a spinner beside the
 * label and takes no taps; disabled fades to 20 %.
 */
@Composable
fun HexPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val colors = HexTheme.colors
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(HexTheme.radius.l)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .height(50.dp)
                .pressScale(source, 0.98f)
                .alpha(if (enabled || loading) 1f else 0.2f)
                .clip(shape)
                .background(colors.solid)
                .clickable(source, null, enabled = enabled && !loading, role = Role.Button, onClick = onClick),
    ) {
        if (loading) HexSpinner(tint = colors.onSolid)
        Text(text, style = HexTheme.type.bodyStrong, color = colors.onSolid, maxLines = 1)
    }
}

/** A [HexButton]'s look: `secondary` (`panel2` and `line`), `primary` (`solid`) or `danger` (red text). */
enum class HexButtonStyle { Secondary, Primary, Danger }

/** 30 pt (`.btn`) or 38 pt (`.cbtn`). */
enum class HexButtonSize { Small, Regular }

/**
 * A small or regular button with a label, an optional leading icon, or only an icon (then [contentDescription] names
 * it and the button is square). A [Modifier] size can make the icon-only form bigger, to match a field beside it.
 */
@Composable
fun HexButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    icon: ImageVector? = null,
    contentDescription: String? = null,
    style: HexButtonStyle = HexButtonStyle.Secondary,
    size: HexButtonSize = HexButtonSize.Small,
    enabled: Boolean = true,
) {
    val colors = HexTheme.colors
    val type = HexTheme.type
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val height = if (size == HexButtonSize.Small) 30.dp else 38.dp
    val shape = RoundedCornerShape(if (size == HexButtonSize.Small) HexTheme.radius.s else HexTheme.radius.l)
    val fill by animateColorAsState(
        when {
            style == HexButtonStyle.Primary -> colors.solid
            pressed -> colors.panel3
            else -> colors.panel2
        },
        HexTheme.motion.press(),
        label = "button-fill",
    )
    val content =
        when (style) {
            HexButtonStyle.Primary -> colors.onSolid
            HexButtonStyle.Danger -> colors.red
            HexButtonStyle.Secondary -> colors.text1
        }
    val iconTint =
        when (style) {
            HexButtonStyle.Secondary -> colors.text2
            else -> content
        }
    val border =
        if (style == HexButtonStyle.Primary) {
            Color.Transparent
        } else if (pressed) {
            colors.line2
        } else {
            colors.line
        }
    val textStyle =
        when {
            size == HexButtonSize.Small -> type.small
            style == HexButtonStyle.Primary -> type.button.copy(fontWeight = FontWeight.SemiBold)
            else -> type.button
        }
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .then(if (text == null) Modifier.defaultMinSize(height, height) else Modifier.height(height))
                .alpha(if (enabled) 1f else 0.3f)
                .clip(shape)
                .background(fill)
                .border(BorderStroke(1.dp, border), shape)
                .clickable(source, null, enabled = enabled, role = Role.Button, onClick = onClick)
                .then(if (text != null) Modifier.padding(horizontal = if (size == HexButtonSize.Small) 11.dp else 16.dp) else Modifier)
                .then(
                    if (text == null &&
                        contentDescription != null
                    ) {
                        Modifier.semantics { this.contentDescription = contentDescription }
                    } else {
                        Modifier
                    },
                ),
    ) {
        if (icon != null) {
            val iconSize = if (size == HexButtonSize.Small) HexTheme.size.iconSmall else HexTheme.size.icon
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(iconSize))
        }
        if (text != null) Text(text, style = textStyle, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The top bar's 44 pt outlined circle (`.rbtn`): `tint(3.5)` fill, `tint(10)` border. [selected] turns the icon green
 * (an active goal).
 */
@Composable
fun HexRoundButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val colors = HexTheme.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(HexTheme.size.touch)
                .pressScale(source, 0.94f)
                .clip(CircleShape)
                .background(colors.tint(if (pressed) 7f else 3.5f))
                .border(1.dp, colors.tint(10f), CircleShape)
                .clickable(source, null, role = Role.Button, onClick = onClick)
                .semantics { this.contentDescription = contentDescription },
    ) {
        Icon(icon, null, tint = if (selected) colors.green else colors.tint(85f), modifier = Modifier.size(HexTheme.size.icon))
    }
}

/** What a [HexIconButton] is for: most are neutral; Stop is red. */
enum class HexIconButtonTone { Default, Stop }

/** The 46 pt borderless icon button (`.ghost`), radius 12. Disabled fades to 35 %. */
@Composable
fun HexIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: HexIconButtonTone = HexIconButtonTone.Default,
    enabled: Boolean = true,
) {
    val colors = HexTheme.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shape = RoundedCornerShape(HexTheme.radius.l)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .width(46.dp)
                .height(46.dp)
                .alpha(if (enabled) 1f else 0.35f)
                .clip(shape)
                .background(if (pressed) colors.hover else Color.Transparent)
                .clickable(source, null, enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { this.contentDescription = contentDescription },
    ) {
        val tint =
            when {
                tone == HexIconButtonTone.Stop -> colors.red
                pressed -> colors.text1
                else -> colors.text2
            }
        Icon(icon, null, tint = tint, modifier = Modifier.size(HexTheme.size.iconButton))
    }
}
