package dev.opencode.hexcode.home

import android.os.Process
import android.os.SystemClock
import android.util.Log
import dev.opencode.voiceengine.VoiceEngine
import java.io.File
import kotlin.math.sqrt

/**
 * Debug builds (`--es HexCodeTakeAudio <WAV>`): every take plays [file] (16 kHz mono) as if it were the microphone, in
 * real time, 20 ms at a time from a thread at the microphone's priority, then silence until the take ends. Emulators
 * have no voice, and a long file shows a locked take being cut at its pauses exactly as speech would be.
 */
class FileMicrophone(
    private val file: File,
) : Microphone {
    private val audio: FloatArray by lazy { VoiceEngine.readWav(file) }

    /** The file's length. */
    val seconds: Double get() = audio.size.toDouble() / VoiceEngine.SAMPLE_RATE

    @Volatile
    private var playing: SampleBuffer? = null
    private var player: Thread? = null

    @Volatile
    override var rms: Float = 0f
        private set

    @Volatile
    override var hasAudio: Boolean = false
        private set

    override val take: RecordedAudio? get() = playing

    override fun prepare(): Boolean = true

    override fun release() = Unit

    override fun start(pressedAt: Long) {
        if (playing != null) return
        val buffer = SampleBuffer()
        playing = buffer
        rms = 0f
        hasAudio = false
        Log.i(TalkLog.TAG, "playing ${file.name} (%.1f s) as the microphone".format(seconds))
        player = Thread({ play(buffer) }, "hex-mic").apply { start() }
    }

    override fun end(): FloatArray? {
        val buffer = playing ?: return null
        playing = null
        player?.join(500)
        player = null
        rms = 0f
        hasAudio = false
        return buffer.toArray()
    }

    private fun play(buffer: SampleBuffer) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val started = SystemClock.uptimeMillis()
        var sent = 0
        while (playing === buffer) {
            // As many 20 ms buffers as real time has reached, as a microphone delivers them.
            val due = ((SystemClock.uptimeMillis() - started) * VoiceEngine.SAMPLE_RATE / 1000).toInt()
            while (sent + MicRecorder.CHUNK <= due) {
                val chunk = FloatArray(MicRecorder.CHUNK) { i -> audio.getOrElse(sent + i) { 0f } }
                var sum = 0.0
                for (v in chunk) sum += v * v
                buffer.add(chunk)
                sent += chunk.size
                rms = sqrt(sum / chunk.size).toFloat()
                hasAudio = true
            }
            Thread.sleep(CHUNK_MS)
        }
    }

    private companion object {
        const val CHUNK_MS = 20L
    }
}
