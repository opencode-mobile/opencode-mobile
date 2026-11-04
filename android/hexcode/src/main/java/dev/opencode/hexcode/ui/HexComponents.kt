package dev.opencode.hexcode.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.hexcode.theme.HexTheme

/** The page edge every first-run screen sits on (the prototype's 18 pt). */
val HexEdge: Dp = 18.dp

/** A press that shrinks [scale] a little, as the prototype's `:active` states do. */
@Composable
private fun pressScale(
    interaction: MutableInteractionSource,
    pressed: Float,
): Float {
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) pressed else 1f, tween(150), label = "press")
    return scale
}

/** The one solid, full-width action at the bottom of a screen. */
@Composable
fun HexPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = HexTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val alpha by animateFloatAsState(if (enabled) 1f else 0.2f, tween(200), label = "enabled")
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .fillMaxWidth()
                .height(50.dp)
                .scale(pressScale(interaction, 0.98f))
                .clip(RoundedCornerShape(12.dp))
                .background(colors.solid.copy(alpha = colors.solid.alpha * alpha))
                .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        Text(text, style = HexTheme.type.button, color = colors.onSolid.copy(alpha = if (enabled) 1f else 0.9f))
    }
}

/** A round 44 pt icon button: back, close. */
@Composable
fun HexRoundButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onCamera: Boolean = false,
) {
    val colors = HexTheme.colors
    val ink = if (onCamera) Color.White else colors.ink
    val interaction = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(44.dp)
                .scale(pressScale(interaction, 0.94f))
                .clip(CircleShape)
                .background(if (onCamera) Color.Black.copy(alpha = 0.35f) else ink.copy(alpha = 0.035f))
                .border(1.dp, ink.copy(alpha = 0.1f), CircleShape)
                .clickable(interaction, indication = null, role = Role.Button, onClick = onClick),
    ) {
        Icon(icon, contentDescription, tint = ink.copy(alpha = 0.85f), modifier = Modifier.size(18.dp))
    }
}

/** The top of a picker page: back, a title, and an optional status line under it. */
@Composable
fun HexPageTop(
    title: String,
    onBack: () -> Unit,
    backLabel: String,
    modifier: Modifier = Modifier,
    status: String? = null,
) {
    val colors = HexTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .height(56.dp)
                .padding(horizontal = HexEdge),
    ) {
        HexRoundButton(HexIcons.ChevronLeft, backLabel, onBack)
        Column(Modifier.weight(1f)) {
            Text(title, style = HexTheme.type.title, color = colors.text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (status != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HexDot(colors.green)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        status,
                        style = HexTheme.type.rowSubtitle,
                        color = colors.text2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun HexDot(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/** An uppercase group label over rows ("On this computer", a provider). */
@Composable
fun HexSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text.uppercase(),
        style = HexTheme.type.section,
        color = HexTheme.colors.text3,
        modifier = modifier.padding(start = 12.dp, end = 12.dp, top = 18.dp, bottom = 6.dp),
    )
}

/** A small outlined mono tag next to a title: `git`, `default`. */
@Composable
fun HexTag(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = HexTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .height(17.dp)
                .border(1.dp, colors.line2, RoundedCornerShape(5.dp))
                .padding(horizontal = 5.dp),
    ) {
        Text(text, style = HexTheme.type.tag, color = colors.text2)
    }
}

/**
 * One tappable list row: an optional leading icon, a title (with an optional tag), an optional subtitle, and
 * whatever trails it (a caption, a chevron, a check).
 */
@Composable
fun HexRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tag: String? = null,
    subtitle: String? = null,
    subtitleMono: Boolean = false,
    titleWeight: FontWeight = FontWeight.SemiBold,
    selected: Boolean = false,
    minHeight: Dp = 62.dp,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val colors = HexTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val wash by animateColorAsState(if (isPressed) colors.wash(0.045f) else Color.Transparent, tween(120), label = "row")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .scale(pressScale(interaction, 0.975f))
                .clip(RoundedCornerShape(12.dp))
                .background(wash)
                .semantics { this.selected = selected }
                .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, tint = colors.text1, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    title,
                    style = HexTheme.type.rowTitle.copy(fontWeight = titleWeight),
                    color = colors.text1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (tag != null) HexTag(tag)
            }
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = if (subtitleMono) HexTheme.type.mono.copy(fontSize = 12.5.sp) else HexTheme.type.rowSubtitle,
                    color = colors.text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        trailing()
    }
}

@Composable
fun HexChevron() {
    Icon(HexIcons.ChevronRight, null, tint = HexTheme.colors.text2, modifier = Modifier.size(15.dp))
}

/** A row of equal segments; [selected] is one of [options]. */
@Composable
fun <T> HexSegmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HexTheme.colors
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.wash(0.06f))
                .padding(3.dp),
    ) {
        options.forEach { option ->
            val on = option == selected
            val fill by animateColorAsState(if (on) colors.panel3 else Color.Transparent, tween(150), label = "segment")
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .height(30.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(fill)
                        .then(if (on) Modifier.border(1.dp, colors.line, RoundedCornerShape(7.dp)) else Modifier)
                        .semantics { this.selected = on }
                        .clickable(role = Role.Tab) { onSelect(option) },
            ) {
                Text(
                    label(option),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (on) colors.text1 else colors.text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The search field on the model page. */
@Composable
fun HexSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val colors = HexTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.wash(0.06f))
                .padding(horizontal = 12.dp),
    ) {
        Icon(HexIcons.Search, null, tint = colors.text3, modifier = Modifier.size(15.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, fontSize = 15.5.sp, color = colors.text3)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = HexTheme.type.body.copy(fontSize = 15.5.sp, color = colors.text1),
                cursorBrush = SolidColor(colors.text1),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The prototype's small spinner: a faint ring with a solid quarter turning. */
@Composable
fun HexSpinner(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    track: Color = HexTheme.colors.text4,
    arc: Color = HexTheme.colors.solid,
) {
    val turn by rememberInfiniteTransition(label = "spin").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "turn",
    )
    Canvas(modifier.size(size)) {
        val stroke = 1.5.dp.toPx()
        val inset = stroke / 2 + size.toPx() / 16f
        val diameter = this.size.minDimension - inset * 2
        drawCircle(track, radius = diameter / 2, style = Stroke(stroke))
        drawArc(
            color = arc,
            startAngle = turn - 90f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size =
                androidx.compose.ui.geometry
                    .Size(diameter, diameter),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}
