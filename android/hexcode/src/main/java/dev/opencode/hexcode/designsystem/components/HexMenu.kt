package dev.opencode.hexcode.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.designsystem.HexTheme

/** The colour of a [HexMenuItem]'s icon: `text2`, or `green` (the goal). */
enum class HexMenuTone { Default, Green }

/** One item of a [HexMenu]: a 15 pt [icon] in its [tone], a [title] and a second line ([detail]). */
data class HexMenuItem(
    val icon: ImageVector,
    val title: String,
    val detail: String? = null,
    val tone: HexMenuTone = HexMenuTone.Default,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * A small pop-up menu (`.dmenu`): `panel3`, a `line2` border, radius `l`, `shadow`, padding 4, at least 230 pt wide.
 * Each item: the icon, the title (`callout`, `text1`) and its second line (`footnote`, `text3`), padding 9 × 10, radius
 * `s`, `hover` while pressed; a disabled item fades and takes no taps. The screen places it above its button.
 */
@Composable
fun HexMenu(
    items: List<HexMenuItem>,
    modifier: Modifier = Modifier,
) {
    val colors = HexTheme.colors
    val shape = RoundedCornerShape(HexTheme.radius.l)
    Column(
        modifier =
            modifier
                .widthIn(min = 230.dp)
                .width(IntrinsicSize.Max)
                .shadow(16.dp, shape, ambientColor = colors.shadow, spotColor = colors.shadow)
                .clip(shape)
                .background(colors.panel3)
                .border(1.dp, colors.line2, shape)
                .padding(4.dp),
    ) {
        items.forEach { MenuRow(it) }
    }
}

@Composable
private fun MenuRow(item: HexMenuItem) {
    val colors = HexTheme.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val fill by animateColorAsState(if (pressed) colors.hover else Color.Transparent, HexTheme.motion.press(), label = "menu-fill")
    val shape = RoundedCornerShape(HexTheme.radius.s)
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
        modifier =
            Modifier
                .fillMaxWidth()
                .alpha(if (item.enabled) 1f else 0.35f)
                .clip(shape)
                .background(fill)
                .clickable(source, null, enabled = item.enabled, role = Role.Button, onClick = item.onClick)
                .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Icon(
            item.icon,
            null,
            tint = if (item.tone == HexMenuTone.Green) colors.green else colors.text2,
            modifier = Modifier.padding(top = 1.dp).size(15.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(item.title, style = HexTheme.type.callout, color = colors.text1)
            if (item.detail != null) Text(item.detail, style = HexTheme.type.footnote, color = colors.text3)
        }
    }
}
