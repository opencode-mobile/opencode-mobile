package dev.opencode.hexcode.home

import dev.opencode.voiceengine.Detected
import dev.opencode.voiceengine.PauseDetector
import dev.opencode.voiceengine.SpeechSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** app-plan.md, Dictation › Locked takes, with synthetic spans in place of the pause detector (spike S3, §5). */
class TakePartsTest {
    private val second = 16_000
    private val window = PauseDetector.WINDOW

    /** Speech from [start] to [end] seconds, closed when the detector had heard [heard] seconds. */
    private fun span(
        start: Double,
        end: Double,
        heard: Double,
    ) = SpeechSpan((start * second).toLong(), (end * second).toLong(), (heard * second).toLong())

    /** Probabilities for [seconds] of windows: speech (0.9) where [speech] says so, else 0.1. */
    private fun probabilities(
        seconds: Double,
        speech: (Double) -> Boolean,
    ) = FloatArray((seconds * second).toInt() / window) { if (speech(it * window.toDouble() / second)) 0.9f else 0.1f }

    /** A cutter that has heard [seconds] of windows, speech where [speech] says so. */
    private fun cutter(
        seconds: Double,
        speech: (Double) -> Boolean = { true },
    ) = PartCutter().apply { listen(probabilities(seconds, speech)) }

    /**
     * A detector that closes the spans it's given once it has been fed up to their [SpeechSpan.heard], and calls each
     * window speech where [speech] says so.
     */
    private class ScriptedPauses(
        private val spans: List<SpeechSpan>,
        private val speech: (Double) -> Boolean = { true },
    ) : Pauses {
        var heard = 0L
        private var closed = 0
        private var windows = 0

        override fun accept(samples: FloatArray): Detected {
            heard += samples.size
            val ready = spans.drop(closed).takeWhile { it.heard <= heard }
            closed += ready.size
            val whole = (heard / PauseDetector.WINDOW).toInt()
            val probabilities =
                FloatArray(whole - windows) { i ->
                    if (speech((windows + i) * PauseDetector.WINDOW / 16_000.0)) 0.9f else 0.1f
                }
            windows = whole
            return Detected(ready, probabilities)
        }
    }

    /** Records [seconds] of audio whose samples are their own index, so a part shows where it came from. */
    private fun audio(seconds: Double) = SampleBuffer().apply { add(FloatArray((seconds * 16_000).toInt()) { it.toFloat() }) }

    @Test
    fun aPartIsCutInTheMiddleOfThePause() {
        val cutter = cutter(6.0)
        // Speech ends at 4.0 s; the 1.0 s pause is known at 5.1 s: the cut is at 4.55 s.
        val part = cutter.cut(span(0.3, 4.0, 5.1))
        assertEquals(0 until (4.55 * second).toInt(), part)
        assertEquals((4.55 * second).toInt(), cutter.lastCut)
    }

    @Test
    fun partsTouchSoNoAudioIsDropped() {
        val cutter = cutter(11.0)
        val first = cutter.cut(span(0.0, 4.0, 5.1))!!
        val second = cutter.cut(span(5.6, 9.0, 10.1))!!
        assertEquals(first.last + 1, second.first)
        val rest = cutter.rest(total = 11 * this.second)!!
        assertEquals(second.last + 1, rest.first)
        assertEquals(11 * this.second - 1, rest.last)
    }

    @Test
    fun aPartLongerThan12sIsCutWhereTheDetectorClosedIt() {
        // sherpa-onnx closes a stretch of speech past maxSpeechDuration at the next short dip, with hardly any pause:
        // the cut is there, and the next part starts right after it.
        assertEquals(12f, PauseDetector.MAX_SPEECH_S)
        val cutter = cutter(22.0)
        val forced = cutter.cut(span(0.2, 12.4, 12.45))!!
        assertTrue(forced.last + 1 in (12.4 * second).toInt()..(12.45 * second).toInt())
        // A span that started before that cut (the detector's lead-in) still makes the next part start at the cut.
        val next = cutter.cut(span(12.3, 20.0, 21.1))!!
        assertEquals(forced.last + 1, next.first)
    }

    @Test
    fun withNoDipBy15sAPartEndsAtTheQuietestMomentAfter12s() {
        val cutter = cutter(16.0)
        assertNull(cutter.capped(14 * second) { error("not yet") })
        var asked: IntRange? = null
        val part =
            cutter.capped(15 * second) { within ->
                asked = within
                (13.5 * second).toInt()
            }
        assertEquals(12 * second until 15 * second, asked)
        assertEquals(0 until (13.5 * second).toInt(), part)
        // The next part counts from that cut.
        assertNull(cutter.capped(16 * second) { error("too soon") })
    }

    @Test
    fun theQuietestMomentIsTheMiddleOfTheQuietestWindow() {
        val samples = FloatArray(window * 10) { 0.5f }
        for (i in window * 6 until window * 7) samples[i] = 0.01f
        assertEquals(window * 6 + window / 2, quietest(samples))
    }

    @Test
    fun theSplitterCutsRunOnSpeechAt15s() {
        // 20 s of speech with no pause, a quiet moment of 0.1 s at 13 s.
        val quiet = 13 * second until 13 * second + second / 10
        val take = SampleBuffer().apply { add(FloatArray(20 * second) { if (it in quiet) 0f else 0.3f }) }
        val parts = mutableListOf<TakePart>()
        val splitter = TakeSplitter(take, ScriptedPauses(emptyList()), { parts += it }, clock = { 0 })
        splitter.step()
        assertEquals(1, parts.size)
        assertTrue(parts.single().samples.size in quiet)
        splitter.finish()
        assertEquals(listOf(1, 2), parts.map { it.number })
        assertEquals(20 * second, parts.last().start + parts.last().samples.size)
    }

    @Test
    fun theDetectorKeepsTheSpikesSettings() {
        assertEquals(1.0f, PauseDetector.MIN_SILENCE_S)
        assertEquals(0.5f, PauseDetector.THRESHOLD)
        assertEquals(0.25f, PauseDetector.MIN_SPEECH_S)
    }

    @Test
    fun aCutNeverGoesBackOrPastWhatWasHeard() {
        val cutter = cutter(6.0)
        cutter.cut(span(0.0, 4.0, 5.0))
        // A span closing behind the last cut gives no part.
        assertNull(cutter.cut(span(0.0, 3.0, 4.0)))
    }

    @Test
    fun noCutIsMadeWithoutSpeechHeard() {
        // A span over windows that are all under 0.5: no part, and the audio stays with the next one.
        val cutter = cutter(8.0) { false }
        assertNull(cutter.cut(span(1.0, 2.0, 3.0)))
        assertNull(cutter.capped(16 * second) { error("no speech to cut") })
        assertEquals(0, cutter.lastCut)
    }

    @Test
    fun afterReleaseOnlyTheAudioSinceTheLastCutIsLeft() {
        val cutter = cutter(8.0)
        cutter.cut(span(0.0, 4.0, 5.0))
        assertEquals((4.5 * second).toInt() until 8 * second, cutter.rest(8 * second))
    }

    @Test
    fun releaseWithNoSpeechSinceTheLastCutLeavesNothing() {
        val cutter = cutter(8.0) { it < 4.0 }
        cutter.cut(span(0.0, 4.0, 5.0))
        assertNull(cutter.rest(8 * second))
    }

    @Test
    fun oneQuietWordIsSpeechHeard() {
        // A single window at 0.5 or above after the last cut, shorter than the detector's minimum speech, still counts.
        val cutter = cutter(8.0) { it < 4.0 || it in 6.0..6.03 }
        cutter.cut(span(0.0, 4.0, 5.0))
        assertEquals((4.5 * second).toInt() until 8 * second, cutter.rest(8 * second))
    }

    @Test
    fun aTakeWithNoCutLeavesNoPartAfterRelease() {
        // It's turned into text whole instead.
        assertNull(cutter(8.0).rest(8 * second))
    }

    @Test
    fun theSplitterSendsPartsInOrderAsTheyClose() {
        val take = audio(12.0)
        val pauses = ScriptedPauses(listOf(span(0.0, 3.0, 4.0), span(4.5, 7.0, 8.0)))
        val parts = mutableListOf<TakePart>()
        val splitter = TakeSplitter(take, pauses, { parts += it }, clock = { 100_000 })
        splitter.step()
        // All 12 s at once: both pauses close in one step, in order.
        assertEquals(listOf(1, 2), parts.map { it.number })
        assertEquals(listOf(0, 3 * second + second / 2), parts.map { it.start })
        assertEquals(3.5, parts[0].seconds, 0.001)
        // Each part is the take's own audio from its start.
        assertEquals(parts[1].start.toFloat(), parts[1].samples.first())
        splitter.finish()
        val last = parts.last()
        assertEquals(3, last.number)
        assertTrue(last.last)
        assertEquals(7 * second + second / 2, last.start)
        assertEquals(12 * second, last.start + last.samples.size)
    }

    @Test
    fun theSplitterOnlyFeedsWhatIsNew() {
        val take = SampleBuffer()
        val pauses = ScriptedPauses(listOf(span(0.0, 1.0, 2.0))) { it < 1.0 }
        val parts = mutableListOf<TakePart>()
        val splitter = TakeSplitter(take, pauses, { parts += it }, clock = { 0 })
        take.add(FloatArray(second))
        splitter.step()
        splitter.step()
        assertEquals(second.toLong(), pauses.heard)
        assertTrue(parts.isEmpty())
        take.add(FloatArray(second * 2))
        splitter.step()
        assertEquals(3L * second, pauses.heard)
        assertEquals(1, parts.size)
        // No speech after the last cut: nothing more after release.
        splitter.finish()
        assertEquals(1, parts.size)
    }

    @Test
    fun aPartKnowsWhenItsSpeechEnded() {
        val take = audio(6.0)
        val parts = mutableListOf<TakePart>()
        TakeSplitter(take, ScriptedPauses(listOf(span(0.0, 4.0, 5.0))), { parts += it }, clock = { 50_000 }).step()
        // Heard up to 6.0 s at 50 000 ms: the speech ended 2 s before.
        assertEquals(48_000, parts.single().speechEndedAt)
        assertEquals(50_000, parts.single().cutAt)
    }

    @Test
    fun partTextsJoinWithOneSpaceAsTheEngineWroteThem() {
        assertEquals(
            "The session store. Is wired to the cache.",
            joinParts(listOf("The session store.", "Is wired to the cache.")),
        )
        // A part with no words adds no space.
        assertEquals("One. Two.", joinParts(listOf("One.", "", "Two.")))
        assertEquals("", joinParts(emptyList()))
    }
}
