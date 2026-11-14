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

/** Where a take's audio comes from: the phone's microphone ([MicRecorder]), or in debug builds a file ([FileMicrophone]). */
interface Microphone {
    /** The loudness of the latest buffer, 0 to 1 (full scale RMS); 0 between takes. */
    val rms: Float

    /** Whether the take has had its first buffer of audio yet. */
    val hasAudio: Boolean

    /** The take that's recording, as its samples arrive; null between takes. */
    val take: RecordedAudio?

    /** Gets ready ahead of a press. Returns whether it is. */
    fun prepare(): Boolean

    /** Lets go of what [prepare] got ready. */
    fun release()

    /** Starts a take; [pressedAt] (uptime) is the touch. Throws when it can't start. */
    fun start(pressedAt: Long)

    /** Ends the take and returns its samples; null when nothing was recording. */
    fun end(): FloatArray?
}

/**
 * A take's samples, -1 to 1, as they arrive: [size] grows while it records, and [copy] reads any stretch of it from
 * another thread meanwhile, without holding the recording up. Each take has its own.
 */
interface RecordedAudio {
    val size: Int

    /** Samples [from] until [to], clamped to what has arrived. */
    fun copy(
        from: Int,
        to: Int,
    ): FloatArray
}

/**
 * The talk button's microphone (app-plan.md, Dictation › Microphone start): 16 kHz mono, as the speech models take it.
 * While home is on screen the recorder is made ahead ([prepare]), with its buffer, but not started, so the microphone and
 * the system's recording indicator stay off; a press only has to start it. [release] lets it go when home leaves the
 * screen. Call everything but the getters from one thread (dictation's audio thread).
 */
class MicRecorder(
    /** Whether the app holds RECORD_AUDIO; without it nothing is made ahead. */
    private val permitted: () -> Boolean,
) : Microphone {
    /** Made ahead, not started. */
    private var ready: AudioRecord? = null

    @Volatile
    private var running: AudioRecord? = null
    private var reader: Thread? = null

    /** A new buffer for every take, so a take still being cut at pauses never sees the next one's audio. */
    @Volatile
    private var samples = SampleBuffer()

    @Volatile
    override var rms: Float = 0f
        private set

    @Volatile
    override var hasAudio: Boolean = false
        private set

    override val take: RecordedAudio? get() = if (running != null) samples else null

    /** Makes the recorder ahead of a press, if it isn't made yet and no take is running. Returns whether one is ready. */
    override fun prepare(): Boolean {
        if (running != null) return false
        if (ready == null && permitted()) ready = create()
        return ready != null
    }

    /** Lets go of a recorder made ahead. A take that's running goes on. */
    override fun release() {
        ready?.release()
        ready = null
    }

    /**
     * Starts a take: the recorder made ahead if there is one, else a new one. [pressedAt] (uptime) is the touch, for the
     * log of how soon audio came. Throws when the microphone can't start. The caller must hold RECORD_AUDIO.
     */
    override fun start(pressedAt: Long) {
        if (running != null) return
        val madeAhead = ready != null
        val record = ready ?: create() ?: error("The microphone couldn't be opened.")
        ready = null
        val buffer = SampleBuffer()
        samples = buffer
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
            Thread({ read(record, buffer, pressedAt, startedAt, madeAhead) }, "hex-mic").apply { start() }
    }

    /** Ends the take and returns its samples; null when nothing was recording. */
    override fun end(): FloatArray? {
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
        samples: SampleBuffer,
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

/**
 * A growing array of samples, -1 to 1, that the reading thread fills. The lock is only held to append a buffer or copy a
 * stretch out, so the reading thread never waits long for it.
 */
internal class SampleBuffer : RecordedAudio {
    private var data = FloatArray(VoiceEngine.SAMPLE_RATE * 10)
    private var count = 0

    @Synchronized
    fun add(
        chunk: ShortArray,
        n: Int,
    ) {
        grow(n)
        for (i in 0 until n) data[count + i] = chunk[i] / 32_768f
        count += n
    }

    @Synchronized
    fun add(samples: FloatArray) {
        grow(samples.size)
        samples.copyInto(data, count)
        count += samples.size
    }

    private fun grow(n: Int) {
        if (count + n > data.size) data = data.copyOf(maxOf(data.size * 2, count + n))
    }

    override val size: Int
        @Synchronized get() = count

    @Synchronized
    override fun copy(
        from: Int,
        to: Int,
    ): FloatArray {
        val end = to.coerceAtMost(count)
        return data.copyOfRange(from.coerceIn(0, end), end)
    }

    @Synchronized
    fun toArray(): FloatArray = data.copyOf(count)
}
