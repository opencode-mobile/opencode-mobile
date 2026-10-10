package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeBottomFade

@Composable
fun BottomFadeGalleryScreen(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.BottomCenter) {
        Text(
            text = "Content under the dock",
            modifier = Modifier.align(Alignment.TopStart).padding(top = 16.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
        OpenCodeBottomFade(height = 72.dp)
    }
}
