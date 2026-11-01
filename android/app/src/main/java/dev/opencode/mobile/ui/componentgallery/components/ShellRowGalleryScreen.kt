package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeShellRow

@Composable
fun ShellRowGalleryScreen(modifier: Modifier = Modifier) {
    var longCommand by rememberSaveable { mutableStateOf(false) }
    var showDescription by rememberSaveable { mutableStateOf(true) }
    var showElapsed by rememberSaveable { mutableStateOf(true) }
    var tappable by rememberSaveable { mutableStateOf(true) }

    val command =
        if (longCommand) {
            "./gradlew :app:testDebugUnitTest --tests 'dev.example.sessionchat.SessionChatViewModelTest' --info --stacktrace"
        } else {
            "npm test"
        }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Content",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OpenCodeChoiceBadge(label = "Long command", selected = longCommand, onClick = { longCommand = !longCommand })
                OpenCodeChoiceBadge(label = "Description", selected = showDescription, onClick = { showDescription = !showDescription })
                OpenCodeChoiceBadge(label = "Elapsed", selected = showElapsed, onClick = { showElapsed = !showElapsed })
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Interaction",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            OpenCodeChoiceBadge(label = "Tappable", selected = tappable, onClick = { tappable = !tappable })
        }

        Text(
            text = "Running",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        OpenCodeShellRow(
            command = command,
            description = if (showDescription) "Run the unit tests" else null,
            elapsed = if (showElapsed) "1:12" else null,
            onClick = if (tappable) ({}) else null,
        )
    }
}
