package dev.opencode.voiceengine

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

/**
 * The voice engine as Hex Code sees it: get the chosen speech model ready, record one take, turn it into text.
 * By default that is Hex's model, Parakeet V2, on sherpa-onnx ([SpeechModels.DICTATION_DEFAULT]); the speech model
 * lab can switch to others. Speech never leaves the phone.
 */
class VoiceEngine(
    context: Context,
) {
    sealed interface ModelState {
        data object NotLoaded : ModelState

        data class Preparing(
            val fraction: Double,
            val detail: String,
        ) : ModelState

        data object Ready : ModelState

        data class Failed(
            val message: String,
        ) : ModelState
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("voice", Context.MODE_PRIVATE)
    val models: List<SpeechModel> = SpeechModels.load(appContext)
    val store = ModelStore(appContext)

    private val active =
        MutableStateFlow(
            models.firstOrNull { it.id == prefs.getString("model", null) } ?: models.first { it.id == SpeechModels.DICTATION_DEFAULT },
        )
    val activeModel: StateFlow<SpeechModel> = active.asStateFlow()
    val modelName: String get() = active.value.title

    var options: EngineOptions =
        EngineOptions(
            allCores = prefs.getBoolean("allCores", false),
            provider =
                prefs.getString("provider", null) ?: EngineOptions().provider,
        )
        private set

    private val state = MutableStateFlow<ModelState>(ModelState.NotLoaded)
    val modelState: StateFlow<ModelState> = state.asStateFlow()

    private var engine: SpeechEngine? = null
    private val lock = Mutex()

    // Phones mix fast and slow cores, and ggml splits each step evenly across its threads, so one thread on a
    // little core holds everything up. Runs use one thread per fast core and stay on those cores.
    internal val cores = Cores.read()
    private var recorder: AudioRecord? = null
    private var reader: Thread? = null
    private var stream: SpeechStream? = null
    private val samples = FloatBuffer()

    /** Loudness of the current take, 0 to 1, for a meter. */
    @Volatile var level: Float = 0f
        private set

    val isRecording: Boolean get() = recorder != null

    /** "8 cores, 4 fast" and the chip, for showing next to results. */
    val deviceDetail: String
        get() =
            listOfNotNull(
                if (Build.VERSION.SDK_INT >=
                    31
                ) {
                    Build.SOC_MODEL
                } else {
                    null
                },
                "${cores.count} cores, ${cores.fastCount} fast",
            ).joinToString(" · ")

    /** Downloads the chosen model if needed and loads it. Safe to call again. */
    suspend fun prepare() {
        lock.withLock {
            if (engine != null) return
            val model = active.value
            withContext(Dispatchers.IO) {
                try {
                    if (!store.isInstalled(model)) {
                        store.download(model) { done, all ->
                            state.value = ModelState.Preparing(done.toDouble() / all, "Downloading · ${done / 1_000_000} MB")
                        }
                    }
                    state.value = ModelState.Preparing(1.0, "Loading the model")
                    engine = openEngine(appContext, store, model, options, cores)
                    state.value = ModelState.Ready
                } catch (e: Exception) {
                    state.value = ModelState.Failed(e.message ?: "Could not get speech ready")
                }
            }
        }
    }

    /** Switches dictation to [model] with [options] and gets it ready. */
    suspend fun use(
        model: SpeechModel,
        options: EngineOptions = this.options,
    ) {
        unload()
        active.value = model
        this.options = options
        prefs
            .edit()
            .putString("model", model.id)
            .putBoolean("allCores", options.allCores)
            .putString("provider", options.provider)
            .apply()
        prepare()
    }

    /**
     * Loads [model] on its own (dictation's model is unloaded first, so two big models never share memory),
     * hands it to [block] with the time loading took, then brings dictation's model back.
     */
    suspend fun <T> withModel(
        model: SpeechModel,
        options: EngineOptions,
        block: suspend (SpeechEngine, Double) -> T,
    ): T {
        unload()
        try {
            var opened: SpeechEngine? = null
            val loadMs = withContext(Dispatchers.IO) { SpeechLab.timed { opened = openEngine(appContext, store, model, options, cores) } }
            val e = opened!!
            try {
                return block(e, loadMs)
            } finally {
                e.close()
            }
        } finally {
            prepare()
        }
    }

    private suspend fun unload() {
        lock.withLock {
            engine?.close()
            engine = null
            state.value = ModelState.NotLoaded
        }
    }

    /** Starts a take. The caller must hold RECORD_AUDIO. */
    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (recorder != null) return
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record =
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, SAMPLE_RATE / 5 * 2),
            )
        samples.clear()
        // engines that listen while you talk get the take as it records
        val live = engine?.startStream()
        stream = live
        record.startRecording()
        recorder = record
        reader =
            Thread {
                val chunk = ShortArray(SAMPLE_RATE / 20)
                while (recorder === record) {
                    val n = record.read(chunk, 0, chunk.size)
                    if (n <= 0) continue
                    var sum = 0.0
                    val floats = FloatArray(n)
                    for (i in 0 until n) {
                        val v = chunk[i] / 32768f
                        floats[i] = v
                        samples.add(v)
                        sum += v * v
                    }
                    live?.accept(floats)
                    level = (sqrt(sum / n) * 6).toFloat().coerceIn(0f, 1f)
                }
            }.apply { start() }
    }

    /** Ends the take and returns its text. Most engines transcribe after the take, not while you talk. */
    suspend fun stopAndTranscribe(): String {
        val live = stream
        val pcm = stop() ?: error("Nothing is being recorded.")
        stream = null
        if (live != null) return live.finish().trim().ifEmpty { error("No speech was detected.") }
        return transcribe(pcm)
    }

    /** Transcribes 16 kHz mono samples, for takes, checks and tests. */
    suspend fun transcribe(pcm: FloatArray): String {
        val e = engine ?: error("${active.value.title} is not ready.")
        return e.transcribe(pcm).trim().ifEmpty { error("No speech was detected.") }
    }

    /** Drops the take without transcribing it. */
    fun cancelRecording() {
        stream?.cancel()
        stream = null
        stop()
    }

    private fun stop(): FloatArray? {
        val record = recorder ?: return null
        recorder = null
        reader?.join(500)
        reader = null
        record.stop()
        record.release()
        level = 0f
        return samples.toArray()
    }

    fun close() {
        cancelRecording()
        engine?.close()
        engine = null
        state.value = ModelState.NotLoaded
    }

    companion object {
        const val SAMPLE_RATE = 16_000

        /** Reads a 16-bit mono WAV into samples (debug samples and tests). */
        fun readWav(file: File): FloatArray = readWav(file.readBytes())

        fun readWav(bytes: ByteArray): FloatArray {
            var offset = 12
            while (offset + 8 <= bytes.size) {
                val id = String(bytes, offset, 4)
                val size =
                    (bytes[offset + 4].toInt() and 0xff) or ((bytes[offset + 5].toInt() and 0xff) shl 8) or
                        ((bytes[offset + 6].toInt() and 0xff) shl 16) or ((bytes[offset + 7].toInt() and 0xff) shl 24)
                if (id == "data") {
                    val start = offset + 8
                    val count = minOf(size, bytes.size - start) / 2
                    return FloatArray(count) { i ->
                        val lo = bytes[start + 2 * i].toInt() and 0xff
                        val hi = bytes[start + 2 * i + 1].toInt()
                        ((hi shl 8) or lo).toShort() / 32768f
                    }
                }
                offset += 8 + size + (size and 1)
            }
            error("No audio data in the file")
        }
    }
}

/** The phone's cores and which of them are fast, from the kernel's per-core capacity (big.LITTLE aware). */
internal class Cores(
    val capacities: List<Int>,
) {
    val count = capacities.size
    private val top = capacities.maxOrNull() ?: 0

    /** Cores with at least half the capacity of the strongest one. */
    val fastMask = capacities.foldIndexed(0L) { i, mask, c -> if (top > 0 && c * 2 >= top) mask or (1L shl i) else mask }
    val fastCount =
        java.lang.Long
            .bitCount(fastMask)
            .coerceAtLeast(1)

    companion object {
        fun read(): Cores {
            val n = Runtime.getRuntime().availableProcessors()
            val caps =
                (0 until n).map { cpu ->
                    runCatching { File("/sys/devices/system/cpu/cpu$cpu/cpu_capacity").readText().trim().toInt() }.getOrDefault(1024)
                }
            return Cores(caps)
        }
    }
}

/** A growable float array the recording thread fills. */
private class FloatBuffer {
    private var data = FloatArray(VoiceEngine.SAMPLE_RATE * 10)
    private var size = 0

    @Synchronized fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    @Synchronized fun clear() {
        size = 0
    }

    @Synchronized fun toArray(): FloatArray = data.copyOf(size)
}

internal object Native {
    init {
        System.loadLibrary("hexvoice")
    }

    external fun open(
        path: String,
        threads: Int,
    ): Long

    external fun transcribe(
        session: Long,
        pcm: FloatArray,
        cpuMask: Long,
    ): String

    external fun close(session: Long)

    /** Moves the calling thread to the CPUs in [mask] and returns its previous mask (0 if that failed). */
    external fun setAffinity(mask: Long): Long
}
