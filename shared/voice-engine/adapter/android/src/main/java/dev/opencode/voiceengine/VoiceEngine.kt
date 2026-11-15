package dev.opencode.voiceengine

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
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

        /**
         * Getting ready, in [stage]. Downloading: [done] of [total] bytes on the phone, at [bytesPerSecond] with about
         * [secondsLeft] to go once the speed is known, and while the connection is down [retryIn], the seconds to the
         * next attempt (0 while it's made). Loading and warming up: [fraction] 1.
         */
        data class Preparing(
            val fraction: Double,
            val detail: String,
            val done: Long = 0,
            val total: Long = 0,
            val secondsLeft: Long? = null,
            val bytesPerSecond: Long? = null,
            val retryIn: Int? = null,
            val stage: Stage = if (total > 0) Stage.Download else Stage.Load,
        ) : ModelState

        data object Ready : ModelState

        /**
         * The model didn't download or load. [done] of [total] bytes were on the phone when it stopped: less than
         * [total] when the download failed.
         */
        data class Failed(
            val message: String,
            val done: Long = 0,
            val total: Long = 0,
        ) : ModelState

        /**
         * The steps to ready (app-plan.md, Speech line and dial): download (once per install), load, then one short
         * take of silence so the first real take is as fast as the rest.
         */
        enum class Stage { Download, Load, WarmUp }
    }

    /** The pause detector's own state, for the speech model sheet: not on the phone, downloaded, loading or ready. */
    enum class PausesState { NotOnPhone, Downloaded, Loading, Ready }

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

    /** The chosen model's files are all on the phone. */
    val isInstalled: Boolean get() = store.isInstalled(active.value)

    /** A download waiting to retry after the connection dropped tries again now. */
    fun tryNow() = store.tryNow()

    private var engine: SpeechEngine? = null
    private val lock = Mutex()

    // Phones mix fast and slow cores, and the engines split each step evenly across their threads, so one thread on
    // a little core holds everything up. Runs use one thread per fast core and stay on those cores.
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

    private val pausesState =
        MutableStateFlow(if (store.isInstalled(SpeechModels.PAUSES)) PausesState.Downloaded else PausesState.NotOnPhone)

    /** The pause detector's state ([PausesState]), for the speech model sheet. */
    val pauses: StateFlow<PausesState> = pausesState.asStateFlow()

    /**
     * Whether locked takes can be cut at pauses: the pause detector ([SpeechModels.PAUSES]) is on the phone and loads.
     * It comes with the speech model, but never holds it back: without it a locked take is turned into text whole.
     */
    val pausesReady: Boolean get() = pausesState.value == PausesState.Ready

    /**
     * Downloads the chosen model if needed, loads it and warms it up, with the pause detector. Safe to call again. A
     * first download fetches the detector alongside the model, in the same progress; on a phone that already has the
     * model, the detector is fetched once the model is ready. Warming up turns half a second of silence into text,
     * so the first real take is as fast as the rest (app-plan.md, The speech model › Load); the model is ready after.
     */
    suspend fun prepare() {
        lock.withLock {
            if (engine != null) {
                withContext(Dispatchers.IO) { preparePauses() }
                return
            }
            val model = active.value
            withContext(Dispatchers.IO) {
                var done = model.bytes
                var all = model.bytes
                try {
                    if (!store.isInstalled(model)) {
                        val pauses = SpeechModels.PAUSES.takeUnless { store.isInstalled(it) }
                        all = model.bytes + (pauses?.bytes ?: 0)
                        done = store.bytesOnPhone(model)
                        var rate = DownloadRate()
                        var retryIn: Int? = null
                        var estimate: DownloadRate.Estimate? = null
                        val report = {
                            state.value =
                                ModelState.Preparing(
                                    fraction = done.toDouble() / all,
                                    detail = "Downloading · ${done / 1_000_000} of ${all / 1_000_000} MB",
                                    done = done,
                                    total = all,
                                    secondsLeft = estimate?.secondsLeft,
                                    bytesPerSecond = estimate?.bytesPerSecond,
                                    retryIn = retryIn,
                                    stage = ModelState.Stage.Download,
                                )
                        }
                        val progress = { bytes: Long ->
                            done = bytes
                            // Bytes again after a drop: the speed is measured afresh, so the wait doesn't count.
                            if (retryIn != null) rate = DownloadRate()
                            retryIn = null
                            estimate = rate.estimate(bytes, all)
                            report()
                        }
                        val retrying = { seconds: Int ->
                            retryIn = seconds
                            estimate = null
                            report()
                        }
                        report()
                        store.download(model, retrying) { bytes, _ -> progress(bytes) }
                        if (pauses != null) {
                            try {
                                store.download(pauses, retrying) { bytes, _ -> progress(model.bytes + bytes) }
                                pausesState.value = PausesState.Downloaded
                            } catch (e: Exception) {
                                Log.w(TAG, "the pause detector didn't download: ${e.message}")
                            }
                        }
                        done = all
                    }
                    state.value = ModelState.Preparing(1.0, "Loading the model", stage = ModelState.Stage.Load)
                    val started = SystemClock.elapsedRealtime()
                    val opened = openEngine(appContext, store, model, options, cores)
                    engine = opened
                    val loaded = SystemClock.elapsedRealtime()
                    state.value = ModelState.Preparing(1.0, "Warming up", stage = ModelState.Stage.WarmUp)
                    warmUp(opened)
                    Log.i(TAG, "${model.id} loaded in ${loaded - started} ms, warmed up in ${SystemClock.elapsedRealtime() - loaded} ms")
                    state.value = ModelState.Ready
                } catch (e: Exception) {
                    state.value = ModelState.Failed(e.message ?: "Could not get speech ready", done, all)
                } catch (e: Error) {
                    // An OutOfMemoryError or a missing native library while loading: speech isn't available, rather
                    // than "Getting speech ready" for good.
                    state.value = ModelState.Failed(e.message ?: "Could not get speech ready", done, all)
                }
                if (state.value == ModelState.Ready) preparePauses()
            }
        }
    }

    /** One short take of silence; a failure only means the first real take is a little slower. */
    private suspend fun warmUp(engine: SpeechEngine) {
        try {
            engine.transcribe(FloatArray(SAMPLE_RATE / 2))
        } catch (e: Exception) {
            Log.w(TAG, "warming the model up failed: ${e.message}")
        }
    }

    /** Fetches the pause detector if it's missing and checks that it loads; a failure only leaves takes uncut. */
    private fun preparePauses() {
        if (pausesReady) return
        val pauses = SpeechModels.PAUSES
        try {
            if (!store.isInstalled(pauses)) store.download(pauses) { _, _ -> }
            pausesState.value = PausesState.Loading
            val started = SystemClock.elapsedRealtime()
            PauseDetector(store.file(pauses, pauses.files.single().name).path).close()
            pausesState.value = PausesState.Ready
            Log.i(TAG, "pause detector loaded in ${SystemClock.elapsedRealtime() - started} ms")
        } catch (e: Exception) {
            settlePauses()
            Log.w(TAG, "the pause detector isn't ready, so locked takes are transcribed whole: ${e.message}", e)
        } catch (e: Error) {
            settlePauses()
            Log.w(TAG, "the pause detector didn't load, so locked takes are transcribed whole: ${e.message}", e)
        }
    }

    private fun settlePauses() {
        pausesState.value = if (store.isInstalled(SpeechModels.PAUSES)) PausesState.Downloaded else PausesState.NotOnPhone
    }

    /**
     * A new pause detector for one take (app-plan.md, Dictation › Locked takes), or null when it isn't ready. Each take
     * gets its own, made on the thread that feeds it.
     */
    fun pauseDetector(): PauseDetector? {
        if (!pausesReady) return null
        val pauses = SpeechModels.PAUSES
        return PauseDetector(store.file(pauses, pauses.files.single().name).path)
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
        if (live != null) return live.finish().trim().ifEmpty { throw NoSpeechException() }
        return transcribe(pcm)
    }

    /** Transcribes 16 kHz mono samples, for takes, checks and tests. */
    suspend fun transcribe(pcm: FloatArray): String {
        val e = engine ?: error("${active.value.title} is not ready.")
        return e.transcribe(pcm).trim().ifEmpty { throw NoSpeechException() }
    }

    /**
     * Ends the take now and returns its samples, so the next take can record while [transcribe] turns these into text.
     * Null when nothing is recording. Only for engines that transcribe after the take (a live stream is dropped).
     */
    fun endTake(): FloatArray? {
        stream?.cancel()
        stream = null
        return stop()
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

        private const val TAG = "VoiceEngine"

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

internal object CpuAffinity {
    init {
        System.loadLibrary("cpuaffinity")
    }

    /** Moves the calling thread to the CPUs in [mask] and returns its previous mask (0 if that failed). */
    external fun set(mask: Long): Long
}

/** A take with no speech in it: not a failure of the engine. */
class NoSpeechException : IllegalStateException("No speech was detected.")
