package dev.opencode.mobile.ui.sessionchat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import dev.opencode.mobile.theme.OpenCodeTheme
import java.util.UUID

@Composable
fun SessionChatScreen(sessionId: String, sessionTitle: String, modifier: Modifier = Modifier) {
  var uiState by remember(sessionId, sessionTitle) { mutableStateOf(SessionChatUiState.initial(sessionId, sessionTitle)) }

  Column(modifier = modifier.fillMaxSize()) {
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

    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 12.dp, vertical = 10.dp)
          .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
          .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
          .padding(horizontal = 10.dp, vertical = 10.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        BasicTextField(
          value = uiState.composerText,
          onValueChange = { nextValue -> uiState = uiState.copy(composerText = nextValue) },
          textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
          cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
          modifier = Modifier.weight(1f),
          decorationBox = { innerTextField ->
            if (uiState.composerText.isBlank()) {
              Text(
                text = "Ask anything...",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            innerTextField()
          },
        )

        OutlinedButton(
          onClick = {},
          shape = RoundedCornerShape(12.dp),
          colors =
            ButtonDefaults.outlinedButtonColors(
              containerColor = MaterialTheme.colorScheme.surfaceVariant,
              contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
          border = null,
        ) {
          Text("+", style = MaterialTheme.typography.titleMedium)
        }

        OutlinedButton(
          enabled = uiState.composerText.isNotBlank(),
          onClick = {
            val prompt = uiState.composerText.trim()
            if (prompt.isEmpty()) return@OutlinedButton
            uiState =
              uiState.copy(
                composerText = "",
                messages = uiState.messages + SessionChatMessage(id = UUID.randomUUID().toString(), role = MessageRole.User, text = prompt),
              )
          },
          shape = RoundedCornerShape(12.dp),
          colors =
            ButtonDefaults.outlinedButtonColors(
              containerColor = MaterialTheme.colorScheme.surfaceVariant,
              contentColor = MaterialTheme.colorScheme.onSurface,
            ),
          border = null,
        ) {
          Text("↑", style = MaterialTheme.typography.titleMedium)
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TextButton(onClick = {}) { Text("Build", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        TextButton(onClick = {}) { Text("DeepSeek V4 Flash", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        TextButton(onClick = {}) { Text("Default", color = MaterialTheme.colorScheme.onSurfaceVariant) }
      }
    }
  }
}

private data class SessionChatUiState(
  val sessionId: String,
  val sessionTitle: String,
  val messages: List<SessionChatMessage>,
  val composerText: String,
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
      )
  }
}

private data class SessionChatMessage(val id: String, val role: MessageRole, val text: String)

private enum class MessageRole(val label: String) {
  User("You"),
  Assistant("Assistant"),
}

@Preview(showBackground = true)
@Composable
private fun SessionChatScreenPreview() {
  OpenCodeTheme(darkTheme = false) {
    SessionChatScreen(sessionId = "1", sessionTitle = "Importing icons explanation")
  }
}
