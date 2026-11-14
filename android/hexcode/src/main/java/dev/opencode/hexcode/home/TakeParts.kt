package dev.opencode.hexcode.home

import dev.opencode.voiceengine.Detected
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
fun interface Pauses : AutoCloseable {
    /** Feeds the next samples: the stretches of speech that closed, and each whole window's speech probability. */
    fun accept(samples: FloatArray): Detected

    override fun close() = Unit
}

/** [PauseDetector] as [Pauses]. */
fun PauseDetector.asPauses(): Pauses =
    object : Pauses {
        override fun accept(samples: FloatArray) = this@asPauses.accept(samples)

        override fun close() = this@asPauses.close()
    }

/**
 * Where a take is cut (app-plan.md, Dictation › Locked takes; spike S3, §5), from the pause detector's stretches of
 * speech and its speech probability for each [PauseDetector.WINDOW] of the take:
 * - in the middle of each pause the detector finds, between the end of the speech and the moment the pause was known,
 *   with no padding, so parts touch;
 * - a part longer than 12 s is closed by the detector itself at the next short dip; if none comes by 15 s ([HARD_CAP]),
 *   at the quietest moment between 12 and 15 s;
 * - a cut is only made when speech was heard since the last one, meaning a window at [PauseDetector.THRESHOLD] or
 *   above, not the detector's own start rule, so quiet words are never thrown away.
 * After release, only the audio since the last cut is left, and only if speech was heard in it and the take was cut
 * at all: a take with no cut is turned into text whole.
 */
class PartCutter {
    /** Where the part being recorded starts: the last cut, or the take's start. */
    var lastCut: Int = 0
        private set

    /** Parts cut so far. */
    var cuts: Int = 0
        private set

    private var probabilities = FloatArray(1_024)
    private var windows = 0

    /** The next windows' speech probabilities, in order from the take's start. */
    fun listen(next: FloatArray) {
        if (windows + next.size > probabilities.size) {
            probabilities = probabilities.copyOf(maxOf(probabilities.size * 2, windows + next.size))
        }
        next.copyInto(probabilities, windows)
        windows += next.size
    }

    /** A stretch of speech closed: the part from the last cut to the middle of the pause after it, or null. */
    fun cut(span: SpeechSpan): IntRange? {
        val heard = span.heard.toInt()
        if (heard <= lastCut) return null
        return cutAt(((span.end + span.heard) / 2).toInt().coerceIn(lastCut, heard))
    }

    /**
     * After [heard] samples: a part that reached [HARD_CAP] with no pause and no dip ends at the moment [quietest]
     * picks between [SOFT_CAP] and [HARD_CAP] after the last cut. Null while the part is shorter, or has no speech.
     */
    fun capped(
        heard: Int,
        quietest: (IntRange) -> Int,
    ): IntRange? {
        if (heard - lastCut < HARD_CAP || !heard(lastCut until lastCut + HARD_CAP)) return null
        val at = quietest(lastCut + SOFT_CAP until lastCut + HARD_CAP).coerceIn(lastCut + SOFT_CAP, lastCut + HARD_CAP)
        return cutAt(at)
    }

    /** The take ended after [total] samples: the part since the last cut, or null (no cut at all, or no speech in it). */
    fun rest(total: Int): IntRange? {
        if (cuts == 0 || total <= lastCut || !heard(lastCut until total)) return null
        return lastCut until total
    }

    /** Whether any window overlapping [range] is speech. */
    fun heard(range: IntRange): Boolean {
        val first = range.first / PauseDetector.WINDOW
        val last = minOf(range.last / PauseDetector.WINDOW, windows - 1)
        return (first..last).any { probabilities[it] >= PauseDetector.THRESHOLD }
    }

    private fun cutAt(at: Int): IntRange? {
        if (at <= lastCut) return null
        val part = lastCut until at
        if (!heard(part)) return null
        lastCut = at
        cuts += 1
        return part
    }

    companion object {
        /** A part longer than this ends at the next dip, which the detector finds. */
        const val SOFT_CAP = 12 * VoiceEngine.SAMPLE_RATE

        /** With no dip by this length, the part ends at its quietest moment after [SOFT_CAP]. */
        const val HARD_CAP = 15 * VoiceEngine.SAMPLE_RATE
    }
}

/** Where in [samples] the quietest [PauseDetector.WINDOW] is (lowest RMS), as the offset of its middle. */
fun quietest(samples: FloatArray): Int {
    val window = PauseDetector.WINDOW
    var best = 0
    var lowest = Double.MAX_VALUE
    var start = 0
    while (start + window <= samples.size) {
        var sum = 0.0
        for (i in start until start + window) sum += samples[i] * samples[i]
        if (sum < lowest) {
            lowest = sum
            best = start
        }
        start += window
    }
    return best + minOf(window, samples.size) / 2
}

/**
 * Cuts one take into parts as it records: [step] feeds [pauses] what [audio] has gained since the last step and hands
 * each part that closes to [onPart], in order; [finish], after the take ends, hands over the audio since the last cut
 * if the take was cut and there was speech in it. Call both from one thread, never the microphone's.
 */
class TakeSplitter(
    private val audio: RecordedAudio,
    private val pauses: Pauses,
    private val onPart: (TakePart) -> Unit,
    private val clock: () -> Long,
) {
    private val cutter = PartCutter()
    private var fed = 0

    /** Feeds the audio that has arrived since the last step. */
    fun step() {
        val size = audio.size
        if (size <= fed) return
        val detected = pauses.accept(audio.copy(fed, size))
        fed = size
        cutter.listen(detected.probabilities)
        for (span in detected.spans) {
            val range = cutter.cut(span) ?: continue
            val now = clock()
            // The detector has heard up to `fed`, which is now: the speech ended that much audio ago.
            send(range, last = false, speechEndedAt = now - (fed - span.end) * 1000 / VoiceEngine.SAMPLE_RATE, cutAt = now)
        }
        while (true) {
            val range = cutter.capped(fed) { within -> within.first + quietest(audio.copy(within.first, within.last + 1)) } ?: break
            val now = clock()
            send(range, last = false, speechEndedAt = now, cutAt = now)
        }
    }

    /** The take ended, all its audio in [audio]: the last step, then the part after the last cut, if any. */
    fun finish() {
        step()
        val now = clock()
        cutter.rest(fed)?.let { send(it, last = true, speechEndedAt = now, cutAt = now) }
    }

    private fun send(
        range: IntRange,
        last: Boolean,
        speechEndedAt: Long,
        cutAt: Long,
    ) {
        onPart(TakePart(cutter.cuts + if (last) 1 else 0, range.first, audio.copy(range.first, range.last + 1), last, speechEndedAt, cutAt))
    }
}

/** A locked take's text: its parts' texts, in order, joined with one space, as the engine wrote them. */
fun joinParts(texts: List<String>): String = texts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
