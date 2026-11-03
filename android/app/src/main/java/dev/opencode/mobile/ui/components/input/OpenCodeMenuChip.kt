package dev.opencode.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private val MenuChipShape = RoundedCornerShape(percent = 50)
private val MenuChipMinHeight = 32.dp
private val MenuChipPaddingStart = 10.dp
private val MenuChipPaddingEnd = 8.dp
private val MenuChipSpacing = 6.dp
private val MenuChipIconSize = 16.dp
private val MenuChipLetterSize = 18.dp
private val MenuChipLetterGlyphSize = 10.sp
private val MenuChipLetterTextStyle =
    TextStyle(
        lineHeight = MenuChipLetterGlyphSize,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.Both),
    )
private const val MENU_CHIP_SELECTED_FILL_ALPHA = 0.14f
private const val MENU_CHIP_SELECTED_BORDER_ALPHA = 0.32f

/** How an [OpenCodeMenuChip] acts. */
enum class OpenCodeMenuChipVariant {
    /** Shows the current choice and opens a menu of the others; a chevron trails the title. */
    Menu,

    /** Turns a filter on or off, such as "Include archived"; `selected` is on. */
    Toggle,
}

/** What leads an [OpenCodeMenuChip]'s title. */
sealed interface OpenCodeMenuChipLeading {
    data class Icon(
        val imageVector: ImageVector,
    ) : OpenCodeMenuChipLeading

    /** A project letter, as on its list rows. */
    data class Letter(
        val character: Char,
        val accent: LetterAccent,
    ) : OpenCodeMenuChipLeading
}

/**
 * A capsule in a row of filters, such as search's scope and "Include archived". [variant] Menu
 * shows the current choice and opens a menu of the others, with [selected] marking a choice other
 * than the default; Toggle turns its filter on and off, with [selected] as on. Both draw
 * [selected] with the interactive tint.
 */
@Composable
fun OpenCodeMenuChip(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: OpenCodeMenuChipLeading? = null,
    selected: Boolean = false,
    variant: OpenCodeMenuChipVariant = OpenCodeMenuChipVariant.Menu,
    enabled: Boolean = true,
    /** Read before the title, such as "Search scope"; the title alone otherwise. */
    accessibilityLabel: String? = null,
) {
    val interactive = openCodeColor("interactive")
    val contentColor =
        when {
            !enabled -> openCodeColor("text-weaker")
            selected -> interactive
            else -> openCodeColor("text-base")
        }
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier =
            modifier
                .defaultMinSize(minHeight = MenuChipMinHeight)
                .openCodeFocusRing(MenuChipShape, enabled)
                .semantics(mergeDescendants = true) {
                    contentDescription = accessibilityLabel ?: title
                    when (variant) {
                        OpenCodeMenuChipVariant.Menu -> {
                            role = Role.DropdownList
                            stateDescription = title
                        }
                        OpenCodeMenuChipVariant.Toggle -> {
                            role = Role.Switch
                            toggleableState = ToggleableState(selected)
                        }
                    }
                },
        shape = MenuChipShape,
        color = if (selected) interactive.copy(alpha = MENU_CHIP_SELECTED_FILL_ALPHA) else openCodeColor("surface-raised-base"),
        contentColor = contentColor,
        border =
            BorderStroke(
                1.dp,
                if (selected) interactive.copy(alpha = MENU_CHIP_SELECTED_BORDER_ALPHA) else openCodeColor("border-weak-base"),
            ),
    ) {
        Row(
            modifier =
                Modifier.padding(
                    start = MenuChipPaddingStart,
                    end = if (variant == OpenCodeMenuChipVariant.Menu) MenuChipPaddingEnd else MenuChipPaddingStart + 2.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MenuChipSpacing),
        ) {
            when (leading) {
                null -> Unit
                is OpenCodeMenuChipLeading.Icon ->
                    Icon(imageVector = leading.imageVector, contentDescription = null, modifier = Modifier.size(MenuChipIconSize))
                is OpenCodeMenuChipLeading.Letter ->
                    // A small badge: trim the line box so the letter sits in the middle.
                    CompositionLocalProvider(LocalTextStyle provides MenuChipLetterTextStyle) {
                        ListItemLetterCircle(
                            character = leading.character,
                            accent = leading.accent,
                            glyphSize = MenuChipLetterGlyphSize,
                            modifier = Modifier.size(MenuChipLetterSize),
                        )
                    }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (variant == OpenCodeMenuChipVariant.Menu) {
                Icon(imageVector = OpenCodeIcons.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(MenuChipIconSize))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeMenuChipPreview() {
    OpenCodeTheme(darkTheme = true) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(16.dp)) {
            OpenCodeMenuChip(title = "All projects", onClick = {}, leading = OpenCodeMenuChipLeading.Icon(OpenCodeIcons.Widgets))
            OpenCodeMenuChip(
                title = "acme-invoices",
                onClick = {},
                leading = OpenCodeMenuChipLeading.Letter('A', LetterAccent.Teal),
                selected = true,
            )
        }
    }
}
