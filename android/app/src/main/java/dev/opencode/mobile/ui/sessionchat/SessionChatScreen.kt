package dev.opencode.mobile.ui.sessionchat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.model.ModelSelectionCatalog
import dev.opencode.mobile.domain.model.ModelSelectionOption
import dev.opencode.mobile.domain.model.ModelSelectionProvider
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.ui.components.BottomSheetEntryDecorator
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetEntry
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetHeaderAction
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeOutgoingMessage
import dev.opencode.mobile.ui.components.OpenCodeRenderedMarkdown
import dev.opencode.mobile.ui.components.OpenCodeRenderedMarkdownVariant
import dev.opencode.mobile.ui.components.OpenCodeSessionConnectionStatus
import dev.opencode.mobile.ui.components.OpenCodeSessionStatusBar
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.components.copySessionIdToClipboard
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private enum class ComposerPickerKind(
    val sheetTitle: String,
) {
    Model("Model"),
    Mode("Agent mode"),
    Variant("Variant"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionChatScreen(
    sessionId: String,
    sessionTitle: String,
    worktree: String,
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {},
    onSessionContextClick: () -> Unit = {},
    viewModel: SessionChatViewModel = hiltViewModel(key = "$sessionId-$worktree"),
) {
    val context = LocalContext.current

    LaunchedEffect(sessionId, sessionTitle, worktree) {
        viewModel.bind(sessionId, sessionTitle, worktree)
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var activePicker by remember { mutableStateOf<ComposerPickerKind?>(null) }
    var activeModelProviderId by remember { mutableStateOf<String?>(null) }
    var modelFilterText by remember { mutableStateOf("") }

    LaunchedEffect(activePicker) {
        if (activePicker != ComposerPickerKind.Model) {
            activeModelProviderId = null
            modelFilterText = ""
        }
    }

    Box(modifier = modifier.fillMaxSize().imePadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            OpenCodeTopBar(
                title = "Session",
                leading = OpenCodeTopBarLeading.Back(onClick = onNavigateBack),
                showDivider = true,
            )
            SessionStatusBar(
                sessionId = uiState.sessionId,
                connectionStatus = uiState.connectionStatus,
                contextUsageFraction = uiState.contextUsageFraction,
                onCopySessionId = { copySessionIdToClipboard(context, uiState.sessionId) },
                onConnectionClick = {},
                onContextClick = onSessionContextClick,
            )
            uiState.sessionStatusLabel?.let { statusLabel ->
                Text(
                    text = statusLabel,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            uiState.modelNotice?.let { notice ->
                Text(
                    text = notice,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    var followingLatest by remember(uiState.sessionId) { mutableStateOf(true) }
                    var isFollowingScroll by remember(uiState.sessionId) { mutableStateOf(false) }
                    val markdownHeightCache = remember(uiState.sessionId) { mutableStateMapOf<RenderedMarkdownHeightKey, Dp>() }
                    var latestMessageHeightRevision by remember(uiState.sessionId) { mutableIntStateOf(0) }
                    val latestMessageId = uiState.messages.lastOrNull()?.id

                    LaunchedEffect(uiState.sessionId, uiState.messages.size) {
                        snapshotFlow {
                            listState.isScrollInProgress to !listState.canScrollForward
                        }.distinctUntilChanged().collect { (isScrollInProgress, atBottom) ->
                            if (atBottom) {
                                followingLatest = true
                            } else if (isScrollInProgress && !isFollowingScroll) {
                                followingLatest = false
                            }
                        }
                    }

                    LaunchedEffect(
                        uiState.sessionId,
                        latestMessageId,
                        uiState.messages.lastOrNull()?.text,
                        latestMessageHeightRevision,
                    ) {
                        val latestMessage = uiState.messages.lastOrNull() ?: return@LaunchedEffect
                        val bottomIndex = uiState.messages.size
                        val lastId = latestMessage.id
                        val isFirstScroll = lastScrolledMessageId == null
                        if (!isFirstScroll && !followingLatest) return@LaunchedEffect
                        try {
                            isFollowingScroll = true
                            listState.scrollToItem(bottomIndex)
                        } finally {
                            isFollowingScroll = false
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
                        ) {
                            itemsIndexed(uiState.messages, key = { _, message -> message.id }) { index, message ->
                                MessageRow(
                                    message = message,
                                    onRestoreClick = { viewModel.restoreComposerText(message.text) },
                                    initialRenderedMarkdownHeight = { variant, width ->
                                        markdownHeightCache[
                                            RenderedMarkdownHeightKey(
                                                messageId = message.id,
                                                variant = variant,
                                                widthBucket = width.value.roundToInt(),
                                            ),
                                        ] ?: 1.dp
                                    },
                                    onRenderedMarkdownHeightChange = { variant, width, height ->
                                        val key =
                                            RenderedMarkdownHeightKey(
                                                messageId = message.id,
                                                variant = variant,
                                                widthBucket = width.value.roundToInt(),
                                            )
                                        val previous = markdownHeightCache[key]
                                        markdownHeightCache[key] = height
                                        if (message.id == latestMessageId && followingLatest) {
                                            if (previous == null || abs(previous.value - height.value) > 1f) {
                                                latestMessageHeightRevision += 1
                                            }
                                        }
                                    },
                                    modifier =
                                        Modifier.padding(
                                            bottom = if (index == uiState.messages.lastIndex) 0.dp else 8.dp,
                                        ),
                                )
                            }
                            item(key = "session-chat-bottom") {
                                Spacer(Modifier.fillMaxWidth().height(1.dp))
                            }
                        }
                    }
                }
            }

            SessionComposer(
                composerText = uiState.composerText,
                selectedModel = uiState.selectedModelLabel,
                selectedMode = uiState.selectedMode,
                selectedVariant = uiState.selectedVariantLabel,
                showVariant = uiState.canSelectVariant,
                sendEnabled = uiState.composerText.isNotBlank() && uiState.selectedModel != null && !uiState.isSending,
                showStop = uiState.sessionStatusLabel != null,
                stopEnabled = !uiState.isAborting,
                onTextChange = viewModel::updateComposerText,
                onModelClick = {
                    activeModelProviderId = uiState.selectedModel?.option?.providerId
                    activePicker = ComposerPickerKind.Model
                },
                onModeClick = { activePicker = ComposerPickerKind.Mode },
                onVariantClick = { activePicker = ComposerPickerKind.Variant },
                onAttachClick = {},
                onSendClick = viewModel::sendPrompt,
                onStopClick = viewModel::abort,
            )
        }

        val modelCatalog =
            remember(uiState.modelOptions) {
                ModelSelectionCatalog(options = uiState.modelOptions, serverDefault = null)
            }
        val activeModelProvider = modelCatalog.providers.firstOrNull { it.id == activeModelProviderId }
        val modelSheetTitle =
            if (activePicker == ComposerPickerKind.Model) {
                activeModelProvider?.label ?: ComposerPickerKind.Model.sheetTitle
            } else {
                activePicker?.sheetTitle.orEmpty()
            }
        val showsModelFilter =
            activePicker == ComposerPickerKind.Model &&
                activeModelProvider != null &&
                activeModelProvider.models.size > SessionChatViewModel.ModelFilterThreshold
        val modelHeaderLeadingAction =
            if (activePicker == ComposerPickerKind.Model && activeModelProvider != null) {
                OpenCodeBottomSheetHeaderAction(
                    imageVector = OpenCodeIcons.ArrowBack,
                    contentDescription = "Back to providers",
                    onClick = {
                        activeModelProviderId = null
                        modelFilterText = ""
                    },
                )
            } else {
                null
            }

        OpenCodeBottomSheet(
            visible = activePicker != null,
            title = modelSheetTitle,
            onDismissRequest = {
                activePicker = null
                activeModelProviderId = null
                modelFilterText = ""
            },
            headerLeadingAction = modelHeaderLeadingAction,
            keyboardAware = showsModelFilter,
            pinnedContent = {
                if (showsModelFilter) {
                    ModelPickerFilterField(
                        value = modelFilterText,
                        onValueChange = { modelFilterText = it },
                    )
                }
            },
        ) {
            when (activePicker) {
                ComposerPickerKind.Model ->
                    ModelPickerSheetContent(
                        providers = modelCatalog.providers,
                        activeProvider = activeModelProvider,
                        selectedModel = uiState.selectedModel?.option,
                        filterText = modelFilterText,
                        onSelectProvider = { provider ->
                            activeModelProviderId = provider.id
                            modelFilterText = ""
                        },
                        onSelectModel = { model ->
                            viewModel.selectModel(model)
                            activePicker = null
                            activeModelProviderId = null
                            modelFilterText = ""
                        },
                    )
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
                ComposerPickerKind.Variant ->
                    uiState.variantOptions.forEach { variant ->
                        OpenCodeBottomSheetEntry(
                            title = variant,
                            endDecorator =
                                if (variant == uiState.selectedVariantLabel) {
                                    BottomSheetEntryDecorator.Icon(OpenCodeIcons.Check, "Selected")
                                } else {
                                    BottomSheetEntryDecorator.None
                                },
                            onClick = {
                                viewModel.selectVariant(variant)
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
private fun ModelPickerSheetContent(
    providers: List<ModelSelectionProvider>,
    activeProvider: ModelSelectionProvider?,
    selectedModel: ModelSelectionOption?,
    filterText: String,
    onSelectProvider: (ModelSelectionProvider) -> Unit,
    onSelectModel: (ModelSelectionOption) -> Unit,
) {
    if (activeProvider == null) {
        providers.forEach { provider ->
            OpenCodeBottomSheetEntry(
                title = provider.label,
                endDecorator =
                    if (provider.id == selectedModel?.providerId) {
                        BottomSheetEntryDecorator.Icon(OpenCodeIcons.Check, "Selected")
                    } else {
                        BottomSheetEntryDecorator.None
                    },
                onClick = { onSelectProvider(provider) },
            )
        }
    } else {
        val filteredModels = activeProvider.models.filter { it.matchesFilter(filterText) }
        if (filteredModels.isEmpty()) {
            OpenCodeBottomSheetEntry(
                title = "No models match your filter",
                enabled = false,
                onClick = {},
            )
        } else {
            filteredModels.forEach { model ->
                OpenCodeBottomSheetEntry(
                    title = model.modelLabel,
                    endDecorator =
                        if (model == selectedModel) {
                            BottomSheetEntryDecorator.Icon(OpenCodeIcons.Check, "Selected")
                        } else {
                            BottomSheetEntryDecorator.None
                        },
                    onClick = { onSelectModel(model) },
                )
            }
        }
    }
}

@Composable
private fun ModelPickerFilterField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 8.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        decorationBox = { innerTextField ->
            Box {
                if (value.isBlank()) {
                    Text(
                        text = "Filter models...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                innerTextField()
            }
        },
    )
}

@Composable
private fun SessionStatusBar(
    sessionId: String,
    connectionStatus: SessionConnectionStatus,
    contextUsageFraction: Float?,
    onCopySessionId: () -> Unit,
    onConnectionClick: () -> Unit,
    onContextClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OpenCodeSessionStatusBar(
        sessionId = sessionId,
        connectionStatus = connectionStatus.toOpenCodeSessionConnectionStatus(),
        contextUsageFraction = contextUsageFraction,
        onCopySessionId = onCopySessionId,
        onConnectionClick = onConnectionClick,
        onContextClick = onContextClick,
        modifier = modifier,
    )
}

private fun SessionConnectionStatus.toOpenCodeSessionConnectionStatus(): OpenCodeSessionConnectionStatus =
    when (this) {
        SessionConnectionStatus.Live -> OpenCodeSessionConnectionStatus.Live
        SessionConnectionStatus.Connecting -> OpenCodeSessionConnectionStatus.Connecting
        SessionConnectionStatus.Offline -> OpenCodeSessionConnectionStatus.Offline
    }

@Composable
private fun MessageRow(
    message: SessionChatMessageItem,
    onRestoreClick: () -> Unit,
    initialRenderedMarkdownHeight: (OpenCodeRenderedMarkdownVariant, Dp) -> Dp,
    onRenderedMarkdownHeightChange: (OpenCodeRenderedMarkdownVariant, Dp, Dp) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val availableWidth = maxWidth
        Column(
            modifier = Modifier.fillMaxWidth(),
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
                        renderedMarkdown = message.renderedMarkdown,
                        metadataLabel = message.metadataLabel,
                        onRestoreClick = onRestoreClick,
                        initialRenderedMarkdownHeight =
                            initialRenderedMarkdownHeight(
                                OpenCodeRenderedMarkdownVariant.OutgoingMessage,
                                availableWidth,
                            ),
                        onRenderedMarkdownHeightChange = { height ->
                            onRenderedMarkdownHeightChange(
                                OpenCodeRenderedMarkdownVariant.OutgoingMessage,
                                availableWidth,
                                height,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                MessageRole.Assistant ->
                    AssistantMessageBubble(
                        text = message.text,
                        renderedMarkdown = message.renderedMarkdown,
                        initialRenderedMarkdownHeight =
                            initialRenderedMarkdownHeight(
                                OpenCodeRenderedMarkdownVariant.AssistantMessage,
                                availableWidth,
                            ),
                        onRenderedMarkdownHeightChange = { height ->
                            onRenderedMarkdownHeightChange(
                                OpenCodeRenderedMarkdownVariant.AssistantMessage,
                                availableWidth,
                                height,
                            )
                        },
                    )
                MessageRole.Command, MessageRole.Tool, MessageRole.Unknown ->
                    FallbackMessageBubble(roleLabel = message.role.displayLabel(), text = message.text)
            }
        }
    }
}

@Composable
private fun AssistantMessageBubble(
    text: String,
    renderedMarkdown: dev.opencode.mobile.markdown.MarkdownDocument?,
    initialRenderedMarkdownHeight: Dp,
    onRenderedMarkdownHeightChange: (Dp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bubbleModifier =
        modifier
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)

    when {
        renderedMarkdown != null ->
            OpenCodeRenderedMarkdown(
                document = renderedMarkdown,
                variant = OpenCodeRenderedMarkdownVariant.AssistantMessage,
                initialHeight = initialRenderedMarkdownHeight,
                onContentHeightChange = onRenderedMarkdownHeightChange,
                modifier = bubbleModifier,
            )
        else ->
            Text(
                text = text.ifBlank { "…" },
                modifier = bubbleModifier,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
    }
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
    selectedVariant: String,
    showVariant: Boolean,
    sendEnabled: Boolean,
    showStop: Boolean,
    stopEnabled: Boolean,
    onTextChange: (String) -> Unit,
    onModelClick: () -> Unit,
    onModeClick: () -> Unit,
    onVariantClick: () -> Unit,
    onAttachClick: () -> Unit,
    onSendClick: () -> Unit,
    onStopClick: () -> Unit,
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
            if (showVariant) {
                OpenCodeChoiceBadge(label = selectedVariant, selected = false, onClick = onVariantClick)
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
            if (showStop) {
                OpenCodeButton(
                    variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.Stop, "Abort active work"),
                    enabled = stopEnabled,
                    contrast = OpenCodeButtonContrast.Low,
                    onClick = onStopClick,
                )
            }
            OpenCodeButton(
                variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.ArrowUpward, "Send prompt"),
                enabled = sendEnabled,
                contrast = OpenCodeButtonContrast.Low,
                onClick = onSendClick,
            )
        }
    }
}

private data class RenderedMarkdownHeightKey(
    val messageId: String,
    val variant: OpenCodeRenderedMarkdownVariant,
    val widthBucket: Int,
)

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
