package dev.opencode.mobile.ui.components

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons

sealed interface OpenCodeSessionComposerState {
    data class Prompt(
        val text: String,
        val attachments: List<OpenCodeAttachmentPresentation> = emptyList(),
        val selectedModelLabel: String,
        val selectedModeLabel: String,
        val selectedVariantLabel: String,
        val showVariant: Boolean,
        val sendEnabled: Boolean,
        val attachEnabled: Boolean = true,
        val showStop: Boolean,
        val stopEnabled: Boolean,
    ) : OpenCodeSessionComposerState

    data class Permission(
        val requestId: String,
        val sessionId: String,
        val permission: String,
        val patterns: List<String>,
        val queuePosition: Int = 1,
        val queueCount: Int = 1,
        val submitting: Boolean = false,
        val errorMessage: String? = null,
    ) : OpenCodeSessionComposerState
}

sealed interface OpenCodeSessionComposerAction {
    data class TextChanged(
        val text: String,
    ) : OpenCodeSessionComposerAction

    data object SelectModel : OpenCodeSessionComposerAction

    data object SelectMode : OpenCodeSessionComposerAction

    data object SelectVariant : OpenCodeSessionComposerAction

    data object Attach : OpenCodeSessionComposerAction

    data class RemoveAttachment(
        val id: String,
    ) : OpenCodeSessionComposerAction

    data object Send : OpenCodeSessionComposerAction

    data object Stop : OpenCodeSessionComposerAction

    data class AllowPermissionOnce(
        val requestId: String,
        val sessionId: String,
    ) : OpenCodeSessionComposerAction

    data class DenyPermission(
        val requestId: String,
        val sessionId: String,
    ) : OpenCodeSessionComposerAction
}

@Composable
fun OpenCodeSessionComposer(
    state: OpenCodeSessionComposerState,
    onAction: (OpenCodeSessionComposerAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val outlineColor =
        when (state) {
            is OpenCodeSessionComposerState.Permission ->
                if (state.errorMessage == null) openCodeColor("warning") else openCodeColor("error")
            is OpenCodeSessionComposerState.Prompt -> MaterialTheme.colorScheme.outlineVariant
        }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 10.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(26.dp))
                .border(1.dp, outlineColor, RoundedCornerShape(26.dp))
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .then(
                    if (state is OpenCodeSessionComposerState.Permission) {
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    } else {
                        Modifier
                    },
                ),
    ) {
        AnimatedContent(
            targetState = state,
            modifier = Modifier.fillMaxWidth(),
            transitionSpec = {
                (
                    fadeIn(
                        animationSpec = tween(durationMillis = 120, delayMillis = 80),
                    ) togetherWith
                        fadeOut(
                            animationSpec = tween(durationMillis = 80),
                        )
                ).using(
                    SizeTransform(clip = true) { _, _ ->
                        tween(durationMillis = 240)
                    },
                )
            },
            contentKey = { it::class },
            label = "composer-content",
        ) { state ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (state) {
                    is OpenCodeSessionComposerState.Prompt ->
                        PromptEntryContent(
                            state = state,
                            onAction = onAction,
                        )

                    is OpenCodeSessionComposerState.Permission ->
                        PermissionApprovalContent(
                            state = state,
                            onAction = onAction,
                        )
                }
            }
        }
    }
}

@Composable
private fun PromptEntryContent(
    state: OpenCodeSessionComposerState.Prompt,
    onAction: (OpenCodeSessionComposerAction) -> Unit,
) {
    if (state.attachments.isNotEmpty()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.attachments.forEach { attachment ->
                OpenCodeAttachmentChip(
                    attachment = attachment,
                    onRemove = { onAction(OpenCodeSessionComposerAction.RemoveAttachment(it)) },
                )
            }
        }
    }

    BasicTextField(
        value = state.text,
        onValueChange = { onAction(OpenCodeSessionComposerAction.TextChanged(it)) },
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        decorationBox = { innerTextField ->
            Box {
                if (state.text.isBlank()) {
                    Text(
                        text = "Ask anything...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                innerTextField()
            }
        },
    )

    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpenCodeChoiceBadge(
            label = state.selectedModelLabel,
            selected = false,
            onClick = { onAction(OpenCodeSessionComposerAction.SelectModel) },
        )
        OpenCodeChoiceBadge(
            label = state.selectedModeLabel,
            selected = false,
            onClick = { onAction(OpenCodeSessionComposerAction.SelectMode) },
        )
        if (state.showVariant) {
            OpenCodeChoiceBadge(
                label = state.selectedVariantLabel,
                selected = false,
                onClick = { onAction(OpenCodeSessionComposerAction.SelectVariant) },
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpenCodeButton(
            variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.Add, "Add attachment"),
            enabled = state.attachEnabled,
            contrast = OpenCodeButtonContrast.Low,
            onClick = { onAction(OpenCodeSessionComposerAction.Attach) },
        )
        Spacer(Modifier.weight(1f))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.showStop) {
                OpenCodeButton(
                    variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.Stop, "Abort active work"),
                    enabled = state.stopEnabled,
                    contrast = OpenCodeButtonContrast.Low,
                    onClick = { onAction(OpenCodeSessionComposerAction.Stop) },
                )
            }
            OpenCodeButton(
                variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.ArrowUpward, "Send prompt"),
                enabled = state.sendEnabled,
                contrast = OpenCodeButtonContrast.Low,
                onClick = { onAction(OpenCodeSessionComposerAction.Send) },
            )
        }
    }
}

@Composable
private fun PermissionApprovalContent(
    state: OpenCodeSessionComposerState.Permission,
    onAction: (OpenCodeSessionComposerAction) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Permission requested", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (state.queueCount > 1) {
                Text("${state.queuePosition} of ${state.queueCount}", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(state.permission.replace('_', ' '), style = MaterialTheme.typography.bodyMedium)
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.patterns.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        state.errorMessage?.let {
            Text(it, color = openCodeColor("error"), style = MaterialTheme.typography.bodySmall)
        }
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpenCodeButton(
            OpenCodeButtonVariant.CompactText("Deny"),
            onClick = {
                onAction(OpenCodeSessionComposerAction.DenyPermission(state.requestId, state.sessionId))
            },
            enabled = !state.submitting,
            contrast = OpenCodeButtonContrast.Low,
        )
        Spacer(Modifier.weight(1f))
        if (state.submitting) {
            CircularProgressIndicator(modifier = Modifier.padding(horizontal = 8.dp), strokeWidth = 2.dp)
        }
        OpenCodeButton(
            OpenCodeButtonVariant.CompactText("Allow once"),
            onClick = {
                onAction(OpenCodeSessionComposerAction.AllowPermissionOnce(state.requestId, state.sessionId))
            },
            enabled = !state.submitting,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PermissionComposerPreview() {
    PermissionComposerPreviewContent()
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PermissionComposerDarkPreview() {
    PermissionComposerPreviewContent()
}

@Composable
private fun PermissionComposerPreviewContent() {
    OpenCodeTheme {
        OpenCodeSessionComposer(
            state =
                OpenCodeSessionComposerState.Permission(
                    requestId = "permission-preview",
                    sessionId = "session-preview",
                    permission = "bash",
                    patterns = listOf("git status", "git diff --stat"),
                    queueCount = 2,
                ),
            onAction = {},
        )
    }
}
