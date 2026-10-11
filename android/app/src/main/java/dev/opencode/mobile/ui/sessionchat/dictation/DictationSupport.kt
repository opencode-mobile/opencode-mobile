package dev.opencode.mobile.ui.sessionchat.dictation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

sealed interface DictationSupport {
    data object Hidden : DictationSupport

    data class Ready(
        val languageLabel: String,
        val onDevice: Boolean = true,
    ) : DictationSupport

    data object PermissionDenied : DictationSupport

    data object ModelNotInstalled : DictationSupport

    val showsMic: Boolean
        get() = this !is Hidden
}

internal fun formatDictationElapsed(
    startedAtEpochMs: Long,
    nowEpochMs: Long,
): String {
    val totalSeconds = ((nowEpochMs - startedAtEpochMs).coerceAtLeast(0L) / 1000L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

internal fun hasRecordAudioPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

internal fun dictationRecognitionIntent(context: Context): Intent {
    val locale = Locale.getDefault()
    return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }
}

internal fun dictationLanguageLabel(locale: Locale = Locale.getDefault()): String =
    locale.displayLanguage.ifBlank { locale.toLanguageTag() }

internal fun localeInstalled(
    languages: List<String>,
    locale: Locale,
): Boolean {
    if (languages.isEmpty()) return false
    val tag = locale.toLanguageTag()
    val language = locale.language
    return languages.any { candidate ->
        candidate.equals(tag, ignoreCase = true) ||
            candidate.equals(language, ignoreCase = true) ||
            candidate.startsWith("$language-", ignoreCase = true) ||
            candidate.startsWith("${language}_", ignoreCase = true) ||
            tag.startsWith("$candidate-", ignoreCase = true)
    }
}

/**
 * Probe on-device dictation. API < 33 and a missing recognizer are [DictationSupport.Hidden].
 * Must be started on the main thread; the support callback also runs on the main executor.
 */
suspend fun probeDictationSupport(context: Context): DictationSupport {
    if (Build.VERSION.SDK_INT < 33) return DictationSupport.Hidden
    if (Looper.myLooper() != Looper.getMainLooper()) return DictationSupport.Hidden
    if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return DictationSupport.Hidden
    val recognizer =
        try {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } catch (_: RuntimeException) {
            return DictationSupport.Hidden
        }
    val locale = Locale.getDefault()
    return try {
        suspendCancellableCoroutine { continuation ->
            var released = false

            fun release() {
                if (released) return
                released = true
                try {
                    recognizer.destroy()
                } catch (_: RuntimeException) {
                    // Already destroyed.
                }
            }
            continuation.invokeOnCancellation { release() }
            try {
                recognizer.checkRecognitionSupport(
                    dictationRecognitionIntent(context),
                    context.mainExecutor,
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(support: RecognitionSupport) {
                            val result = interpretRecognitionSupport(context, support, locale)
                            release()
                            if (continuation.isActive) continuation.resume(result)
                        }

                        override fun onError(error: Int) {
                            release()
                            if (continuation.isActive) continuation.resume(DictationSupport.Hidden)
                        }
                    },
                )
            } catch (_: RuntimeException) {
                release()
                if (continuation.isActive) continuation.resume(DictationSupport.Hidden)
            }
        }
    } catch (_: RuntimeException) {
        DictationSupport.Hidden
    }
}

internal fun interpretRecognitionSupport(
    context: Context,
    support: RecognitionSupport,
    locale: Locale = Locale.getDefault(),
): DictationSupport {
    val installed = support.installedOnDeviceLanguages.orEmpty()
    val pending = support.pendingOnDeviceLanguages.orEmpty()
    val downloadable = support.supportedOnDeviceLanguages.orEmpty()
    return when {
        localeInstalled(installed, locale) ->
            if (hasRecordAudioPermission(context)) {
                DictationSupport.Ready(languageLabel = dictationLanguageLabel(locale), onDevice = true)
            } else {
                DictationSupport.PermissionDenied
            }
        localeInstalled(pending, locale) || localeInstalled(downloadable, locale) ->
            DictationSupport.ModelNotInstalled
        else -> DictationSupport.Hidden
    }
}

/**
 * Holds the recognizer that requested a model download until the screen leaves.
 * [SpeechRecognizer.triggerModelDownload] is the API 33 overload; destroying immediately
 * can drop the service connection before the system accepts the request.
 */
class DictationModelDownloader(
    private val context: Context,
) {
    private var recognizer: SpeechRecognizer? = null

    fun trigger() {
        if (Build.VERSION.SDK_INT < 33) return
        if (Looper.myLooper() != Looper.getMainLooper()) return
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return
        val recognizer =
            try {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } catch (_: RuntimeException) {
                return
            }
        close()
        this.recognizer = recognizer
        try {
            recognizer.triggerModelDownload(dictationRecognitionIntent(context))
        } catch (_: RuntimeException) {
            this.recognizer = null
            try {
                recognizer.destroy()
            } catch (_: RuntimeException) {
                // Already destroyed.
            }
        }
    }

    fun close() {
        val current = recognizer ?: return
        recognizer = null
        try {
            current.destroy()
        } catch (_: RuntimeException) {
            // Already destroyed.
        }
    }
}
