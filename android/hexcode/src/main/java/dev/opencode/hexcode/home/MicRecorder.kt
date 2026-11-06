package dev.opencode.hexcode.home

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import dev.opencode.voiceengine.VoiceEngine
import kotlin.math.sqrt

/**
 * The talk button's microphone (app-plan.md, Dictation › Microphone start): 16 kHz mono, as the speech models take it.
 * While home is on screen the recorder is made ahead ([prepare]), with its buffer, but not started, so the microphone and
 * the system's recording indicator stay off; a press only has to start it. [release] lets it go when home leaves the
 * screen. Call everything but the getters from one thread (dictation's audio thread).
 */
class MicRecorder(
    /** Whether the app holds RECORD_AUDIO; without it nothing is made ahead. */
    private val permitted: () -> Boolean,
) {
    /** Made ahead, not started. */
    private var ready: AudioRecord? = null

    @Volatile
    private var running: AudioRecord? = null
    private var reader: Thread? = null
    private val samples = SampleBuffer()

    /** The loudness of the latest buffer, 0 to 1 (full scale RMS); 0 between takes. */
    @Volatile
    var rms: Float = 0f
        private set

    /** Whether the take has had its first buffer of audio yet. */
    @Volatile
    var hasAudio: Boolean = false
        private set

    /** Makes the recorder ahead of a press, if it isn't made yet and no take is running. Returns whether one is ready. */
    fun prepare(): Boolean {
        if (running != null) return false
        if (ready == null && permitted()) ready = create()
        return ready != null
    }

    /** Lets go of a recorder made ahead. A take that's running goes on. */
    fun release() {
        ready?.release()
        ready = null
    }

    /**
     * Starts a take: the recorder made ahead if there is one, else a new one. [pressedAt] (uptime) is the touch, for the
     * log of how soon audio came. Throws when the microphone can't start. The caller must hold RECORD_AUDIO.
     */
    fun start(pressedAt: Long) {
        if (running != null) return
        val madeAhead = ready != null
        val record = ready ?: create() ?: error("The microphone couldn't be opened.")
        ready = null
        samples.clear()
        rms = 0f
        hasAudio = false
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            error("The microphone didn't start.")
        }
        val startedAt = SystemClock.uptimeMillis()
        running = record
        reader =
            Thread({ read(record, pressedAt, startedAt, madeAhead) }, "hex-mic").apply { start() }
    }

    /** Ends the take and returns its samples; null when nothing was recording. */
    fun end(): FloatArray? {
        val record = running ?: return null
        running = null
        reader?.join(500)
        reader = null
        record.stop()
        record.release()
        rms = 0f
        hasAudio = false
        return samples.toArray()
    }

    private fun read(
        record: AudioRecord,
        pressedAt: Long,
        startedAt: Long,
        madeAhead: Boolean,
    ) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val chunk = ShortArray(CHUNK)
        while (running === record) {
            val n = record.read(chunk, 0, chunk.size)
            if (n <= 0) {
                if (n < 0) Thread.sleep(5)
                continue
            }
            if (!hasAudio) {
                val now = SystemClock.uptimeMillis()
                Log.i(
                    TalkLog.TAG,
                    "first audio ${now - pressedAt} ms after the press (microphone started at ${startedAt - pressedAt} ms, " +
                        if (madeAhead) "recorder made ahead)" else "recorder made at the press)",
                )
            }
            var sum = 0.0
            for (i in 0 until n) {
                val v = chunk[i] / 32_768f
                sum += v * v
            }
            samples.add(chunk, n)
            rms = sqrt(sum / n).toFloat()
            hasAudio = true
        }
    }

    @SuppressLint("MissingPermission")
    private fun create(): AudioRecord? {
        val rate = VoiceEngine.SAMPLE_RATE
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record =
            try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    // 200 ms, so a slow read never loses audio.
                    maxOf(minBuffer, rate / 5 * 2),
                )
            } catch (error: Exception) {
                Log.w(TalkLog.TAG, "couldn't make the recorder", error)
                return null
            }
        if (record.state == AudioRecord.STATE_INITIALIZED) return record
        record.release()
        return null
    }

    companion object {
        /** Each read is 20 ms of audio: the first comes soon after the start, and the meter moves smoothly. */
        const val CHUNK = VoiceEngine.SAMPLE_RATE / 50
    }
}

/** A growing array of samples, -1 to 1, that the reading thread fills. */
private class SampleBuffer {
    private var data = FloatArray(VoiceEngine.SAMPLE_RATE * 10)
    private var size = 0

    @Synchronized
    fun add(
        chunk: ShortArray,
        n: Int,
    ) {
        if (size + n > data.size) data = data.copyOf(maxOf(data.size * 2, size + n))
        for (i in 0 until n) data[size + i] = chunk[i] / 32_768f
        size += n
    }

    @Synchronized
    fun clear() {
        size = 0
    }

    @Synchronized
    fun toArray(): FloatArray = data.copyOf(size)
}
