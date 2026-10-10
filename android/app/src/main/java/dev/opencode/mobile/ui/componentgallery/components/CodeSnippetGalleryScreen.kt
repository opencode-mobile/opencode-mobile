package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeCodeSnippet
import dev.opencode.mobile.ui.components.OpenCodeCodeSnippetSurface

@Composable
fun CodeSnippetGalleryScreen(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OpenCodeCodeSnippet(code = "opencode serve")
        OpenCodeCodeSnippet(code = "opencode2 pair", surface = OpenCodeCodeSnippetSurface.Base)
        OpenCodeCodeSnippet(code = "OPENCODE_SERVER_PASSWORD=secret opencode serve")
    }
}
