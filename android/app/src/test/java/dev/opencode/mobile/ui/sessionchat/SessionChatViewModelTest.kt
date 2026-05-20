package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.message.MessageRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionChatViewModelTest {
  private val testDispatcher = StandardTestDispatcher()

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun loadMessages_success() = runTest {
    val viewModel = SessionChatViewModel(FakeMessageRepository())
    viewModel.bind("ses-1", "Title")
    advanceUntilIdle()

    val state = viewModel.uiState.value
    assertEquals(TimelineState.Ready, state.timeline)
    assertEquals(1, state.messages.size)
    assertEquals("Hello", state.messages.first().text)
  }

  @Test
  fun loadMessages_empty() = runTest {
    val viewModel = SessionChatViewModel(FakeMessageRepository(messages = emptyList()))
    viewModel.bind("ses-1", "Title")
    advanceUntilIdle()

    assertEquals(TimelineState.Empty, viewModel.uiState.value.timeline)
  }

  @Test
  fun loadMessages_error() = runTest {
    val viewModel = SessionChatViewModel(FakeMessageRepository(shouldFailLoad = true))
    viewModel.bind("ses-1", "Title")
    advanceUntilIdle()

    assertEquals(TimelineState.Error, viewModel.uiState.value.timeline)
  }

  @Test
  fun sendPrompt_success_reloadsMessages() = runTest {
    val repository = FakeMessageRepository()
    val viewModel = SessionChatViewModel(repository)
    viewModel.bind("ses-1", "Title")
    advanceUntilIdle()

    viewModel.updateComposerText("New prompt")
    viewModel.sendPrompt()
    advanceUntilIdle()

    assertEquals(false, viewModel.uiState.value.isSending)
    assertTrue(repository.sendPromptAsyncCalled)
    assertEquals("Hello", viewModel.uiState.value.messages.single().text)
  }

  @Test
  fun sendPrompt_failure_removesOptimisticMessage() = runTest {
    val viewModel = SessionChatViewModel(FakeMessageRepository(shouldFailSend = true))
    viewModel.bind("ses-1", "Title")
    advanceUntilIdle()

    viewModel.updateComposerText("Fail me")
    viewModel.sendPrompt()
    advanceUntilIdle()

    assertTrue(viewModel.uiState.value.messages.none { it.text == "Fail me" })
    assertTrue(viewModel.uiState.value.errorMessage != null)
  }
}

private class FakeMessageRepository(
  private val messages: List<Message> =
    listOf(
      Message(
        id = "msg-1",
        role = MessageRole.User,
        text = "Hello",
      ),
    ),
  private val shouldFailLoad: Boolean = false,
  private val shouldFailSend: Boolean = false,
) : MessageRepository {
  var sendPromptAsyncCalled: Boolean = false

  override suspend fun listMessages(sessionId: String): List<Message> {
    if (shouldFailLoad) error("load failed")
    return messages
  }

  override suspend fun getMessage(sessionId: String, messageId: String): Message = error("unused")

  override suspend fun sendMessage(sessionId: String, text: String): Message = error("unused")

  override suspend fun sendPromptAsync(sessionId: String, text: String) {
    sendPromptAsyncCalled = true
    if (shouldFailSend) error("send failed")
  }
}
