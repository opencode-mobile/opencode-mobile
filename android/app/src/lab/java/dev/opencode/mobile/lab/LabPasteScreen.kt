package dev.opencode.mobile.lab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeReadableColumn
import dev.opencode.mobile.ui.components.OpenCodeTextInput
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading

@Composable
internal fun LabPasteScreen(
    state: LabPasteUiState,
    onNavigateBack: () -> Unit,
    onTextChange: (String) -> Unit,
    onSelectLanguage: (String) -> Unit,
    onOpen: () -> Unit,
) {
    Column(Modifier.fillMaxSize().imePadding()) {
        OpenCodeTopBar(
            title = "Paste text",
            leading = OpenCodeTopBarLeading.Close(onNavigateBack),
            showDivider = true,
        )
        OpenCodeReadableColumn(Modifier.weight(1f)) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LabChoiceRow(
                    label = "Language",
                    options = state.languages,
                    selected = { it == state.language },
                    title = { it },
                    onSelect = onSelectLanguage,
                )
                OpenCodeTextInput(
                    value = state.text,
                    onValueChange = onTextChange,
                    hint = "Paste code here",
                    accessibilityLabel = "Text to highlight",
                    minLines = 8,
                    maxLines = 16,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                Text(
                    text = state.characterLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = openCodeColor("text-weak"),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                OpenCodeButton(
                    variant = OpenCodeButtonVariant.FullWidthText("Open as data set"),
                    enabled = state.canOpen,
                    onClick = onOpen,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}
