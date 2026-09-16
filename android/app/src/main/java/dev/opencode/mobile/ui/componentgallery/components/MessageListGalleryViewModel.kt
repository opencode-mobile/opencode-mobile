package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MessageListGalleryViewModel : ViewModel() {
    var liveMessagesEnabled by mutableStateOf(false)
        private set

    var autoFollowEnabled by mutableStateOf(false)
        private set

    var messages by mutableStateOf(MessageListGalleryMocks.messages)
        private set

    private var liveSequence = 0
    private var liveJob: Job? = null

    fun updateLiveMessages(enabled: Boolean) {
        if (liveMessagesEnabled == enabled) return
        liveMessagesEnabled = enabled
        if (enabled) {
            startLiveMessages()
        } else {
            stopLiveMessages()
        }
    }

    fun updateAutoFollow(enabled: Boolean) {
        autoFollowEnabled = enabled
    }

    fun addMessage() {
        appendLiveMessage()
    }

    fun removeLastMessage() {
        messages = messages.dropLast(1)
    }

    fun resizeLastMessage() {
        val lastMessage = messages.lastOrNull() ?: return
        messages =
            messages.dropLast(1) +
            lastMessage.copy(
                text = lastMessage.text.toggledResizeText(),
            )
    }

    private fun startLiveMessages() {
        liveJob?.cancel()
        liveJob =
            viewModelScope.launch {
                while (isActive) {
                    delay(5_000)
                    appendLiveMessage()
                }
            }
    }

    private fun stopLiveMessages() {
        liveJob?.cancel()
        liveJob = null
    }

    private fun appendLiveMessage() {
        liveSequence += 1
        val message =
            MessageListGalleryMocks.nextLiveMessage(
                sequence = liveSequence,
                lastRole = messages.lastOrNull()?.role,
            )
        messages = messages + message
    }

    override fun onCleared() {
        stopLiveMessages()
        super.onCleared()
    }

    private fun String.toggledResizeText(): String =
        if (contains(ResizeMarker)) {
            substringBefore(ResizeMarker).trimEnd()
        } else {
            this +
                "\n\n" +
                ResizeMarker +
                "\n" +
                "This extra gallery-only paragraph expands the final message so the scroll harness can verify resize behavior. " +
                "Tap the resize control again to shrink the message back to its original height."
        }

    private companion object {
        const val ResizeMarker = "[gallery-resized]"
    }
}
