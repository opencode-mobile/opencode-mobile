package dev.opencode.mobile.ui.sessionchat.dictation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.icu.util.ULocale
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

    /** [languageTag] is the recognizer's own pack for the device locale; null means the locale itself. */
    data class Ready(
        val languageLabel: String,
        val onDevice: Boolean = true,
        override val languageTag: String? = null,
    ) : DictationSupport

    data object PermissionDenied : DictationSupport

    data class ModelNotInstalled(
        override val languageTag: String,
    ) : DictationSupport

    /** The system already lists the locale's model as pending. */
    data class ModelDownloading(
        override val languageTag: String,
    ) : DictationSupport

    /** The language pack to request, which can differ from the locale (en-DE uses en-US). */
    val languageTag: String?
        get() = null

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

internal fun dictationRecognitionIntent(
    context: Context,
    languageTag: String? = null,
): Intent {
    val language = languageTag ?: Locale.getDefault().toLanguageTag()
    return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }
}

internal fun dictationLanguageLabel(locale: Locale = Locale.getDefault()): String =
    locale.displayLanguage.ifBlank { locale.toLanguageTag() }

/**
 * The recognizer's pack for [locale]: the exact tag, else a pack for the same language,
 * preferring the language's likely region (en-DE has no pack, so it uses en-US).
 */
internal fun matchingLanguagePack(
    packs: List<String>,
    locale: Locale,
    likelyTag: (String) -> String? = ::likelyLanguageTag,
): String? {
    if (packs.isEmpty()) return null
    val tag = locale.toLanguageTag()
    packs.firstOrNull { it.normalizedTag().equals(tag, ignoreCase = true) }?.let { return it }
    val language = locale.language
    val sameLanguage =
        packs.filter { pack ->
            val normalized = pack.normalizedTag()
            normalized.equals(language, ignoreCase = true) || normalized.startsWith("$language-", ignoreCase = true)
        }
    if (sameLanguage.isEmpty()) return null
    val likely = likelyTag(language)
    return sameLanguage.firstOrNull { it.normalizedTag().equals(likely, ignoreCase = true) } ?: sameLanguage.first()
}

private fun String.normalizedTag(): String = replace('_', '-')

/** en -> en-US, de -> de-DE, pt -> pt-BR, from CLDR likely subtags. */
private fun likelyLanguageTag(language: String): String? =
    try {
        val likely = ULocale.addLikelySubtags(ULocale(language))
        "${likely.language}-${likely.country}"
    } catch (_: RuntimeException) {
        null
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
    val hasPermission = hasRecordAudioPermission(context)
    return dictationSupportFor(
        installed = support.installedOnDeviceLanguages.orEmpty(),
        pending = support.pendingOnDeviceLanguages.orEmpty(),
        supported = support.supportedOnDeviceLanguages.orEmpty(),
        locale = locale,
        hasPermission = hasPermission,
    )
}

internal fun dictationSupportFor(
    installed: List<String>,
    pending: List<String>,
    supported: List<String>,
    locale: Locale,
    hasPermission: Boolean,
    likelyTag: (String) -> String? = ::likelyLanguageTag,
): DictationSupport {
    matchingLanguagePack(installed, locale, likelyTag)?.let { pack ->
        return if (hasPermission) {
            DictationSupport.Ready(languageLabel = dictationLanguageLabel(locale), onDevice = true, languageTag = pack)
        } else {
            DictationSupport.PermissionDenied
        }
    }
    matchingLanguagePack(pending, locale, likelyTag)?.let { return DictationSupport.ModelDownloading(it) }
    matchingLanguagePack(supported, locale, likelyTag)?.let { return DictationSupport.ModelNotInstalled(it) }
    return DictationSupport.Hidden
}
