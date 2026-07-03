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
}
