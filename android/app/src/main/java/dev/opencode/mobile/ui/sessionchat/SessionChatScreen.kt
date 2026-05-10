package dev.opencode.mobile.ui.sessionchat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.ui.components.BottomSheetEntryDecorator
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetEntry
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import java.util.UUID

private enum class ComposerPickerKind(val sheetTitle: String) {
  Model("Model"),
  Mode("Agent mode"),
  Thinking("Thinking level"),
}

@Composable
fun SessionChatScreen(sessionId: String, sessionTitle: String, modifier: Modifier = Modifier) {
  var uiState by remember(sessionId, sessionTitle) { mutableStateOf(SessionChatUiState.initial(sessionId, sessionTitle)) }
  var activePicker by remember { mutableStateOf<ComposerPickerKind?>(null) }

  Box(modifier = modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
      Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = sessionTitle, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(text = "Session ID: $sessionId", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }

      LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(uiState.messages, key = { it.id }) { message ->
          Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = if (message.role == MessageRole.User) Alignment.End else Alignment.Start,
          ) {
            Text(
              text = "${message.role.label}: ${message.text}",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurface,
            )
          }
        }
      }

      SessionComposer(
        state = uiState,
        onTextChange = { nextValue -> uiState = uiState.copy(composerText = nextValue) },
        onModelClick = { activePicker = ComposerPickerKind.Model },
        onModeClick = { activePicker = ComposerPickerKind.Mode },
        onThinkingEffortClick = { activePicker = ComposerPickerKind.Thinking },
        onAttachClick = {},
        onSendClick = {
          val prompt = uiState.composerText.trim()
          if (prompt.isEmpty()) return@SessionComposer
          uiState =
            uiState.copy(
              composerText = "",
              messages = uiState.messages + SessionChatMessage(id = UUID.randomUUID().toString(), role = MessageRole.User, text = prompt),
            )
        },
      )
    }

    OpenCodeBottomSheet(
      visible = activePicker != null,
      title = activePicker?.sheetTitle ?: "",
      onDismissRequest = { activePicker = null },
    ) {
      when (activePicker) {
        ComposerPickerKind.Model ->
          AvailableModels.forEach { model ->
            OpenCodeBottomSheetEntry(
              title = model,
              endDecorator =
                if (model == uiState.selectedModel) {
                  BottomSheetEntryDecorator.Icon(Icons.Filled.Check, "Selected")
                } else {
                  BottomSheetEntryDecorator.None
                },
              onClick = {
                if (AvailableModels.contains(model)) {
                  uiState = uiState.copy(selectedModel = model)
                }
                activePicker = null
              },
            )
          }
        ComposerPickerKind.Mode ->
          AvailableModes.forEach { mode ->
            OpenCodeBottomSheetEntry(
              title = mode,
              endDecorator =
                if (mode == uiState.selectedMode) {
                  BottomSheetEntryDecorator.Icon(Icons.Filled.Check, "Selected")
                } else {
                  BottomSheetEntryDecorator.None
                },
              onClick = {
                if (AvailableModes.contains(mode)) {
                  uiState = uiState.copy(selectedMode = mode)
                }
                activePicker = null
              },
            )
          }
        ComposerPickerKind.Thinking ->
          AvailableThinkingEfforts.forEach { effort ->
            OpenCodeBottomSheetEntry(
              title = effort,
              endDecorator =
                if (effort == uiState.selectedThinkingEffort) {
                  BottomSheetEntryDecorator.Icon(Icons.Filled.Check, "Selected")
                } else {
                  BottomSheetEntryDecorator.None
                },
              onClick = {
                if (AvailableThinkingEfforts.contains(effort)) {
                  uiState = uiState.copy(selectedThinkingEffort = effort)
                }
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
private fun SessionComposer(
  state: SessionChatUiState,
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
        .padding(horizontal = 12.dp, vertical = 10.dp)
        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(26.dp))
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(26.dp))
        .padding(horizontal = 12.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    BasicTextField(
      value = state.composerText,
      onValueChange = onTextChange,
      textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
      cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
      modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
      decorationBox = { innerTextField ->
        Box {
          if (state.composerText.isBlank()) {
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
      OpenCodeChoiceBadge(label = state.selectedModel, selected = false, onClick = onModelClick)
      OpenCodeChoiceBadge(label = state.selectedMode, selected = false, onClick = onModeClick)
      OpenCodeChoiceBadge(label = state.selectedThinkingEffort, selected = false, onClick = onThinkingEffortClick)
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      OpenCodeButton(
        variant = OpenCodeButtonVariant.IconCircle(Icons.Filled.Add, "Attach context"),
        contrast = OpenCodeButtonContrast.Low,
        onClick = onAttachClick,
      )
      OpenCodeButton(
        variant = OpenCodeButtonVariant.IconCircle(Icons.Filled.ArrowUpward, "Send prompt"),
        enabled = state.composerText.isNotBlank(),
        contrast = OpenCodeButtonContrast.Low,
        onClick = onSendClick,
      )
    }
  }
}

private data class SessionChatUiState(
  val sessionId: String,
  val sessionTitle: String,
  val messages: List<SessionChatMessage>,
  val composerText: String,
  val selectedModel: String,
  val selectedMode: String,
  val selectedThinkingEffort: String,
) {
  companion object {
    fun initial(sessionId: String, sessionTitle: String): SessionChatUiState =
      SessionChatUiState(
        sessionId = sessionId,
        sessionTitle = sessionTitle,
        messages =
          listOf(
            SessionChatMessage(id = "assistant-1", role = MessageRole.Assistant, text = "Session ready. Enter a prompt to continue."),
          ),
        composerText = "",
        selectedModel = AvailableModels.first(),
        selectedMode = AvailableModes.first(),
        selectedThinkingEffort = AvailableThinkingEfforts.first(),
      )
  }
}

private data class SessionChatMessage(val id: String, val role: MessageRole, val text: String)

private enum class MessageRole(val label: String) {
  User("You"),
  Assistant("Assistant"),
}

private val AvailableModels = listOf("DeepSeek V4 Flash", "GPT-5 Preview", "Claude Sonnet")
private val AvailableModes = listOf("Build", "Plan")
private val AvailableThinkingEfforts = listOf("Default", "Low", "Medium", "High")

@Preview(showBackground = true)
@Composable
private fun SessionChatScreenPreview() {
  OpenCodeTheme(darkTheme = false) {
    SessionChatScreen(sessionId = "1", sessionTitle = "Importing icons explanation")
  }
}
