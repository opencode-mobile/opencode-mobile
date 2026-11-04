package dev.opencode.voiceengine

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** How an engine runs: on the fast cores only (the default) or all of them, and ONNX Runtime's provider. */
data class EngineOptions(
    val allCores: Boolean = false,
    /** sherpa-onnx only: "cpu", "xnnpack" (the default: fastest measured, NNAPI matched it) or "nnapi". */
    val provider: String = "xnnpack",
) {
    fun label(model: SpeechModel): String =
        buildList {
            add(if (allCores) "all cores" else "fast cores")
            if (model.engine == "sherpa-onnx") add(provider)
        }.joinToString(" · ")
}

/** Something that turns 16 kHz mono speech into text. */
interface SpeechEngine {
    /** Transcribes a whole take at once. */
    suspend fun transcribe(pcm: FloatArray): String

    /** Engines that listen while you talk return a session to feed as the take records; the others return null. */
    fun startStream(): SpeechStream? = null

    /** True for engines whose [startStream] returns a session. */
    val listensWhileYouTalk: Boolean get() = false

    fun close()
}

/** A take fed to the engine while it records. [finish] ends it and waits for the text. */
interface SpeechStream {
    fun accept(pcm: FloatArray)

    suspend fun finish(): String

    fun cancel()
}

internal fun openEngine(
    context: Context,
    store: ModelStore,
    model: SpeechModel,
    options: EngineOptions,
    cores: Cores,
): SpeechEngine {
    val threads = if (options.allCores) cores.count else cores.fastCount
    val mask = if (options.allCores) 0L else cores.fastMask
    return when (model.engine) {
        "sherpa-onnx" -> SherpaOnnxEngine(store, model, threads, options.provider, mask)
        "litert" -> LiteRtEngine(store, model, threads, gpu = model.kind == "tdt_gpu", context.cacheDir, mask)
        "android" -> AndroidSystemEngine(context)
        else -> error("Unknown engine ${model.engine}")
    }
}

/**
 * sherpa-onnx: ONNX Runtime with an ONNX export of the model. ONNX Runtime starts its worker threads when the
 * recognizer is created, so creating it on the fast cores keeps the workers there too.
 */
private class SherpaOnnxEngine(
    store: ModelStore,
    model: SpeechModel,
    threads: Int,
    provider: String,
    private val mask: Long,
) : SpeechEngine {
    private val recognizer: OfflineRecognizer

    init {
        fun path(suffix: String) = store.file(model, model.files.first { it.name.startsWith(suffix) }.name).path
        val config =
            OfflineRecognizerConfig(
                modelConfig =
                    OfflineModelConfig(
                        transducer =
                            OfflineTransducerModelConfig(
                                encoder = path("encoder"),
                                decoder = path("decoder"),
                                joiner = path("joiner"),
                            ),
                        tokens = path("tokens"),
                        numThreads = threads,
                        provider = provider,
                        modelType = model.kind,
                    ),
                decodingMethod = "greedy_search",
            )
        recognizer = onCores(mask) { OfflineRecognizer(config = config) }
    }

    override suspend fun transcribe(pcm: FloatArray): String =
        withContext(Dispatchers.Default) {
            onCores(mask) {
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(pcm, VoiceEngine.SAMPLE_RATE)
                    recognizer.decode(stream)
                    recognizer.getResult(stream).text
                } finally {
                    stream.release()
                }
            }
        }

    override fun close() = recognizer.release()
}

/** Runs [block] with the calling thread (and the threads it starts) on [mask]'s cores; 0 means any core. */
internal inline fun <T> onCores(
    mask: Long,
    block: () -> T,
): T {
    if (mask == 0L) return block()
    val previous = CpuAffinity.set(mask)
    try {
        return block()
    } finally {
        if (previous != 0L) CpuAffinity.set(previous)
    }
}

/**
 * The phone's own on-device recognizer (Android 13 and later), fed our audio through a pipe rather than its own
 * microphone so it hears exactly what the other engines hear. It works while audio arrives, so a take fed live
 * leaves only its last moment to finish when you let go.
 */
private class AndroidSystemEngine(
    private val context: Context,
) : SpeechEngine {
    init {
        check(Build.VERSION.SDK_INT >= 33) { "Needs Android 13 or later." }
        check(SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) { "This phone has no on-device speech recognizer." }
    }

    override suspend fun transcribe(pcm: FloatArray): String {
        val stream = startStream()
        withContext(Dispatchers.IO) { stream.accept(pcm) }
        return stream.finish()
    }

    override fun startStream(): SpeechStream = SystemStream(context)

    override val listensWhileYouTalk = true

    override fun close() {}
}

private class SystemStream(
    context: Context,
) : SpeechStream {
    private val main = Handler(Looper.getMainLooper())
    private val result = CompletableDeferred<String>()
    private var lastPartial = ""
    private val pipe = ParcelFileDescriptor.createPipe()
    private val output: OutputStream = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])

    // Writes go through one background thread: the pipe blocks when the recognizer stops reading (it may end
    // a take early), and a block must never reach the caller, which can be the main thread.
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "android-speech-pipe").apply { isDaemon = true } }
    private lateinit var recognizer: SpeechRecognizer

    init {
        val intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, VoiceEngine.SAMPLE_RATE)
                // a take ends when we close the pipe, not at the first pause (Google's service reads these as Int)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 60_000)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 60_000)
        val listener =
            object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    // Google's on-device service ends audio-source sessions with an empty bundle: the text is in
                    // its last partial result
                    val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    result.complete(text?.takeIf { it.isNotBlank() } ?: lastPartial)
                }

                override fun onError(error: Int) {
                    result.completeExceptionally(IllegalStateException("Android speech: ${errorName(error)}"))
                }

                override fun onReadyForSpeech(params: Bundle?) {}

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {}

                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { lastPartial = it }
                }

                override fun onEvent(
                    eventType: Int,
                    params: Bundle?,
                ) {}
            }
        // the recognizer must be created and started on the main thread
        val started = CountDownLatch(1)
        val start = {
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer.setRecognitionListener(listener)
            recognizer.startListening(intent)
            started.countDown()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) start() else main.post(start)
        started.await()
    }

    override fun accept(pcm: FloatArray) {
        val bytes = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            val v = (pcm[i] * 32767f).toInt().coerceIn(-32768, 32767)
            bytes[2 * i] = v.toByte()
            bytes[2 * i + 1] = (v shr 8).toByte()
        }
        if (!result.isCompleted) writer.execute { runCatching { output.write(bytes) } }
    }

    override suspend fun finish(): String {
        writer.execute { runCatching { output.close() } }
        try {
            return result.await()
        } finally {
            // the recognizer reads our pipe after startListening returns, so its end stays open until the take is over
            // destroying the recognizer and closing our read end also unblocks a write it stopped reading
            main.post {
                recognizer.destroy()
                pipe[0].close()
            }
            writer.shutdown()
        }
    }

    override fun cancel() {
        writer.execute { runCatching { output.close() } }
        writer.shutdown()
        main.post {
            recognizer.cancel()
            recognizer.destroy()
            pipe[0].close()
        }
    }
}

private fun errorName(error: Int) =
    when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "no speech recognised"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "English is not supported on this phone"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "the English speech pack is not downloaded"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone permission is missing"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "the recognizer is busy"
        SpeechRecognizer.ERROR_AUDIO -> "audio error"
        SpeechRecognizer.ERROR_CLIENT -> "client error"
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "service error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech heard"
        else -> "error $error"
    }
