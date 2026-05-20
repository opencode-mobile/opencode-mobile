package dev.opencode.mobile.ui.sessionchat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageError
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.message.MessageRole
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class SessionChatViewModel
@Inject
constructor(
  private val messageRepository: MessageRepository,
) : ViewModel() {
  private val _uiState =
    MutableStateFlow(SessionChatUiState.initial(sessionId = "", sessionTitle = ""))
  val uiState: StateFlow<SessionChatUiState> = _uiState.asStateFlow()

  private var boundSessionId: String? = null
  private var boundWorktree: String? = null

  fun bind(sessionId: String, sessionTitle: String, worktree: String) {
    if (boundSessionId == sessionId && boundWorktree == worktree && _uiState.value.sessionTitle == sessionTitle) {
      return
    }
    boundSessionId = sessionId
    boundWorktree = worktree
    _uiState.value = SessionChatUiState.initial(sessionId, sessionTitle)
    loadMessages()
  }

  fun loadMessages() {
    val sessionId = boundSessionId ?: return
    val worktree = boundWorktree ?: return
    viewModelScope.launch {
      _uiState.update { it.copy(timeline = TimelineState.Loading, errorMessage = null) }
      runCatching { messageRepository.listMessages(sessionId, worktree) }
        .onSuccess { messages -> applyLoadedMessages(messages) }
        .onFailure { throwable -> applyLoadError(throwable) }
    }
  }

  fun refreshMessages() {
    val sessionId = boundSessionId ?: return
    val worktree = boundWorktree ?: return
    viewModelScope.launch {
      _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
      runCatching { messageRepository.listMessages(sessionId, worktree) }
        .onSuccess { messages -> applyLoadedMessages(messages, isRefreshing = false) }
        .onFailure { throwable ->
          _uiState.update {
            it.copy(
              isRefreshing = false,
              errorMessage = errorMessage(throwable),
            )
          }
        }
    }
  }

  fun updateComposerText(text: String) {
    _uiState.update { it.copy(composerText = text) }
  }

  fun selectModel(model: String) {
    if (model in AvailableModels) {
      _uiState.update { it.copy(selectedModel = model) }
    }
  }

  fun selectMode(mode: String) {
    if (mode in AvailableModes) {
      _uiState.update { it.copy(selectedMode = mode) }
    }
  }

  fun selectThinkingEffort(effort: String) {
    if (effort in AvailableThinkingEfforts) {
      _uiState.update { it.copy(selectedThinkingEffort = effort) }
    }
  }

  fun restoreComposerText(text: String) {
    _uiState.update { it.copy(composerText = text) }
  }

  fun sendPrompt() {
    val sessionId = boundSessionId ?: return
    val worktree = boundWorktree ?: return
    val prompt = _uiState.value.composerText.trim()
    if (prompt.isEmpty() || _uiState.value.isSending) return

    val optimistic =
      SessionChatMessageItem(
        id = "pending-${UUID.randomUUID()}",
        role = MessageRole.User,
        text = prompt,
        metadataLabel = composerMetadataLabel(),
        isPending = true,
      )

    viewModelScope.launch {
      _uiState.update {
        it.copy(
          composerText = "",
          isSending = true,
          errorMessage = null,
          messages = it.messages + optimistic,
          timeline = TimelineState.Ready,
        )
      }
      runCatching { messageRepository.sendPromptAsync(sessionId, prompt, worktree) }
        .onSuccess {
          runCatching { messageRepository.listMessages(sessionId, worktree) }
            .onSuccess { messages ->
              _uiState.update {
                it.copy(
                  isSending = false,
                  messages = messages.map(::toMessageItem),
                  timeline = if (messages.isEmpty()) TimelineState.Empty else TimelineState.Ready,
                )
              }
            }
            .onFailure { throwable ->
              _uiState.update {
                it.copy(
                  isSending = false,
                  messages = it.messages.filterNot { item -> item.id == optimistic.id },
                  errorMessage = errorMessage(throwable),
                )
              }
            }
        }
        .onFailure { throwable ->
          _uiState.update {
            it.copy(
              isSending = false,
              messages = it.messages.filterNot { item -> item.id == optimistic.id },
              errorMessage = errorMessage(throwable),
            )
          }
        }
    }
  }

  private fun applyLoadedMessages(messages: List<Message>, isRefreshing: Boolean = false) {
    _uiState.update {
      it.copy(
        timeline = if (messages.isEmpty()) TimelineState.Empty else TimelineState.Ready,
        messages = messages.map(::toMessageItem),
        isSending = false,
        isRefreshing = isRefreshing,
      )
    }
  }

  private fun applyLoadError(throwable: Throwable) {
    _uiState.update {
      it.copy(
        timeline = TimelineState.Error,
        messages = emptyList(),
        isSending = false,
        errorMessage = errorMessage(throwable),
      )
    }
  }

  private fun toMessageItem(message: Message): SessionChatMessageItem =
    SessionChatMessageItem(
      id = message.id,
      role = message.role,
      text = message.text,
      metadataLabel = metadataLabel(message),
    )

  private fun metadataLabel(message: Message): String {
    val timeLabel = message.sentAtEpochMs?.let(::formatSentTime)
    return listOfNotNull(message.mode, message.model, timeLabel).joinToString(" · ")
  }

  private fun composerMetadataLabel(): String {
    val state = _uiState.value
    return listOfNotNull(state.selectedMode, state.selectedModel, formatSentTime(System.currentTimeMillis()))
      .joinToString(" · ")
  }

  private fun formatSentTime(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs)
      .atZone(ZoneId.systemDefault())
      .format(SentTimeFormatter)

  private fun errorMessage(throwable: Throwable): String =
    when (throwable) {
      is MessageError.Http -> throwable.message ?: "Request failed"
      is MessageError.Transport -> "Unable to reach the server"
      is MessageError.Decoding -> "Unable to read server response"
      else -> throwable.message ?: "Something went wrong"
    }

  companion object {
    val AvailableModels = listOf("DeepSeek V4 Flash", "GPT-5 Preview", "Claude Sonnet")
    val AvailableModes = listOf("Build", "Plan")
    val AvailableThinkingEfforts = listOf("Default", "Low", "Medium", "High")
    private val SentTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
  }
}

data class SessionChatUiState(
  val sessionId: String,
  val sessionTitle: String,
  val timeline: TimelineState,
  val messages: List<SessionChatMessageItem>,
  val composerText: String,
  val selectedModel: String,
  val selectedMode: String,
  val selectedThinkingEffort: String,
  val isSending: Boolean = false,
  val isRefreshing: Boolean = false,
  val errorMessage: String? = null,
) {
  companion object {
    fun initial(sessionId: String, sessionTitle: String): SessionChatUiState =
      SessionChatUiState(
        sessionId = sessionId,
        sessionTitle = sessionTitle,
        timeline = TimelineState.Loading,
        messages = emptyList(),
        composerText = "",
        selectedModel = SessionChatViewModel.AvailableModels.first(),
        selectedMode = SessionChatViewModel.AvailableModes.first(),
        selectedThinkingEffort = SessionChatViewModel.AvailableThinkingEfforts.first(),
      )
  }
}

enum class TimelineState {
  Loading,
  Empty,
  Ready,
  Error,
}

data class SessionChatMessageItem(
  val id: String,
  val role: MessageRole,
  val text: String,
  val metadataLabel: String,
  val isPending: Boolean = false,
)

fun MessageRole.displayLabel(): String =
  when (this) {
    MessageRole.User -> "You"
    MessageRole.Assistant -> "Assistant"
    MessageRole.Command -> "Command"
    MessageRole.Tool -> "Tool"
    MessageRole.Unknown -> "Message"
  }
