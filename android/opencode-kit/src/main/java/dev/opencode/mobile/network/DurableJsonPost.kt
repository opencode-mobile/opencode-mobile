package dev.opencode.mobile.network

import dev.opencode.mobile.domain.message.PromptSendProgressReporter
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.onUpload
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder

private const val PROMPT_SEND_TIMEOUT_MS = 30L * 60L * 1_000L

fun HttpRequestBuilder.applyDurableSend(progress: PromptSendProgressReporter?) {
    timeout {
        requestTimeoutMillis = PROMPT_SEND_TIMEOUT_MS
        socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
    }
    if (progress == null) return
    onUpload { sent, total ->
        val expected = total ?: return@onUpload
        if (expected > 0) progress.onProgress(sent, expected)
    }
}
