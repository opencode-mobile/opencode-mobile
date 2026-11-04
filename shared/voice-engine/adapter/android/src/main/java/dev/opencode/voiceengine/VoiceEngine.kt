package dev.opencode.voiceengine

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.sqrt

/**
 * The voice engine as Hex Code sees it: get the speech model ready, record one take, turn it into text.
 * Same engine and model as Hex desktop (transcribe.cpp, Parakeet V2); speech never leaves the phone.
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

    val modelName = "Parakeet V2"

    private val appContext = context.applicationContext
    private val modelFile = File(appContext.filesDir, "models/${BuildConfig.MODEL_FILE}")
    private val state = MutableStateFlow<ModelState>(ModelState.NotLoaded)
    val modelState: StateFlow<ModelState> = state.asStateFlow()

    private var session = 0L

    // Phones mix fast and slow cores, and ggml splits each step evenly across its threads, so one thread on a
    // little core holds everything up. Runs use one thread per fast core and stay on those cores.
    private val cores = Cores.read()
    private var recorder: AudioRecord? = null
    private var reader: Thread? = null
    private val samples = FloatBuffer()

    /** Loudness of the current take, 0 to 1, for a meter. */
    @Volatile var level: Float = 0f
        private set

    val isRecording: Boolean get() = recorder != null

    /** Downloads (once, about 730 MB) and loads the speech model. Safe to call again. */
    suspend fun prepare() {
        if (state.value == ModelState.Ready) return
        withContext(Dispatchers.IO) {
            try {
                if (!modelFile.isFile || modelFile.length() != BuildConfig.MODEL_BYTES) download()
                state.value = ModelState.Preparing(1.0, "Loading the model")
                session = Native.open(modelFile.absolutePath, cores.fastCount)
                state.value = ModelState.Ready
            } catch (e: Exception) {
                state.value = ModelState.Failed(e.message ?: "Could not get speech ready")
            }
        }
    }

    private fun download() {
        modelFile.parentFile?.mkdirs()
        val part = File(modelFile.path + ".part")
        val connection = URL(BuildConfig.MODEL_URL).openConnection() as HttpURLConnection
        val digest = MessageDigest.getInstance("SHA-256")
        connection.inputStream.use { input ->
            part.outputStream().use { output ->
                val buffer = ByteArray(1 shl 16)
                var done = 0L
                var lastReport = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                    done += n
                    if (done - lastReport > 4_000_000) {
                        lastReport = done
                        val mb = done / 1_000_000
                        state.value = ModelState.Preparing(done.toDouble() / BuildConfig.MODEL_BYTES, "Downloading · $mb MB")
                    }
                }
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        check(sha == BuildConfig.MODEL_SHA256) { "The downloaded model is damaged. Try again." }
        check(part.renameTo(modelFile)) { "Could not save the model" }
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
        record.startRecording()
        recorder = record
        reader =
            Thread {
                val chunk = ShortArray(SAMPLE_RATE / 20)
                while (recorder === record) {
                    val n = record.read(chunk, 0, chunk.size)
                    if (n <= 0) continue
                    var sum = 0.0
                    for (i in 0 until n) {
                        val v = chunk[i] / 32768f
                        samples.add(v)
                        sum += v * v
                    }
                    level = (sqrt(sum / n) * 6).toFloat().coerceIn(0f, 1f)
                }
            }.apply { start() }
    }

    /** Ends the take and returns its text. Hex transcribes after the take, not while you talk. */
    suspend fun stopAndTranscribe(): String {
        val pcm = stop() ?: error("Nothing is being recorded.")
        return transcribe(pcm)
    }

    /** Transcribes 16 kHz mono samples, for takes, checks and tests. */
    suspend fun transcribe(pcm: FloatArray): String =
        withContext(Dispatchers.Default) {
            check(session != 0L) { "Parakeet V2 is not ready." }
            Native.transcribe(session, pcm, cores.fastMask).trim().ifEmpty { error("No speech was detected.") }
        }

    /** Drops the take without transcribing it. */
    fun cancelRecording() {
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

    /**
     * Times transcription of [pcm] across thread counts and core choices, opening one session at a time.
     * Each line: setup, first run, then the best of two warm runs.
     */
    suspend fun benchmark(pcm: FloatArray): String =
        withContext(Dispatchers.Default) {
            val all = (1L shl cores.count) - 1
            val configs =
                buildList {
                    add(Triple("4 threads, any core (current)", 4, 0L))
                    add(Triple("${cores.count} threads, any core", cores.count, 0L))
                    add(Triple("${cores.fastCount} threads, any core", cores.fastCount, 0L))
                    add(Triple("${cores.fastCount} threads, fast cores", cores.fastCount, cores.fastMask))
                    if (cores.fastCount > 1) add(Triple("${cores.fastCount - 1} threads, fast cores", cores.fastCount - 1, cores.fastMask))
                    add(Triple("${cores.count} threads, all cores", cores.count, all))
                }.distinctBy { it.second to it.third }
            val lines = mutableListOf("cores: capacities ${cores.capacities}, fast ${cores.fastCount}")
            for ((label, threads, mask) in configs) {
                val s = Native.open(modelFile.absolutePath, threads)
                try {
                    val first = timed { Native.transcribe(s, pcm, mask) }
                    val warm = minOf(timed { Native.transcribe(s, pcm, mask) }, timed { Native.transcribe(s, pcm, mask) })
                    lines += "%-32s first %.2f s · warm %.2f s".format(label, first, warm)
                } finally {
                    Native.close(s)
                }
            }
            lines.joinToString("\n")
        }

    private inline fun timed(block: () -> Unit): Double {
        val t0 = System.nanoTime()
        block()
        return (System.nanoTime() - t0) / 1e9
    }

    fun close() {
        cancelRecording()
        if (session != 0L) Native.close(session)
        session = 0L
        state.value = ModelState.NotLoaded
    }

    companion object {
        const val SAMPLE_RATE = 16_000

        /** Reads a 16-bit mono WAV into samples (debug samples and tests). */
        fun readWav(file: File): FloatArray {
            val bytes = file.readBytes()
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
            error("No audio data in ${file.name}")
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
}
