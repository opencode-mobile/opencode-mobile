package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeSideQuestionPill
import dev.opencode.mobile.ui.components.OpenCodeSideQuestionPillVariant

@Composable
fun SideQuestionPillGalleryScreen(modifier: Modifier = Modifier) {
    var log by remember { mutableStateOf("Tap a pill") }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "Variants", style = MaterialTheme.typography.titleMedium)
        Text(
            text =
                "Floating /btw status above the composer. Pending is not interactive; ready opens the answer; " +
                    "failed retries, and its close forgets the failures.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OpenCodeSideQuestionPill(OpenCodeSideQuestionPillVariant.Pending(count = 1))
            OpenCodeSideQuestionPill(OpenCodeSideQuestionPillVariant.Pending(count = 3))
            OpenCodeSideQuestionPill(OpenCodeSideQuestionPillVariant.AnswerReady(count = 1), onClick = { log = "Open answer" })
            OpenCodeSideQuestionPill(
                OpenCodeSideQuestionPillVariant.Failed(count = 2),
                onClick = { log = "Retry" },
                onDismiss = { log = "Dismiss" },
            )
        }
        Text(text = log, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
