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
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
                session = Native.open(modelFile.absolutePath, threads)
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
            Native.transcribe(session, pcm).trim().ifEmpty { error("No speech was detected.") }
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
    ): String

    external fun close(session: Long)
}
