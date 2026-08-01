package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeServerAddressInput
import dev.opencode.mobile.ui.components.OpenCodeTextInput

@Composable
fun ServerAddressInputGalleryScreen(modifier: Modifier = Modifier) {
    var value by rememberSaveable { mutableStateOf("http://127.0.0.1:4096/") }
    var username by rememberSaveable { mutableStateOf("opencode") }
    var enabled by rememberSaveable { mutableStateOf(true) }
    var showsError by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenCodeChoiceBadge(label = "Enabled", selected = enabled, onClick = { enabled = !enabled })
            OpenCodeChoiceBadge(label = "Error", selected = showsError, onClick = { showsError = !showsError })
            OpenCodeChoiceBadge(
                label = "Hostname sample",
                selected = value.contains("macbook.local"),
                onClick = { value = "https://macbook.local:8080/" },
            )
            OpenCodeChoiceBadge(
                label = "Empty",
                selected = value == "http://",
                onClick = { value = "http://" },
            )
        }
        OpenCodeServerAddressInput(
            value = value,
            onValueChange = { value = it },
            enabled = enabled,
            errorMessage = if (showsError) "Enter a valid host and port" else null,
        )
        OpenCodeTextInput(
            value = username,
            onValueChange = { username = it },
            hint = "Username",
            enabled = enabled,
            errorMessage = if (showsError) "Required" else null,
        )
    }
}
