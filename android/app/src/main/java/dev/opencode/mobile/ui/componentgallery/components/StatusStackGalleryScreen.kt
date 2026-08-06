package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeStatusStack

@Composable
fun StatusStackGalleryScreen(modifier: Modifier = Modifier) {
    var usesSessionCopy by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OpenCodeChoiceBadge(
            label = "Session copy",
            selected = usesSessionCopy,
            onClick = { usesSessionCopy = !usesSessionCopy },
        )
        OpenCodeStatusStack(
            title = if (usesSessionCopy) "Loading messages" else "Loading sessions",
            subtitle = if (usesSessionCopy) "Fetching timeline…" else "Checking server…",
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.background),
        )
    }
}
