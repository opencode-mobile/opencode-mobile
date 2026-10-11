package dev.opencode.mobile.ui.sessionchat.dictation

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import dev.opencode.mobile.ui.sessionchat.ComposerDictationPhase

interface ComposerDictationSession {
    fun start(listener: Listener)

    fun stop()

    interface Listener {
        fun onPartial(
            committed: String,
            volatile: String,
        )

        fun onLevel(level: Float)

        fun onUnavailable(
            phase: ComposerDictationPhase,
            message: String?,
        )
    }
}

/**
 * On-device [SpeechRecognizer] session. Create, listen, and [destroy] on the main thread.
 * A finished utterance stays on the card; the caller inserts. Silence restarts listening once.
 */
class SystemComposerDictationSession(
    private val context: Context,
) : ComposerDictationSession {
    private var recognizer: SpeechRecognizer? = null
    private var listener: ComposerDictationSession.Listener? = null
    private var committed: String = ""
    private var volatileHypothesis: String = ""
    private var acceptCallbacks: Boolean = false
    private var silenceRestarts: Int = 0

    override fun start(listener: ComposerDictationSession.Listener) {
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
        committed = ""
        volatileHypothesis = ""
        silenceRestarts = 0
        acceptCallbacks = true
        recognizer.setRecognitionListener(recognitionListener)
        startListening()
    }

    override fun stop() {
        acceptCallbacks = false
        listener = null
        val current = recognizer
        recognizer = null
        if (current == null) return
        try {
            current.destroy()
        } catch (_: RuntimeException) {
            // Already destroyed.
        }
    }

    /**
     * A pause ends the current utterance. Keep its hypothesis so the next partial
     * cannot replace words the user already saw, and do not turn a later empty
     * timeout into an error card when Insert can still use that transcript.
     */
    private fun recoverFromSilence() {
        when (
            val recovery =
                recoverDictationSilence(
                    committed = committed,
                    volatile = volatileHypothesis,
                    silenceRestarts = silenceRestarts,
                )
        ) {
            is DictationSilenceRecovery.Restart -> {
                committed = recovery.committed
                volatileHypothesis = ""
                silenceRestarts = recovery.silenceRestarts
                listener?.onPartial(committed, "")
                startListening()
            }
            is DictationSilenceRecovery.Keep -> {
                committed = recovery.committed
                volatileHypothesis = ""
                listener?.onPartial(committed, "")
                releaseRecognizerKeepCard()
            }
            DictationSilenceRecovery.Fail ->
                fail(ComposerDictationPhase.RecognizerError, "Dictation stopped")
        }
    }

    private fun startListening() {
        val recognizer = recognizer ?: return
        try {
            recognizer.startListening(dictationRecognitionIntent(context))
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
        val recognizer = recognizer
        this.recognizer = null
        listener = null
        if (recognizer != null) {
            try {
                recognizer.destroy()
            } catch (_: RuntimeException) {
                // Already destroyed.
            }
        }
        current?.onUnavailable(phase, message)
    }

    private fun releaseRecognizerKeepCard() {
        acceptCallbacks = false
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

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) {
                if (!acceptCallbacks) return
                listener?.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                if (!acceptCallbacks) return
                when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        fail(ComposerDictationPhase.PermissionDenied, null)
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    -> recoverFromSilence()
                    else -> fail(ComposerDictationPhase.RecognizerError, recognizerErrorMessage(error))
                }
            }

            override fun onResults(results: Bundle) {
                if (!acceptCallbacks) return
                val text = firstHypothesis(results)
                volatileHypothesis = ""
                if (text.isNotEmpty()) {
                    committed = if (committed.isEmpty()) text else committed + text
                    silenceRestarts = 0
                }
                listener?.onPartial(committed, "")
                // Leave the card up. Do not insert when recognition ends.
                releaseRecognizerKeepCard()
            }

            override fun onPartialResults(partialResults: Bundle) {
                if (!acceptCallbacks) return
                volatileHypothesis = firstHypothesis(partialResults)
                if (volatileHypothesis.isNotEmpty()) silenceRestarts = 0
                listener?.onPartial(committed, volatileHypothesis)
            }

            override fun onEvent(
                eventType: Int,
                params: Bundle?,
            ) = Unit
        }
}

internal sealed interface DictationSilenceRecovery {
    data class Restart(
        val committed: String,
        val silenceRestarts: Int,
    ) : DictationSilenceRecovery

    data class Keep(
        val committed: String,
    ) : DictationSilenceRecovery

    data object Fail : DictationSilenceRecovery
}

/** Fold a volatile hypothesis into committed, then restart or keep the card. */
internal fun recoverDictationSilence(
    committed: String,
    volatile: String,
    silenceRestarts: Int,
    maxRestarts: Int = 1,
): DictationSilenceRecovery {
    val promoted =
        when {
            volatile.isEmpty() -> committed
            committed.isEmpty() -> volatile
            else -> committed + volatile
        }
    return when {
        silenceRestarts < maxRestarts ->
            DictationSilenceRecovery.Restart(
                committed = promoted,
                silenceRestarts = silenceRestarts + 1,
            )
        promoted.isNotEmpty() -> DictationSilenceRecovery.Keep(promoted)
        else -> DictationSilenceRecovery.Fail
    }
}

private fun firstHypothesis(results: Bundle): String =
    results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

private fun recognizerErrorMessage(error: Int): String =
    when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Couldn't use the microphone"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Dictation is busy"
        else -> "Dictation stopped"
    }
