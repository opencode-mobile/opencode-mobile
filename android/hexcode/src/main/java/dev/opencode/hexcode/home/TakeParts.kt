package dev.opencode.hexcode.home

import dev.opencode.voiceengine.PauseDetector
import dev.opencode.voiceengine.SpeechSpan
import dev.opencode.voiceengine.VoiceEngine

/**
 * One part of a locked take (app-plan.md, Dictation › Locked takes): the take's samples from the last cut to the next,
 * to be turned into text while recording goes on. Parts touch, so no audio is dropped between them.
 */
class TakePart(
    /** 1, 2, 3… in the order they were cut. */
    val number: Int,
    /** Where the part starts in the take, in samples. */
    val start: Int,
    val samples: FloatArray,
    /** The audio after the last cut, taken at release. */
    val last: Boolean,
    /** When the part's speech ended (uptime ms), as best known: for the log of how soon its text came. */
    val speechEndedAt: Long,
    /** When the cut was found (uptime ms). */
    val cutAt: Long,
) {
    val seconds: Double get() = samples.size.toDouble() / VoiceEngine.SAMPLE_RATE
}

/** What finds the pauses in a take ([PauseDetector] in the app), fed as it records. */
interface Pauses : AutoCloseable {
    /** Feeds the next samples; returns the stretches of speech that closed with them. */
    fun accept(samples: FloatArray): List<SpeechSpan>

    /** The take ended: returns a stretch of speech still open, so an empty list means none since the last one closed. */
    fun finish(): List<SpeechSpan>
}

/** [PauseDetector] as [Pauses]. */
fun PauseDetector.asPauses(): Pauses =
    object : Pauses {
        override fun accept(samples: FloatArray) = this@asPauses.accept(samples)

        override fun finish() = this@asPauses.finish()

        override fun close() = this@asPauses.close()
    }

/**
 * Where a locked take is cut (app-plan.md, Dictation › Locked takes; spike S3, §5): in the middle of each pause the
 * detector finds, between the end of the speech and the moment the pause was known, with no padding. A part longer than
 * 12 s is closed by the detector itself at the next short dip ([PauseDetector.MAX_SPEECH_S]), so it is cut the same way.
 * After release only the audio since the last cut is left, and only if the detector heard speech in it.
 */
class PartCutter {
    /** Where the part being recorded starts: the last cut, or the take's start. */
    var lastCut: Int = 0
        private set

    /** A stretch of speech closed: the part from the last cut to the middle of the pause after it, or null if empty. */
    fun cut(span: SpeechSpan): IntRange? {
        val heard = span.heard.toInt()
        if (heard <= lastCut) return null
        val at = ((span.end + span.heard) / 2).toInt().coerceIn(lastCut, heard)
        if (at <= lastCut) return null
        return (lastCut until at).also { lastCut = at }
    }

    /** The take ended after [total] samples: the part since the last cut, or null when no [speech] was heard in it. */
    fun rest(
        total: Int,
        speech: Boolean,
    ): IntRange? = if (speech && total > lastCut) lastCut until total else null
}

/**
 * Cuts one locked take into parts as it records: [step] feeds [pauses] what [audio] has gained since the last step and
 * hands each part that closes to [onPart], in order; [finish], after the take ends, hands over the audio since the last
 * cut if there was speech in it. Call both from one thread, never the microphone's.
 */
class TakeSplitter(
    private val audio: RecordedAudio,
    private val pauses: Pauses,
    private val onPart: (TakePart) -> Unit,
    private val clock: () -> Long,
) {
    private val cutter = PartCutter()
    private var fed = 0
    private var parts = 0

    /** Feeds the audio that has arrived since the last step. */
    fun step() {
        val size = audio.size
        if (size <= fed) return
        val spans = pauses.accept(audio.copy(fed, size))
        fed = size
        for (span in spans) {
            val range = cutter.cut(span) ?: continue
            val now = clock()
            // The detector has heard up to `fed`, which is now: the speech ended that much audio ago.
            val ended = now - (fed - span.end) * 1000 / VoiceEngine.SAMPLE_RATE
            send(range, last = false, speechEndedAt = ended, cutAt = now)
        }
    }

    /** The take ended, all its audio in [audio]: the last step, then the part after the last cut, if it has speech. */
    fun finish() {
        step()
        val speech = pauses.finish().isNotEmpty()
        val now = clock()
        cutter.rest(fed, speech)?.let { send(it, last = true, speechEndedAt = now, cutAt = now) }
    }

    private fun send(
        range: IntRange,
        last: Boolean,
        speechEndedAt: Long,
        cutAt: Long,
    ) {
        parts += 1
        onPart(TakePart(parts, range.first, audio.copy(range.first, range.last + 1), last, speechEndedAt, cutAt))
    }
}

/** A locked take's text: its parts' texts, in order, joined with one space, as the engine wrote them. */
fun joinParts(texts: List<String>): String = texts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
