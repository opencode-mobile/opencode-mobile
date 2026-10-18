package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
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
    var value by rememberSaveable { mutableStateOf("") }
    var kindName by rememberSaveable { mutableStateOf(OpenCodeTextInputKind.Url.name) }
    var enabled by rememberSaveable { mutableStateOf(true) }
    var showsError by rememberSaveable { mutableStateOf(false) }
    var isEmpty by rememberSaveable { mutableStateOf(true) }
    var multiline by rememberSaveable { mutableStateOf(false) }
    val kind = OpenCodeTextInputKind.valueOf(kindName)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OpenCodeTextInputKind.entries.forEach { option ->
                OpenCodeChoiceBadge(
                    label = option.name,
                    selected = option == kind,
                    onClick = {
                        kindName = option.name
                        if (option != OpenCodeTextInputKind.Text) multiline = false
                        value = if (isEmpty) "" else option.sampleValue
                    },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenCodeChoiceBadge(label = "Enabled", selected = enabled, onClick = { enabled = !enabled })
            OpenCodeChoiceBadge(label = "Error", selected = showsError, onClick = { showsError = !showsError })
            OpenCodeChoiceBadge(
                label = "Empty",
                selected = isEmpty,
                onClick = {
                    isEmpty = !isEmpty
                    value = if (isEmpty) "" else kind.sampleValue
                },
            )
            OpenCodeChoiceBadge(
                label = "Multi-line",
                selected = multiline,
                onClick = {
                    multiline = !multiline
                    if (multiline) kindName = OpenCodeTextInputKind.Text.name
                },
            )
        }
        OpenCodeTextInput(
            value = value,
            onValueChange = { value = it },
            hint = kind.fieldHint,
            kind = kind,
            enabled = enabled,
            errorMessage = if (showsError) "Enter a valid ${kind.fieldHint.lowercase()}" else null,
            minLines = if (multiline) 4 else 1,
            maxLines = if (multiline) 8 else 1,
        )
    }
}

private val OpenCodeTextInputKind.fieldHint: String
    get() =
        when (this) {
            OpenCodeTextInputKind.Text -> "Project name"
            OpenCodeTextInputKind.Url -> "Server URL"
            OpenCodeTextInputKind.Username -> "Username"
            OpenCodeTextInputKind.Password -> "Password"
            OpenCodeTextInputKind.Filter -> "Filter models"
        }

private val OpenCodeTextInputKind.sampleValue: String
    get() =
        when (this) {
            OpenCodeTextInputKind.Text -> "OpenCode Mobile"
            OpenCodeTextInputKind.Url -> "http://127.0.0.1:4096/"
            OpenCodeTextInputKind.Username -> "opencode"
            OpenCodeTextInputKind.Password -> "correct horse battery staple"
            OpenCodeTextInputKind.Filter -> "claude"
        }
