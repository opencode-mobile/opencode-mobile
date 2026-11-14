package dev.opencode.hexcode.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.designsystem.HexIcons
import dev.opencode.hexcode.designsystem.HexTheme

/** The 40 pt search field (`.mp-search`): `tint(6)` fill (`tint(9)` while focused) and the search icon. */
@Composable
fun HexSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val colors = HexTheme.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val fill by animateColorAsState(colors.tint(if (focused) 9f else 6f), HexTheme.motion.fade(), label = "search-fill")
    val style = HexTheme.type.body.copy(color = colors.text1)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(colors.text1),
        interactionSource = source,
        keyboardOptions =
            KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Search,
            ),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { field ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier =
                    Modifier
                        .height(40.dp)
                        .clip(RoundedCornerShape(HexTheme.radius.m))
                        .background(fill)
                        .padding(horizontal = 12.dp),
            ) {
                Icon(HexIcons.search, null, tint = colors.text3, modifier = Modifier.size(HexTheme.size.icon))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = style, color = colors.text3, maxLines = 1)
                    field()
                }
            }
        },
    )
}

/**
 * The 44 pt field (`.pk-field`): `panel` fill, `line2` border, radius 12. Without [onValueChange] it only shows
 * [value] (Browse folders' path), cut at the start so the end of a long path stays in view; [mono] sets it in the
 * monospace.
 */
@Composable
fun HexTextField(
    value: String,
    modifier: Modifier = Modifier,
    onValueChange: ((String) -> Unit)? = null,
    placeholder: String? = null,
    mono: Boolean = false,
) {
    val colors = HexTheme.colors
    val shape = RoundedCornerShape(HexTheme.radius.l)
    val style = HexTheme.type.row.copy(color = colors.text1, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
    val frame =
        Modifier
            .fillMaxWidth()
            .height(HexTheme.size.touch)
            .clip(shape)
            .background(colors.panel)
            .border(1.dp, colors.line2, shape)
            .padding(horizontal = 14.dp)
    if (onValueChange == null) {
        Box(modifier.then(frame), contentAlignment = Alignment.CenterStart) {
            Text(value, style = style, maxLines = 1, overflow = TextOverflow.StartEllipsis)
        }
    } else {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(colors.text1),
            modifier = modifier,
            decorationBox = { field ->
                Box(frame, contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder != null) Text(placeholder, style = style, color = colors.text3, maxLines = 1)
                    field()
                }
            },
        )
    }
}
