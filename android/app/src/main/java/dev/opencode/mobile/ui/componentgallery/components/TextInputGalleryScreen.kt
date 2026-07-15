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
import dev.opencode.mobile.ui.components.OpenCodeTextInput
import dev.opencode.mobile.ui.components.OpenCodeTextInputKind

@Composable
fun TextInputGalleryScreen(modifier: Modifier = Modifier) {
    var value by rememberSaveable { mutableStateOf("http://127.0.0.1:4096/") }
    var kindName by rememberSaveable { mutableStateOf(OpenCodeTextInputKind.Url.name) }
    var enabled by rememberSaveable { mutableStateOf(true) }
    var showsError by rememberSaveable { mutableStateOf(false) }
    var showsLabel by rememberSaveable { mutableStateOf(true) }
    val kind = OpenCodeTextInputKind.valueOf(kindName)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenCodeTextInputKind.entries.forEach { option ->
                OpenCodeChoiceBadge(
                    label = option.name,
                    selected = option == kind,
                    onClick = {
                        kindName = option.name
                        value = option.sampleValue
                    },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenCodeChoiceBadge(label = "Enabled", selected = enabled, onClick = { enabled = !enabled })
            OpenCodeChoiceBadge(label = "Error", selected = showsError, onClick = { showsError = !showsError })
            OpenCodeChoiceBadge(label = "Label", selected = showsLabel, onClick = { showsLabel = !showsLabel })
        }
        OpenCodeTextInput(
            label = kind.fieldLabel.takeIf { showsLabel },
            value = value,
            onValueChange = { value = it },
            placeholder = kind.placeholder,
            accessibilityLabel = kind.fieldLabel.takeUnless { showsLabel },
            kind = kind,
            enabled = enabled,
            errorMessage = if (showsError) "Enter a valid ${kind.fieldLabel.lowercase()}" else null,
        )
    }
}

private val OpenCodeTextInputKind.fieldLabel: String
    get() =
        when (this) {
            OpenCodeTextInputKind.Text -> "Project name"
            OpenCodeTextInputKind.Url -> "Server URL"
            OpenCodeTextInputKind.Username -> "Username"
            OpenCodeTextInputKind.Password -> "Password"
        }

private val OpenCodeTextInputKind.placeholder: String
    get() =
        when (this) {
            OpenCodeTextInputKind.Text -> "OpenCode Mobile"
            OpenCodeTextInputKind.Url -> "http://127.0.0.1:4096/"
            OpenCodeTextInputKind.Username -> "opencode"
            OpenCodeTextInputKind.Password -> "Server password"
        }

private val OpenCodeTextInputKind.sampleValue: String
    get() =
        when (this) {
            OpenCodeTextInputKind.Text -> "OpenCode Mobile"
            OpenCodeTextInputKind.Url -> "http://127.0.0.1:4096/"
            OpenCodeTextInputKind.Username -> "opencode"
            OpenCodeTextInputKind.Password -> "correct horse battery staple"
        }
