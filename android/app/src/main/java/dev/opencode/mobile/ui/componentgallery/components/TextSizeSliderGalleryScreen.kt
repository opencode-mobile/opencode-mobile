package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeTextSizeSlider

private val GallerySteps = listOf("Extra small", "Small", "Default", "Large", "Extra large")

@Composable
fun TextSizeSliderGalleryScreen(modifier: Modifier = Modifier) {
    var index by rememberSaveable { mutableIntStateOf(2) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Interactive", style = MaterialTheme.typography.titleMedium)
        OpenCodeTextSizeSlider(
            title = "Size",
            stepNames = GallerySteps,
            selectedIndex = index,
            onSelectedIndexChange = { index = it },
        )
        Text("Disabled", style = MaterialTheme.typography.titleMedium)
        OpenCodeTextSizeSlider(
            title = "Size",
            stepNames = GallerySteps,
            selectedIndex = 4,
            onSelectedIndexChange = {},
            enabled = false,
        )
    }
}
