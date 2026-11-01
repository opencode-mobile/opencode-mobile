package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeKeyboardHint
import dev.opencode.mobile.ui.components.OpenCodeKeyboardHintTone

@Composable
fun KeyboardHintGalleryScreen(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OpenCodeKeyboardHint(text = "Press Tab to answer with the keyboard")
        OpenCodeKeyboardHint(text = "Esc again to interrupt", tone = OpenCodeKeyboardHintTone.Warning)
    }
}
