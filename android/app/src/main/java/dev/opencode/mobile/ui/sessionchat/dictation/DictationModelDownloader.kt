package dev.opencode.mobile.ui.sessionchat.dictation

import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.speech.ModelDownloadListener
import android.speech.SpeechRecognizer
import dev.opencode.mobile.ui.sessionchat.ComposerDictationPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the card shows while Android fetches the on-device speech model. */
sealed interface DictationModelDownloadUpdate {
    /** [progress] is 0..1, or null while the system has not reported any. */
    data class Downloading(
        val progress: Float?,
    ) : DictationModelDownloadUpdate

    /** The system accepted the request but runs it later, or went quiet. */
    data object Queued : DictationModelDownloadUpdate

    data object Failed : DictationModelDownloadUpdate

    data object Installed : DictationModelDownloadUpdate
}

/** Events from one system download request. */
interface DictationModelDownloadEvents {
    fun onProgress(percent: Int)

    fun onInstalled()

    fun onScheduled()

    fun onFailed()

    /** The recognizer accepted the request but will not report on it; polling still works. */
    fun onEventsUnavailable()
}

/** Sends the system request. Returns false when the recognizer refused it outright. */
fun interface DictationModelDownloadRequest {
    fun send(events: DictationModelDownloadEvents): Boolean

    fun close() {}
}

/**
 * Drives one model download for the dictation card. Android 14+ recognizers report
 * progress through [ModelDownloadListener]; Android 13 and recognizers that report
 * nothing are covered by re-probing until the model shows up as installed. A
 * request that stays quiet for [quietTimeoutMs] is reported as [DictationModelDownloadUpdate.Queued].
 */
class DictationModelDownloader(
    private val scope: CoroutineScope,
    private val probe: suspend () -> DictationSupport,
    private val makeRequest: (languageTag: String?) -> DictationModelDownloadRequest?,
    private val now: () -> Long,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val quietTimeoutMs: Long = DEFAULT_QUIET_TIMEOUT_MS,
) {
    private var request: DictationModelDownloadRequest? = null
    private var watchJob: Job? = null
    private var onUpdate: ((DictationModelDownloadUpdate) -> Unit)? = null
    private var lastActivityMs = 0L
    private var generation = 0
    private var hostPaused = false

    /**
     * Some recognizers ask before downloading with their own dialog over the app.
     * Waiting on that dialog is not silence, so the quiet timeout restarts on resume.
     */
    fun setHostPaused(paused: Boolean) {
        hostPaused = paused
        if (!paused) lastActivityMs = now()
    }

    /**
     * Ask the system for the model and report until it is installed, queued, or failed.
     * With [alreadyPending] the system is already downloading, so only watch.
     */
    fun start(
        languageTag: String?,
        alreadyPending: Boolean,
        onUpdate: (DictationModelDownloadUpdate) -> Unit,
    ) {
        close()
        val run = ++generation
        this.onUpdate = onUpdate
        lastActivityMs = now()
        onUpdate(DictationModelDownloadUpdate.Downloading(progress = null))
        if (!alreadyPending) {
            val request = makeRequest(languageTag)
            if (request == null) {
                finish(run, DictationModelDownloadUpdate.Failed)
                return
            }
            this.request = request
            val sent =
                try {
                    request.send(events(run))
                } catch (_: RuntimeException) {
                    false
                }
            if (!sent) {
                finish(run, DictationModelDownloadUpdate.Failed)
                return
            }
        }
        if (run != generation) return
        watchJob = scope.launch { watch(run) }
    }

    fun close() {
        generation++
        onUpdate = null
        watchJob?.cancel()
        watchJob = null
        val current = request ?: return
        request = null
        try {
            current.close()
        } catch (_: RuntimeException) {
            // Already closed.
        }
    }

    private suspend fun watch(run: Int) {
        while (run == generation) {
            delay(pollIntervalMs)
            if (run != generation) return
            when (probe()) {
                is DictationSupport.Ready, DictationSupport.PermissionDenied -> {
                    finish(run, DictationModelDownloadUpdate.Installed)
                    return
                }
                is DictationSupport.ModelDownloading,
                is DictationSupport.ModelNotInstalled,
                DictationSupport.Hidden,
                -> Unit
            }
            if (run != generation) return
            if (!hostPaused && now() - lastActivityMs >= quietTimeoutMs) {
                finish(run, DictationModelDownloadUpdate.Queued)
                return
            }
        }
    }

    private fun events(run: Int) =
        object : DictationModelDownloadEvents {
            override fun onProgress(percent: Int) {
                if (run != generation) return
                lastActivityMs = now()
                onUpdate?.invoke(DictationModelDownloadUpdate.Downloading(percent.coerceIn(0, 100) / 100f))
            }

            override fun onInstalled() = finish(run, DictationModelDownloadUpdate.Installed)

            override fun onScheduled() = finish(run, DictationModelDownloadUpdate.Queued)

            override fun onFailed() = finish(run, DictationModelDownloadUpdate.Failed)

            override fun onEventsUnavailable() = Unit
        }

    private fun finish(
        run: Int,
        update: DictationModelDownloadUpdate,
    ) {
        if (run != generation) return
        val deliver = onUpdate
        close()
        deliver?.invoke(update)
    }

    companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 3_000L
        const val DEFAULT_QUIET_TIMEOUT_MS = 45_000L

        /** The system downloader: on-device [SpeechRecognizer], probed on the main thread. */
        fun system(
            context: Context,
            scope: CoroutineScope,
        ): DictationModelDownloader =
            DictationModelDownloader(
                scope = scope,
                probe = { probeDictationSupport(context) },
                makeRequest = { languageTag -> systemModelDownloadRequest(context, languageTag) },
                now = SystemClock::elapsedRealtime,
            )
    }
}

/**
 * Holds the recognizer that requested the download until the request finishes.
 * Destroying it at once can drop the service connection before the system accepts it.
 */
private fun systemModelDownloadRequest(
    context: Context,
    languageTag: String?,
): DictationModelDownloadRequest? {
    if (Build.VERSION.SDK_INT < 33) return null
    if (Looper.myLooper() != Looper.getMainLooper()) return null
    if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return null
    val recognizer =
        try {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } catch (_: RuntimeException) {
            return null
        }
    return object : DictationModelDownloadRequest {
        override fun send(events: DictationModelDownloadEvents): Boolean {
            val intent = dictationRecognitionIntent(context, languageTag)
            if (Build.VERSION.SDK_INT >= 34) {
                recognizer.triggerModelDownload(
                    intent,
                    context.mainExecutor,
                    object : ModelDownloadListener {
                        override fun onProgress(completedPercent: Int) = events.onProgress(completedPercent)

                        override fun onSuccess() = events.onInstalled()

                        override fun onScheduled() = events.onScheduled()

                        override fun onError(error: Int) {
                            if (error == SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS) {
                                events.onEventsUnavailable()
                            } else {
                                events.onFailed()
                            }
                        }
                    },
                )
            } else {
                recognizer.triggerModelDownload(intent)
            }
            return true
        }

        override fun close() {
            recognizer.destroy()
        }
    }
}

/** The card phase for a download update; null for [DictationModelDownloadUpdate.Installed], which starts listening. */
internal fun DictationModelDownloadUpdate.cardPhase(): Pair<ComposerDictationPhase, Float?>? =
    when (this) {
        is DictationModelDownloadUpdate.Downloading -> ComposerDictationPhase.ModelDownloading to progress
        DictationModelDownloadUpdate.Queued -> ComposerDictationPhase.ModelDownloadQueued to null
        DictationModelDownloadUpdate.Failed -> ComposerDictationPhase.ModelDownloadFailed to null
        DictationModelDownloadUpdate.Installed -> null
    }
