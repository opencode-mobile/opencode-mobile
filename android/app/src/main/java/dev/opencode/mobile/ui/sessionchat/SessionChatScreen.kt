package dev.opencode.mobile.ui.sessionchat

import android.content.ClipData
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.ui.components.BottomSheetEntryDecorator
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetEntry
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeOutgoingMessage
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private enum class ComposerPickerKind(
    val sheetTitle: String,
) {
    Model("Model"),
    Mode("Agent mode"),
    Thinking("Thinking level"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionChatScreen(
    sessionId: String,
    sessionTitle: String,
    worktree: String,
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {},
    viewModel: SessionChatViewModel = hiltViewModel(key = "$sessionId-$worktree"),
) {
    LaunchedEffect(sessionId, sessionTitle, worktree) {
        viewModel.bind(sessionId, sessionTitle, worktree)
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var activePicker by remember { mutableStateOf<ComposerPickerKind?>(null) }

    Box(modifier = modifier.fillMaxSize().imePadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            OpenCodeTopBar(
                title = "Session",
                leading = OpenCodeTopBarLeading.Back(onClick = onNavigateBack),
                showDivider = true,
            )
            SessionIdRow(sessionId = uiState.sessionId)
            uiState.sessionStatusLabel?.let { statusLabel ->
                Text(
                    text = statusLabel,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            uiState.errorMessage?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            when (uiState.timeline) {
                TimelineState.Loading ->
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                TimelineState.Error ->
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = uiState.errorMessage ?: "Unable to load messages",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                TimelineState.Empty ->
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No messages yet. Send a prompt to start.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                TimelineState.Ready -> {
                    val listState = rememberLazyListState()
                    var lastScrolledMessageId by remember(uiState.sessionId) { mutableStateOf<String?>(null) }

                    LaunchedEffect(uiState.sessionId, uiState.messages.lastOrNull()?.id) {
                        val lastIndex = uiState.messages.lastIndex
                        if (lastIndex < 0) return@LaunchedEffect
                        val lastId = uiState.messages[lastIndex].id
                        if (lastId == lastScrolledMessageId) return@LaunchedEffect
                        if (lastScrolledMessageId == null) {
                            listState.scrollToItem(lastIndex)
                        } else {
                            listState.animateScrollToItem(lastIndex)
                        }
                        lastScrolledMessageId = lastId
                    }

                    PullToRefreshBox(
                        isRefreshing = uiState.isRefreshing,
                        onRefresh = { viewModel.refreshMessages() },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding =
                                PaddingValues(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 12.dp,
                                    bottom = 12.dp,
                                ),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(uiState.messages, key = { it.id }) { message ->
                                MessageRow(
                                    message = message,
                                    onRestoreClick = { viewModel.restoreComposerText(message.text) },
                                )
                            }
                        }
                    }
                }
            }

            SessionComposer(
                composerText = uiState.composerText,
                selectedModel = uiState.selectedModel,
                selectedMode = uiState.selectedMode,
                selectedThinkingEffort = uiState.selectedThinkingEffort,
                sendEnabled = uiState.composerText.isNotBlank() && !uiState.isSending,
                onTextChange = viewModel::updateComposerText,
                onModelClick = { activePicker = ComposerPickerKind.Model },
                onModeClick = { activePicker = ComposerPickerKind.Mode },
                onThinkingEffortClick = { activePicker = ComposerPickerKind.Thinking },
                onAttachClick = {},
                onSendClick = viewModel::sendPrompt,
            )
        }

        OpenCodeBottomSheet(
            visible = activePicker != null,
            title = activePicker?.sheetTitle ?: "",
            onDismissRequest = { activePicker = null },
        ) {
            when (activePicker) {
                ComposerPickerKind.Model ->
                    SessionChatViewModel.AvailableModels.forEach { model ->
                        OpenCodeBottomSheetEntry(
                            title = model,
                            endDecorator =
                                if (model == uiState.selectedModel) {
                                    BottomSheetEntryDecorator.Icon(OpenCodeIcons.Check, "Selected")
                                } else {
                                    BottomSheetEntryDecorator.None
                                },
                            onClick = {
                                viewModel.selectModel(model)
                                activePicker = null
                            },
                        )
                    }
                ComposerPickerKind.Mode ->
                    SessionChatViewModel.AvailableModes.forEach { mode ->
                        OpenCodeBottomSheetEntry(
                            title = mode,
                            endDecorator =
                                if (mode == uiState.selectedMode) {
                                    BottomSheetEntryDecorator.Icon(OpenCodeIcons.Check, "Selected")
                                } else {
                                    BottomSheetEntryDecorator.None
                                },
                            onClick = {
                                viewModel.selectMode(mode)
                                activePicker = null
                            },
                        )
                    }
                ComposerPickerKind.Thinking ->
                    SessionChatViewModel.AvailableThinkingEfforts.forEach { effort ->
                        OpenCodeBottomSheetEntry(
                            title = effort,
                            endDecorator =
                                if (effort == uiState.selectedThinkingEffort) {
                                    BottomSheetEntryDecorator.Icon(OpenCodeIcons.Check, "Selected")
                                } else {
                                    BottomSheetEntryDecorator.None
                                },
                            onClick = {
                                viewModel.selectThinkingEffort(effort)
                                activePicker = null
                            },
                        )
                    }
                null -> Unit
            }
        }
    }
}

@Composable
private fun SessionIdRow(
    sessionId: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Text(
        text = "Session ID: $sessionId",
        modifier =
            modifier
                .fillMaxWidth()
                .clickable { copySessionId(context, sessionId) }
                .padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.MiddleEllipsis,
    )
}

private fun copySessionId(
    context: Context,
    sessionId: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Session ID", sessionId))
}

@Composable
private fun MessageRow(
    message: SessionChatMessageItem,
    onRestoreClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment =
            if (message.role == MessageRole.User) {
                Alignment.End
            } else {
                Alignment.Start
            },
    ) {
        when (message.role) {
            MessageRole.User ->
                OpenCodeOutgoingMessage(
                    text = message.text,
                    metadataLabel = message.metadataLabel,
                    onRestoreClick = onRestoreClick,
                    modifier = Modifier.fillMaxWidth(),
                )
            MessageRole.Assistant ->
                AssistantMessageBubble(text = message.text)
            MessageRole.Command, MessageRole.Tool, MessageRole.Unknown ->
                FallbackMessageBubble(roleLabel = message.role.displayLabel(), text = message.text)
        }
    }
}

@Composable
private fun AssistantMessageBubble(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.ifBlank { "…" },
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun FallbackMessageBubble(
    roleLabel: String,
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = "$roleLabel: ${text.ifBlank { "…" }}",
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SessionComposer(
    composerText: String,
    selectedModel: String,
    selectedMode: String,
    selectedThinkingEffort: String,
    sendEnabled: Boolean,
    onTextChange: (String) -> Unit,
    onModelClick: () -> Unit,
    onModeClick: () -> Unit,
    onThinkingEffortClick: () -> Unit,
    onAttachClick: () -> Unit,
    onSendClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 10.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(26.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(26.dp))
                .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BasicTextField(
            value = composerText,
            onValueChange = onTextChange,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            decorationBox = { innerTextField ->
                Box {
                    if (composerText.isBlank()) {
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
            OpenCodeChoiceBadge(label = selectedModel, selected = false, onClick = onModelClick)
            OpenCodeChoiceBadge(label = selectedMode, selected = false, onClick = onModeClick)
            OpenCodeChoiceBadge(label = selectedThinkingEffort, selected = false, onClick = onThinkingEffortClick)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OpenCodeButton(
                variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.Add, "Attach context"),
                contrast = OpenCodeButtonContrast.Low,
                onClick = onAttachClick,
            )
            OpenCodeButton(
                variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.ArrowUpward, "Send prompt"),
                enabled = sendEnabled,
                contrast = OpenCodeButtonContrast.Low,
                onClick = onSendClick,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SessionChatScreenPreview() {
    OpenCodeTheme(darkTheme = false) {
        SessionChatScreen(
            sessionId = "1",
            sessionTitle = "Importing icons explanation",
            worktree = "/Users/demo/opencode-mobile",
        )
    }
}
