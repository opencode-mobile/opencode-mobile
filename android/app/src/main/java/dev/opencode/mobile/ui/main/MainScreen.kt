package dev.opencode.mobile.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.opencode.mobile.data.DefaultDataRepository
import dev.opencode.mobile.data.SessionSummary
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.ui.components.OpenCodeListItem

@Composable
fun MainScreen(
  onDeveloperComponentsClick: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: MainScreenViewModel = viewModel { MainScreenViewModel(DefaultDataRepository()) },
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  MainScreen(uiState = state, onDeveloperComponentsClick = onDeveloperComponentsClick, modifier = modifier)
}

@Composable
internal fun MainScreen(uiState: MainScreenUiState, onDeveloperComponentsClick: () -> Unit, modifier: Modifier = Modifier) {
  val serverBaseUrl = (uiState as? MainScreenUiState.Success)?.serverBaseUrl ?: "http://127.0.0.1:4096/"
  val selectedProjectName = (uiState as? MainScreenUiState.Success)?.selectedProjectName ?: "opencode-mobile"
  Box(modifier = modifier.fillMaxSize()) {
    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 64.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      item {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
            text = "Selected project: $selectedProjectName",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = "Connected server: $serverBaseUrl",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      when (uiState) {
        MainScreenUiState.Loading -> {
          item {
            Column(
              modifier = Modifier.fillMaxSize(),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              CircularProgressIndicator()
            }
          }
        }
        is MainScreenUiState.Error -> {
          item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text(text = "Unable to load sessions", style = MaterialTheme.typography.titleMedium)
              Text(
                text = uiState.throwable.message ?: "Unknown error",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
        is MainScreenUiState.Success -> {
          if (uiState.sessions.isEmpty()) {
            item {
              Text(
                text = "No sessions found yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          } else {
            items(uiState.sessions, key = { it.id }) { session ->
              OpenCodeListItem(
                title = session.title,
                subtitle = serverBaseUrl,
                trailing = { Text("...", style = MaterialTheme.typography.titleMedium) },
              )
            }
          }
        }
      }
    }

    Text(
      text = "Developer Components",
      style = MaterialTheme.typography.labelLarge,
      modifier =
        Modifier
          .align(Alignment.BottomCenter)
          .fillMaxWidth()
          .clickable(onClick = onDeveloperComponentsClick)
          .padding(vertical = 12.dp),
      textAlign = TextAlign.Center,
    )
  }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
  OpenCodeTheme(darkTheme = false) {
    MainScreen(
      uiState =
        MainScreenUiState.Success(
          sessions =
            listOf(
              SessionSummary(id = "1", title = "Importing icons explanation"),
              SessionSummary(id = "2", title = "Material Design Icons integration plan"),
            ),
          serverBaseUrl = "http://127.0.0.1:4096/",
          selectedProjectName = "opencode-mobile",
        ),
      onDeveloperComponentsClick = {},
    )
  }
}

@Preview(showBackground = true, widthDp = 340)
@Composable
fun MainScreenDarkPreview() {
  OpenCodeTheme(darkTheme = true) {
    MainScreen(
      uiState =
        MainScreenUiState.Success(
          sessions =
            listOf(
              SessionSummary(id = "1", title = "Importing icons explanation"),
              SessionSummary(id = "2", title = "Material Design Icons integration plan"),
            ),
          serverBaseUrl = "http://127.0.0.1:4096/",
          selectedProjectName = "opencode-mobile",
        ),
      onDeveloperComponentsClick = {},
    )
  }
}
