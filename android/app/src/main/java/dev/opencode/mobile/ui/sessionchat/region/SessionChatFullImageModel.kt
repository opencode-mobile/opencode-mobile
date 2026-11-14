package dev.opencode.mobile.ui.sessionchat.region

import dev.opencode.mobile.ui.components.OpenCodeImageViewerPageStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where each viewer image's full picture is, by image id; unknown ids are still on their thumbnail. */
data class SessionChatFullImageState(
    val statuses: Map<String, OpenCodeImageViewerPageStatus> = emptyMap(),
) {
    fun status(imageId: String): OpenCodeImageViewerPageStatus = statuses[imageId] ?: OpenCodeImageViewerPageStatus.Thumbnail
}

/**
 * Screen Model for the full-screen image viewer's full pictures. The viewer opens on the
 * enlarged thumbnail ([OpenCodeImageViewerPageStatus.Thumbnail]); [show] starts loading
 * ([OpenCodeImageViewerPageStatus.Loading]), which ends in
 * [OpenCodeImageViewerPageStatus.Full] or [OpenCodeImageViewerPageStatus.Failed]. A failure
 * stays until [retry]. [load] returns the full picture or throws.
 */
class SessionChatFullImageModel(
    private val scope: CoroutineScope,
    private val load: suspend (imageId: String) -> ByteArray,
) {
    private val mutableState = MutableStateFlow(SessionChatFullImageState())
    val state: StateFlow<SessionChatFullImageState> = mutableState.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()

    /** The viewer settled on [imageId]: load its full picture unless it is loaded, loading, or failed. */
    fun show(imageId: String) {
        if (state.value.status(imageId) != OpenCodeImageViewerPageStatus.Thumbnail) return
        start(imageId)
    }

    /** Tries a failed full picture again. */
    fun retry(imageId: String) {
        if (state.value.status(imageId) != OpenCodeImageViewerPageStatus.Failed) return
        start(imageId)
    }

    private fun start(imageId: String) {
        set(imageId, OpenCodeImageViewerPageStatus.Loading)
        jobs[imageId]?.cancel()
        jobs[imageId] =
            scope.launch {
                val outcome =
                    try {
                        load(imageId)
                        OpenCodeImageViewerPageStatus.Full
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        OpenCodeImageViewerPageStatus.Failed
                    }
                set(imageId, outcome)
            }
    }

    private fun set(
        imageId: String,
        status: OpenCodeImageViewerPageStatus,
    ) {
        mutableState.update { it.copy(statuses = it.statuses + (imageId to status)) }
    }
}
