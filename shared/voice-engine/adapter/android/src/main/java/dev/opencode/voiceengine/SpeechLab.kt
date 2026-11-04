package dev.opencode.voiceengine

import android.content.Context
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File

/** One test clip run through one model on this phone. Times are in milliseconds. */
data class LabResult(
    val runId: Long,
    val device: String,
    val modelId: String,
    val modelTitle: String,
    val engine: String,
    val options: String,
    val clipId: String,
    val audioSeconds: Double,
    /** Time from the end of the audio to the text: what you wait after letting go. */
    val waitMs: Double,
    val loadMs: Double,
    /** The first transcription after loading, which is slower while caches warm up. */
    val firstMs: Double,
    val reference: String,
    val text: String,
    val referenceWords: Int,
    val wordErrors: Int,
    val memoryMb: Int,
    val thermal: Int,
) {
    fun toJson(): String =
        JSONObject()
            .put("runId", runId)
            .put("device", device)
            .put("modelId", modelId)
            .put("modelTitle", modelTitle)
            .put("engine", engine)
            .put("options", options)
            .put("clipId", clipId)
            .put("audioSeconds", audioSeconds)
            .put("waitMs", waitMs)
            .put("loadMs", loadMs)
            .put("firstMs", firstMs)
            .put("reference", reference)
            .put("text", text)
            .put("referenceWords", referenceWords)
            .put("wordErrors", wordErrors)
            .put("memoryMb", memoryMb)
            .put("thermal", thermal)
            .toString()

    companion object {
        fun fromJson(line: String): LabResult {
            val o = JSONObject(line)
            return LabResult(
                o.getLong("runId"),
                o.getString("device"),
                o.getString("modelId"),
                o.getString("modelTitle"),
                o.getString("engine"),
                o.getString("options"),
                o.getString("clipId"),
                o.getDouble("audioSeconds"),
                o.getDouble("waitMs"),
                o.getDouble("loadMs"),
                o.getDouble("firstMs"),
                o.getString("reference"),
                o.getString("text"),
                o.getInt("referenceWords"),
                o.getInt("wordErrors"),
                o.getInt("memoryMb"),
                o.getInt("thermal"),
            )
        }
    }
}

/** One model's results from one run on this phone, added up across the clips. */
data class LabSummary(
    val modelId: String,
    val modelTitle: String,
    val engine: String,
    val options: String,
    val runId: Long,
    val clips: Int,
    /** Wait after letting go, scaled to 10 s of speech. */
    val waitPer10s: Double,
    /** Wait for the longest clip, in seconds, with its length. */
    val longestWait: Double,
    val longestSeconds: Double,
    /** Word error rate against the reference text, percent. */
    val wer: Double,
    /** How far the text is from Hex's default model on the same clips, percent of words; null without its run. */
    val vsHex: Double?,
    val loadMs: Double,
    val firstMs: Double,
    val memoryMb: Int,
    val results: List<LabResult>,
)

/**
 * The speech model lab: runs a set of test clips through a model and records how long you wait and how many
 * words come out wrong. The "technical" set (technical English, text-to-speech) comes with the app; other sets
 * can be copied onto a debug build as `files/lab-sets/<name>/clips.json` plus WAVs (16 kHz mono), for test
 * recordings that may not be shipped. Results are kept in `lab/results.jsonl` in the app's external files
 * folder (one JSON object per clip), so `adb pull` can collect them too.
 */
class SpeechLab(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val file = File(context.getExternalFilesDir(null) ?: context.filesDir, "lab/results.jsonl")
    val clips: List<TestClip> = SpeechModels.clips(context)
    private val sets = File(context.filesDir, "lab-sets")

    /** The clip sets on this phone: "technical" first, then any copied in. */
    fun clipSets(): List<String> =
        listOf(TECHNICAL) + (
            sets
                .listFiles()
                ?.filter { File(it, "clips.json").isFile }
                ?.map { it.name }
                ?.sorted() ?: emptyList()
        )

    private fun clipsOf(set: String): List<Pair<TestClip, () -> FloatArray>> =
        if (set == TECHNICAL) {
            clips.map { it to { SpeechModels.clipAudio(appContext, it) } }
        } else {
            val dir = File(sets, set)
            SpeechModels.parseClips(File(dir, "clips.json").readText(), prefix = "$set/").map {
                it to { VoiceEngine.readWav(File(dir, it.file)) }
            }
        }

    val device = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    fun results(): List<LabResult> =
        if (!file.isFile) {
            emptyList()
        } else {
            file.readLines().filter { it.isNotBlank() }.mapNotNull { runCatching { LabResult.fromJson(it) }.getOrNull() }
        }

    fun clear() {
        file.delete()
    }

    /** The latest run of each model and setting on this phone with [set]'s clips, fastest first. */
    fun summaries(set: String = TECHNICAL): List<LabSummary> {
        val all = results().filter { it.device == device && !it.clipId.startsWith("voice:") && setName(it.clipId) == set }
        val latest = all.groupBy { it.modelId to it.options }.mapValues { (_, rs) -> rs.filter { it.runId == rs.maxOf { r -> r.runId } } }
        val hex = latest.entries.firstOrNull { it.key.first == SpeechModels.HEX_DEFAULT && it.key.second == "fast cores" }?.value
        return latest.values
            .map { rs ->
                val first = rs.first()
                val audio = rs.sumOf { it.audioSeconds }
                val longest = rs.maxBy { it.audioSeconds }
                val vsHex =
                    hex?.let { base ->
                        val pairs = rs.mapNotNull { r -> base.firstOrNull { it.clipId == r.clipId }?.let { r to it } }
                        if (pairs.isEmpty()) {
                            null
                        } else {
                            val words = pairs.sumOf { Wer.words(it.second.text).size }
                            100.0 * pairs.sumOf { Wer.errors(it.second.text, it.first.text) } / maxOf(1, words)
                        }
                    }
                LabSummary(
                    modelId = first.modelId,
                    modelTitle = first.modelTitle,
                    engine = first.engine,
                    options = first.options,
                    runId = first.runId,
                    clips = rs.size,
                    waitPer10s = rs.sumOf { it.waitMs } / 1000 / audio * 10,
                    longestWait = longest.waitMs / 1000,
                    longestSeconds = longest.audioSeconds,
                    wer = 100.0 * rs.sumOf { it.wordErrors } / maxOf(1, rs.sumOf { it.referenceWords }),
                    vsHex = vsHex,
                    loadMs = first.loadMs,
                    firstMs = first.firstMs,
                    memoryMb = rs.maxOf { it.memoryMb },
                    results = rs,
                )
            }.sortedBy { it.waitPer10s }
    }

    /**
     * Runs every clip through [engine], which is already loaded ([loadMs] it took). Engines that listen while
     * you talk get each clip at real-time pace, as a live take would arrive; the others get the whole clip.
     */
    suspend fun run(
        model: SpeechModel,
        options: EngineOptions,
        engine: SpeechEngine,
        loadMs: Double,
        set: String = TECHNICAL,
        progress: (String) -> Unit,
    ): List<LabResult> {
        val runId = System.currentTimeMillis()
        val clips = clipsOf(set)
        progress("Warming up")
        val firstMs = timed { engine.transcribe(clips.first().second()) }
        val out = mutableListOf<LabResult>()
        for ((i, entry) in clips.withIndex()) {
            val (clip, audio) = entry
            progress("Clip ${i + 1} of ${clips.size}")
            val (text, waitMs) = transcribeLikeATake(engine, audio())
            out += result(runId, model, options, clip.id, clip.seconds, clip.reference, text, waitMs, loadMs, firstMs)
        }
        file.parentFile?.mkdirs()
        file.appendText(out.joinToString("") { it.toJson() + "\n" })
        return out
    }

    /** Records one take spoken into the lab (reading [reference] aloud), with the same measures as a clip. */
    fun recordVoice(
        model: SpeechModel,
        options: EngineOptions,
        reference: String,
        seconds: Double,
        text: String,
        waitMs: Double,
    ): LabResult {
        val r =
            result(System.currentTimeMillis(), model, options, "voice:${reference.take(24)}", seconds, reference, text, waitMs, 0.0, 0.0)
        file.parentFile?.mkdirs()
        file.appendText(r.toJson() + "\n")
        return r
    }

    private fun result(
        runId: Long,
        model: SpeechModel,
        options: EngineOptions,
        clipId: String,
        seconds: Double,
        reference: String,
        text: String,
        waitMs: Double,
        loadMs: Double,
        firstMs: Double,
    ) = LabResult(
        runId = runId,
        device = device,
        modelId = model.id,
        modelTitle = model.title,
        engine = model.engine,
        options = options.label(model),
        clipId = clipId,
        audioSeconds = seconds,
        waitMs = waitMs,
        loadMs = loadMs,
        firstMs = firstMs,
        reference = reference,
        text = text.trim(),
        referenceWords = Wer.words(reference).size,
        wordErrors = Wer.errors(reference, text),
        memoryMb = memoryMb(),
        thermal = (appContext.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus,
    )

    private suspend fun transcribeLikeATake(
        engine: SpeechEngine,
        pcm: FloatArray,
    ): Pair<String, Double> {
        // Google's recognizer takes one session at a time and is still closing the last one for a moment
        if (engine.listensWhileYouTalk) delay(1500)
        val stream = engine.startStream()
        if (stream == null) {
            var text = ""
            val ms = timed { text = engine.transcribe(pcm) }
            return text to ms
        }
        val chunk = VoiceEngine.SAMPLE_RATE / 10
        var at = 0
        while (at < pcm.size) {
            val end = minOf(pcm.size, at + chunk)
            stream.accept(pcm.copyOfRange(at, end))
            at = end
            delay(100)
        }
        var text = ""
        val ms = timed { text = stream.finish() }
        return text to ms
    }

    fun csv(): String =
        buildString {
            appendLine("run,device,model,engine,options,clip,audio_s,wait_ms,load_ms,first_ms,ref_words,word_errors,memory_mb,thermal,text")
            for (r in results()) {
                appendLine(
                    listOf(
                        r.runId,
                        r.device,
                        r.modelId,
                        r.engine,
                        r.options,
                        r.clipId,
                        r.audioSeconds,
                        "%.0f".format(r.waitMs),
                        "%.0f".format(r.loadMs),
                        "%.0f".format(r.firstMs),
                        r.referenceWords,
                        r.wordErrors,
                        r.memoryMb,
                        r.thermal,
                        "\"" + r.text.replace("\"", "\"\"") + "\"",
                    ).joinToString(","),
                )
            }
        }

    companion object {
        /** The bundled clip set: technical English, spoken by text-to-speech voices. */
        const val TECHNICAL = "technical"

        /** Which set a result's clip belongs to: the part of its id before "/", or the bundled set. */
        fun setName(clipId: String) = if ('/' in clipId) clipId.substringBefore('/') else TECHNICAL

        /** The process's resident memory now, in MB, from /proc. */
        fun memoryMb(): Int =
            runCatching {
                File("/proc/self/status")
                    .readLines()
                    .first { it.startsWith("VmRSS:") }
                    .filter { it.isDigit() }
                    .toInt() / 1024
            }.getOrDefault(0)

        inline fun timed(block: () -> Unit): Double {
            val t0 = System.nanoTime()
            block()
            return (System.nanoTime() - t0) / 1e6
        }
    }
}

/**
 * Word error rate for technical dictation. Both texts are lowercased, camelCase and letter-digit joins are split
 * ("useEffect", "arm64"), punctuation is dropped and numbers are spelled out, so "App.tsx" and "app tsx", or
 * "300" and "three hundred", count as the same words. Hesitations ("uh", "um") are dropped from both, since
 * real-speech references keep them and dictation should not.
 */
object Wer {
    private val fillers = setOf("uh", "um", "uhm", "umm", "er", "erm", "ah", "eh", "hmm", "hm", "mm", "mhm", "mmm")

    fun words(text: String): List<String> {
        val split =
            text
                .replace(Regex("([a-z])([A-Z])"), "$1 $2")
                .replace(Regex("([A-Za-z])([0-9])"), "$1 $2")
                .replace(Regex("([0-9])([A-Za-z])"), "$1 $2")
                .replace("%", " percent ")
                .replace("'", "")
                .lowercase()
        return split.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() && it !in fillers }.flatMap { w ->
            if (w.all { it.isDigit() } && w.length <= 4) spell(w.toInt()) else listOf(w)
        }
    }

    /** Substitutions, deletions and insertions to turn [reference] into [hypothesis]. */
    fun errors(
        reference: String,
        hypothesis: String,
    ): Int {
        val r = words(reference)
        val h = words(hypothesis)
        var previous = IntArray(h.size + 1) { it }
        for (i in 1..r.size) {
            val row = IntArray(h.size + 1)
            row[0] = i
            for (j in 1..h.size) {
                row[j] = minOf(previous[j] + 1, row[j - 1] + 1, previous[j - 1] + if (r[i - 1] == h[j - 1]) 0 else 1)
            }
            previous = row
        }
        return previous[h.size]
    }

    private val ones =
        listOf(
            "zero",
            "one",
            "two",
            "three",
            "four",
            "five",
            "six",
            "seven",
            "eight",
            "nine",
            "ten",
            "eleven",
            "twelve",
            "thirteen",
            "fourteen",
            "fifteen",
            "sixteen",
            "seventeen",
            "eighteen",
            "nineteen",
        )
    private val tens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    private fun spell(n: Int): List<String> =
        when {
            n < 20 -> listOf(ones[n])
            n < 100 -> listOf(tens[n / 10]) + if (n % 10 > 0) listOf(ones[n % 10]) else emptyList()
            n < 1000 -> listOf(ones[n / 100], "hundred") + if (n % 100 > 0) spell(n % 100) else emptyList()
            else -> spell(n / 1000) + "thousand" + if (n % 1000 > 0) spell(n % 1000) else emptyList()
        }
}
