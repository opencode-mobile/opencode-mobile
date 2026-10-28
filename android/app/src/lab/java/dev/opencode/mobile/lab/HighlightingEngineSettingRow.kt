package dev.opencode.mobile.lab

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.opencode.mobile.highlight.core.HighlightCore
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetEntry
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeListItemAccessory
import dev.opencode.mobile.ui.components.OpenCodeListItemStyle
import dev.opencode.mobile.ui.components.StartDecorator
import dev.opencode.mobile.ui.icons.OpenCodeIcons

/**
 * Settings > Developer Tools > Highlighting engine, with a picker sheet. [onChange] runs after a
 * new engine is stored, so prepared chat documents and open files can be colored again.
 */
@Composable
fun HighlightingEngineSettingRow(
    onChange: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPicker by remember { mutableStateOf(false) }
    val engine = HighlightingEnginePreferences.engine
    OpenCodeListItem(
        title = "Highlighting Engine",
        subtitle = "${engine.displayName} · chat code blocks and the File Viewer",
        style = OpenCodeListItemStyle.Card,
        startDecorator = StartDecorator.Icon(OpenCodeIcons.Code, "Highlighting Engine"),
        accessory = OpenCodeListItemAccessory.Disclosure,
        onClick = { showPicker = true },
        modifier = modifier,
    )
    OpenCodeBottomSheet(
        visible = showPicker,
        title = "Highlighting Engine",
        onDismissRequest = { showPicker = false },
    ) {
        HighlightingEngine.entries.forEach { option ->
            OpenCodeBottomSheetEntry(
                title = option.displayName,
                subtitle = option.pickerSubtitle,
                selected = option == engine,
                onClick = {
                    showPicker = false
                    if (HighlightingEnginePreferences.update(option)) onChange()
                },
            )
        }
    }
}

private val HighlightingEngine.pickerSubtitle: String
    get() =
        when (this) {
            HighlightingEngine.TextMate -> "Every language, as in release builds"
            HighlightingEngine.HighlightCore ->
                HighlightCore.languages.joinToString(", ", postfix = "; TextMate for the rest")
        }
