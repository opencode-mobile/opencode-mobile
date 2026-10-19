package dev.opencode.mobile.ui.sessionchat.dictation

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import dev.opencode.mobile.ui.sessionchat.ComposerDictationPhase
import kotlin.math.sqrt

interface ComposerDictationSession {
    /** [languageTag] is the recognizer pack from the probe; null uses the device locale. */
    fun start(
        listener: Listener,
        languageTag: String? = null,
    )

    fun stop()

    interface Listener {
        fun onPartial(
            committed: String,
            volatile: String,
        )

        fun onLevel(level: Float)

        /** Listening ended on its own. The transcript stays on the card for Insert. */
        fun onStopped()

        fun onUnavailable(
            phase: ComposerDictationPhase,
            message: String?,
        )
    }
}

/**
 * On-device [SpeechRecognizer] session. Create, listen, and [destroy] on the main thread.
 *
 * The system recognizer ends each utterance on a pause. The card stays open until Cancel
 * or Insert, so every finished utterance is committed and listening restarts.
 */
class SystemComposerDictationSession(
    private val context: Context,
) : ComposerDictationSession {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var languageTag: String? = null
    private var listener: ComposerDictationSession.Listener? = null
    private var committed: String = ""
    private var volatileHypothesis: String = ""
    private var acceptCallbacks: Boolean = false
    private var restartFailures: Int = 0
    private val restartRunnable = Runnable { startListening() }

    override fun start(
        listener: ComposerDictationSession.Listener,
        languageTag: String?,
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        stop()
        if (Build.VERSION.SDK_INT < 31) {
            listener.onUnavailable(ComposerDictationPhase.RecognizerError, "Dictation stopped")
            return
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            listener.onUnavailable(ComposerDictationPhase.RecognizerError, "Dictation stopped")
            return
        }
        val recognizer =
            try {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } catch (_: RuntimeException) {
                listener.onUnavailable(ComposerDictationPhase.RecognizerError, "Dictation stopped")
                return
            }
        this.listener = listener
        this.recognizer = recognizer
        this.languageTag = languageTag
        committed = ""
        volatileHypothesis = ""
        restartFailures = 0
        acceptCallbacks = true
        recognizer.setRecognitionListener(recognitionListener)
        startListening()
    }

    override fun stop() {
        acceptCallbacks = false
        listener = null
        mainHandler.removeCallbacks(restartRunnable)
        releaseRecognizer()
    }

    /** Commit what the user saw, then listen for the next utterance. */
    private fun continueListening(
        utterance: String,
        delayMs: Long = 0L,
    ) {
        committed = appendDictationUtterance(committed, utterance)
        volatileHypothesis = ""
        listener?.onPartial(committed, "")
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.postDelayed(restartRunnable, delayMs)
    }

    private fun startListening() {
        if (!acceptCallbacks) return
        val recognizer = recognizer ?: return
        try {
            recognizer.startListening(dictationRecognitionIntent(context, languageTag))
        } catch (_: RuntimeException) {
            fail(ComposerDictationPhase.RecognizerError, "Dictation stopped")
        }
    }

    private fun fail(
        phase: ComposerDictationPhase,
        message: String?,
    ) {
        val current = listener
        acceptCallbacks = false
        listener = null
        mainHandler.removeCallbacks(restartRunnable)
        releaseRecognizer()
        current?.onUnavailable(phase, message)
    }

    /** Stop listening but leave the transcript on the card so Insert still works. */
    private fun keepTranscript() {
        val current = listener
        committed = appendDictationUtterance(committed, volatileHypothesis)
        volatileHypothesis = ""
        acceptCallbacks = false
        listener = null
        mainHandler.removeCallbacks(restartRunnable)
        releaseRecognizer()
        current?.let { notifyDictationStopped(it, committed) }
    }

    private fun releaseRecognizer() {
        val current = recognizer
        recognizer = null
        if (current == null) return
        try {
            current.destroy()
        } catch (_: RuntimeException) {
            // Already destroyed.
        }
    }

    private val recognitionListener =
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit

            override fun onBeginningOfSpeech() {
                restartFailures = 0
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (!acceptCallbacks) return
                listener?.onLevel(dictationLevelFromRmsDb(rmsdB))
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                if (!acceptCallbacks) return
                listener?.onLevel(0f)
            }

            override fun onError(error: Int) {
                if (!acceptCallbacks) return
                when (dictationErrorAction(error, restartFailures, hasTranscript())) {
                    DictationErrorAction.Restart -> continueListening(volatileHypothesis)
                    DictationErrorAction.RetryLater -> {
                        restartFailures += 1
                        continueListening(volatileHypothesis, delayMs = RetryDelayMs * restartFailures)
                    }
                    DictationErrorAction.KeepTranscript -> keepTranscript()
                    DictationErrorAction.PermissionDenied ->
                        fail(ComposerDictationPhase.PermissionDenied, null)
                    DictationErrorAction.Fail ->
                        fail(ComposerDictationPhase.RecognizerError, recognizerErrorMessage(error))
                }
            }

            override fun onResults(results: Bundle) {
                if (!acceptCallbacks) return
                // A pause ends the recognizer's utterance, not the user's dictation.
                continueListening(firstHypothesis(results).ifEmpty { volatileHypothesis })
            }

            override fun onPartialResults(partialResults: Bundle) {
                if (!acceptCallbacks) return
                val hypothesis = firstHypothesis(partialResults)
                if (hypothesis.isEmpty()) return
                volatileHypothesis = hypothesis
                listener?.onPartial(committed, dictationVolatileTail(committed, hypothesis))
            }

            override fun onEvent(
                eventType: Int,
                params: Bundle?,
            ) = Unit
        }

    private fun hasTranscript(): Boolean = appendDictationUtterance(committed, volatileHypothesis).isNotBlank()

    private companion object {
        const val RetryDelayMs = 250L
    }
}

internal enum class DictationErrorAction {
    Restart,
    RetryLater,
    KeepTranscript,
    PermissionDenied,
    Fail,
}

private const val MaxRestartFailures = 3

/** Silence keeps listening; a busy recognizer gets a few delayed retries. */
internal fun dictationErrorAction(
    error: Int,
    restartFailures: Int,
    hasTranscript: Boolean,
): DictationErrorAction =
    when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> DictationErrorAction.PermissionDenied
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> DictationErrorAction.Restart
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        SpeechRecognizer.ERROR_CLIENT,
        ->
            when {
                restartFailures < MaxRestartFailures -> DictationErrorAction.RetryLater
                hasTranscript -> DictationErrorAction.KeepTranscript
                else -> DictationErrorAction.Fail
            }
        else -> if (hasTranscript) DictationErrorAction.KeepTranscript else DictationErrorAction.Fail
    }

/** Show the final transcript, settle the orb, then report that listening ended. */
internal fun notifyDictationStopped(
    listener: ComposerDictationSession.Listener,
    committed: String,
) {
    listener.onPartial(committed, "")
    listener.onLevel(0f)
    listener.onStopped()
}

/** Append a finished utterance with one space between words. */
internal fun appendDictationUtterance(
    committed: String,
    utterance: String,
): String {
    val next = utterance.trim()
    if (next.isEmpty()) return committed
    if (committed.isEmpty()) return next
    return committed + dictationSeparator(committed) + next
}

/** The volatile hypothesis as shown after committed text. */
internal fun dictationVolatileTail(
    committed: String,
    hypothesis: String,
): String {
    val next = hypothesis.trim()
    if (next.isEmpty() || committed.isEmpty()) return next
    return dictationSeparator(committed) + next
}

private fun dictationSeparator(committed: String): String = if (committed.last().isWhitespace()) "" else " "

/**
 * Map [RecognitionListener.onRmsChanged] dB to 0...1. Recognizers report roughly
 * -2 dB in silence and 8 to 10 dB for normal speech.
 */
internal fun dictationLevelFromRmsDb(rmsdB: Float): Float {
    val normalized = ((rmsdB - SilenceDb) / (LoudDb - SilenceDb)).coerceIn(0f, 1f)
    return sqrt(normalized)
}

private const val SilenceDb = 0f
private const val LoudDb = 10f

private fun firstHypothesis(results: Bundle): String =
    results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

private fun recognizerErrorMessage(error: Int): String =
    when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Couldn't use the microphone"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Dictation is busy"
        else -> "Dictation stopped"
    }
