package dev.opencode.voiceengine

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * A stretch of speech the detector closed: the take's samples [start] to [end], closed once [heard] samples of the take
 * had been fed to it. The pause after the speech runs from [end] to at least [heard].
 */
data class SpeechSpan(
    val start: Long,
    val end: Long,
    val heard: Long,
)

/**
 * Finds the speaker's pauses in one take, fed as it records (app-plan.md, Dictation › Locked takes): Silero VAD v5 on
 * sherpa-onnx, with spike S3's settings. A stretch of speech closes after a 1.0 s pause; one longer than 12 s closes at
 * the next short dip (sherpa-onnx's `max_speech_duration`: a 0.1 s dip below 0.9). Not thread safe: feed it from one
 * thread, and [close] it there.
 */
class PauseDetector internal constructor(
    model: String,
) : AutoCloseable {
    private val vad =
        Vad(
            config =
                VadModelConfig(
                    sileroVadModelConfig =
                        SileroVadModelConfig(
                            model = model,
                            threshold = THRESHOLD,
                            minSilenceDuration = MIN_SILENCE_S,
                            minSpeechDuration = MIN_SPEECH_S,
                            windowSize = WINDOW,
                            maxSpeechDuration = MAX_SPEECH_S,
                        ),
                    sampleRate = VoiceEngine.SAMPLE_RATE,
                    numThreads = 1,
                    provider = "cpu",
                ),
        )

    /** Samples fed so far. */
    var heard = 0L
        private set

    /** Feeds the next samples of the take; returns the stretches of speech that closed with them. */
    fun accept(samples: FloatArray): List<SpeechSpan> {
        if (samples.isEmpty()) return emptyList()
        vad.acceptWaveform(samples)
        heard += samples.size
        return drain()
    }

    /**
     * The take ended: closes a stretch of speech that was still going, or whose pause wasn't long enough yet. Returns
     * it, so an empty list means no speech since the last stretch that closed.
     */
    fun finish(): List<SpeechSpan> {
        vad.flush()
        return drain()
    }

    private fun drain(): List<SpeechSpan> {
        if (vad.empty()) return emptyList()
        val spans = mutableListOf<SpeechSpan>()
        while (!vad.empty()) {
            val segment = vad.front()
            spans += SpeechSpan(segment.start, segment.start + segment.samples.size, heard)
            vad.pop()
        }
        return spans
    }

    override fun close() = vad.release()

    companion object {
        /** Speech probability above which a window counts as speech. */
        const val THRESHOLD = 0.5f

        /** The pause that ends a part. */
        const val MIN_SILENCE_S = 1.0f

        /** Shorter sounds don't count as speech. */
        const val MIN_SPEECH_S = 0.25f

        /** A part longer than this ends at the next short dip. */
        const val MAX_SPEECH_S = 12f

        /** Silero v5 reads 512 samples (32 ms) at a time at 16 kHz. */
        const val WINDOW = 512
    }
}
