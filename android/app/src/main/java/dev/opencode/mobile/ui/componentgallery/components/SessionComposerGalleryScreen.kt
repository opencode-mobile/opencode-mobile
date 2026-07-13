package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import dev.opencode.mobile.ui.components.OpenCodeSessionComposer
import dev.opencode.mobile.ui.components.OpenCodeSessionComposerAction
import dev.opencode.mobile.ui.components.OpenCodeSessionComposerState

private enum class SessionComposerGalleryScenario(
    val label: String,
) {
    Empty("Empty"),
    Prompt("Prompt"),
    Active("Active"),
    Permission("Permission"),
    Queued("Queued"),
    Submitting("Submitting"),
    Error("Error"),
}

@Composable
fun SessionComposerGalleryScreen(modifier: Modifier = Modifier) {
    var scenarioName by rememberSaveable { mutableStateOf(SessionComposerGalleryScenario.Empty.name) }
    var draftText by rememberSaveable { mutableStateOf("Summarize the current changes") }
    var lastAction by rememberSaveable { mutableStateOf("None") }
    val scenario =
        runCatching { SessionComposerGalleryScenario.valueOf(scenarioName) }
            .getOrDefault(SessionComposerGalleryScenario.Empty)
    val state =
        when (scenario) {
            SessionComposerGalleryScenario.Empty -> promptState(text = "")
            SessionComposerGalleryScenario.Prompt -> promptState(text = draftText)
            SessionComposerGalleryScenario.Active ->
                promptState(
                    text = draftText,
                    showStop = true,
                )
            SessionComposerGalleryScenario.Permission -> permissionState()
            SessionComposerGalleryScenario.Queued -> permissionState(queueCount = 3)
            SessionComposerGalleryScenario.Submitting -> permissionState(submitting = true)
            SessionComposerGalleryScenario.Error -> permissionState(errorMessage = "The permission response failed. Try again.")
        }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("State", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SessionComposerGalleryScenario.entries.forEach { option ->
                    OpenCodeChoiceBadge(
                        label = option.label,
                        selected = option == scenario,
                        onClick = { scenarioName = option.name },
                    )
                }
            }
            Text(
                "Last action: $lastAction",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        OpenCodeSessionComposer(
            state = state,
            onAction = { action ->
                if (action is OpenCodeSessionComposerAction.TextChanged) {
                    draftText = action.text
                }
                lastAction = action.label
            },
        )
    }
}

private fun promptState(
    text: String,
    showStop: Boolean = false,
) = OpenCodeSessionComposerState.Prompt(
    text = text,
    selectedModelLabel = "Claude Sonnet 4",
    selectedModeLabel = "Build",
    selectedVariantLabel = "Default",
    showVariant = true,
    sendEnabled = text.isNotBlank(),
    showStop = showStop,
    stopEnabled = showStop,
)

private fun permissionState(
    queueCount: Int = 1,
    submitting: Boolean = false,
    errorMessage: String? = null,
) = OpenCodeSessionComposerState.Permission(
    requestId = "permission-gallery",
    sessionId = "session-gallery",
    permission = "bash",
    patterns = listOf("git status", "git diff --stat"),
    queueCount = queueCount,
    submitting = submitting,
    errorMessage = errorMessage,
)

private val OpenCodeSessionComposerAction.label: String
    get() =
        when (this) {
            is OpenCodeSessionComposerAction.TextChanged -> "Text changed"
            OpenCodeSessionComposerAction.SelectModel -> "Select model"
            OpenCodeSessionComposerAction.SelectMode -> "Select mode"
            OpenCodeSessionComposerAction.SelectVariant -> "Select variant"
            OpenCodeSessionComposerAction.Attach -> "Attach"
            OpenCodeSessionComposerAction.Send -> "Send"
            OpenCodeSessionComposerAction.Stop -> "Stop"
            is OpenCodeSessionComposerAction.AllowPermissionOnce -> "Allow once"
            is OpenCodeSessionComposerAction.DenyPermission -> "Deny"
        }
