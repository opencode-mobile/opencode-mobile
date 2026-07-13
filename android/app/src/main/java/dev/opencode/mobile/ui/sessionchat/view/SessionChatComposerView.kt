package dev.opencode.mobile.ui.sessionchat.view

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
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.icons.OpenCodeIcons

internal data class SessionChatComposerViewProps(
    val text: String,
    val selectedModelLabel: String,
    val selectedMode: String,
    val selectedVariantLabel: String,
    val showVariant: Boolean,
    val sendEnabled: Boolean,
    val showStop: Boolean,
    val stopEnabled: Boolean,
    val permission: SessionChatComposerPermissionProps? = null,
)

internal data class SessionChatComposerPermissionProps(
    val permission: String,
    val patterns: List<String>,
    val queuePosition: Int = 1,
    val queueCount: Int = 1,
    val submitting: Boolean = false,
    val errorMessage: String? = null,
)

private sealed interface SessionChatComposerContent {
    data object Prompt : SessionChatComposerContent

    data class Permission(
        val props: SessionChatComposerPermissionProps,
    ) : SessionChatComposerContent
}

@Composable
internal fun SessionChatComposerView(
    props: SessionChatComposerViewProps,
    onTextChange: (String) -> Unit,
    onModelClick: () -> Unit,
    onModeClick: () -> Unit,
    onVariantClick: () -> Unit,
    onAttachClick: () -> Unit,
    onSendClick: () -> Unit,
    onStopClick: () -> Unit,
    onAllowPermissionOnce: () -> Unit,
    onDenyPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val permission = props.permission
    val content = permission?.let(SessionChatComposerContent::Permission) ?: SessionChatComposerContent.Prompt
    val outlineColor =
        when {
            permission?.errorMessage != null -> openCodeColor("error")
            permission != null -> openCodeColor("warning")
            else -> MaterialTheme.colorScheme.outlineVariant
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
                .then(if (permission != null) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
    ) {
        AnimatedContent(
            targetState = content,
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
                    SessionChatComposerContent.Prompt ->
                        PromptEntryContent(
                            props = props,
                            onTextChange = onTextChange,
                            onModelClick = onModelClick,
                            onModeClick = onModeClick,
                            onVariantClick = onVariantClick,
                            onAttachClick = onAttachClick,
                            onSendClick = onSendClick,
                            onStopClick = onStopClick,
                        )

                    is SessionChatComposerContent.Permission ->
                        PermissionApprovalContent(
                            permission = state.props,
                            onAllowOnce = onAllowPermissionOnce,
                            onDeny = onDenyPermission,
                        )
                }
            }
        }
    }
}

@Composable
private fun PromptEntryContent(
    props: SessionChatComposerViewProps,
    onTextChange: (String) -> Unit,
    onModelClick: () -> Unit,
    onModeClick: () -> Unit,
    onVariantClick: () -> Unit,
    onAttachClick: () -> Unit,
    onSendClick: () -> Unit,
    onStopClick: () -> Unit,
) {
    BasicTextField(
        value = props.text,
        onValueChange = onTextChange,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        decorationBox = { innerTextField ->
            Box {
                if (props.text.isBlank()) {
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
        OpenCodeChoiceBadge(label = props.selectedModelLabel, selected = false, onClick = onModelClick)
        OpenCodeChoiceBadge(label = props.selectedMode, selected = false, onClick = onModeClick)
        if (props.showVariant) {
            OpenCodeChoiceBadge(label = props.selectedVariantLabel, selected = false, onClick = onVariantClick)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpenCodeButton(
            variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.Add, "Attach context"),
            contrast = OpenCodeButtonContrast.Low,
            onClick = onAttachClick,
        )
        Spacer(Modifier.weight(1f))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (props.showStop) {
                OpenCodeButton(
                    variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.Stop, "Abort active work"),
                    enabled = props.stopEnabled,
                    contrast = OpenCodeButtonContrast.Low,
                    onClick = onStopClick,
                )
            }
            OpenCodeButton(
                variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.ArrowUpward, "Send prompt"),
                enabled = props.sendEnabled,
                contrast = OpenCodeButtonContrast.Low,
                onClick = onSendClick,
            )
        }
    }
}

@Composable
private fun PermissionApprovalContent(
    permission: SessionChatComposerPermissionProps,
    onAllowOnce: () -> Unit,
    onDeny: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Permission requested", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (permission.queueCount > 1) {
                Text("${permission.queuePosition} of ${permission.queueCount}", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(permission.permission.replace('_', ' '), style = MaterialTheme.typography.bodyMedium)
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                permission.patterns.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        permission.errorMessage?.let {
            Text(it, color = openCodeColor("error"), style = MaterialTheme.typography.bodySmall)
        }
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpenCodeButton(
            OpenCodeButtonVariant.CompactText("Deny"),
            onClick = onDeny,
            enabled = !permission.submitting,
            contrast = OpenCodeButtonContrast.Low,
        )
        Spacer(Modifier.weight(1f))
        if (permission.submitting) {
            CircularProgressIndicator(modifier = Modifier.padding(horizontal = 8.dp), strokeWidth = 2.dp)
        }
        OpenCodeButton(
            OpenCodeButtonVariant.CompactText("Allow once"),
            onClick = onAllowOnce,
            enabled = !permission.submitting,
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
        SessionChatComposerView(
            props =
                SessionChatComposerViewProps(
                    text = "",
                    selectedModelLabel = "Claude Sonnet 4",
                    selectedMode = "Build",
                    selectedVariantLabel = "Default",
                    showVariant = false,
                    sendEnabled = false,
                    showStop = false,
                    stopEnabled = false,
                    permission = SessionChatComposerPermissionProps("bash", listOf("git status", "git diff --stat"), queueCount = 2),
                ),
            onTextChange = {},
            onModelClick = {},
            onModeClick = {},
            onVariantClick = {},
            onAttachClick = {},
            onSendClick = {},
            onStopClick = {},
            onAllowPermissionOnce = {},
            onDenyPermission = {},
        )
    }
}
