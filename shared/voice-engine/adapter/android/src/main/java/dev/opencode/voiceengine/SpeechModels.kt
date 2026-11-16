package dev.opencode.voiceengine

import android.content.Context
import org.json.JSONObject
import java.io.File

/** One file of a speech model, pinned to a Hugging Face revision and checked by its SHA-256. */
data class ModelFile(
    val name: String,
    val url: String,
    val bytes: Long,
    val sha256: String,
)

/**
 * A speech model the app can run, from `assets/lab/models.json`. [engine] says what runs it: "sherpa-onnx" (ONNX
 * Runtime), "litert" (Google's LiteRT) or "android" (the phone's own recognizer, nothing to download).
 * The word error rates are published ones (LibriSpeech test-clean and FLEURS English), in percent, for comparison only.
 */
data class SpeechModel(
    val id: String,
    val title: String,
    val engine: String,
    val kind: String,
    val params: Long,
    val license: String,
    val source: String,
    val note: String,
    val librispeechWer: Double?,
    val fleursWer: Double?,
    val files: List<ModelFile>,
) {
    val bytes: Long get() = files.sumOf { it.bytes }
}

/** A test recording with the text a good transcript would have, from `assets/lab/clips.json`. */
data class TestClip(
    val id: String,
    val file: String,
    val seconds: Double,
    val voice: String,
    val reference: String,
)

object SpeechModels {
    /**
     * What dictation uses until someone picks another model in the lab: Parakeet V2, the model the iPhone runs, on
     * ONNX Runtime with XNNPACK. Lab results are compared against it (see shared/voice-engine/README.md).
     */
    const val DICTATION_DEFAULT = "parakeet-v2-onnx-int8"

    /**
     * The pause detector that cuts a locked take into parts while it records (app-plan.md, Dictation › Locked takes):
     * Silero VAD v5 on sherpa-onnx. Not sherpa-onnx's default `silero_vad.onnx`, which is v4 and lost half the pauses in
     * noise and at the Pixel's quiet input (spike S3). Pinned to a Hugging Face revision and checked by its SHA-256 like
     * the speech models, and fetched with dictation's model; it isn't one the lab lists.
     */
    val PAUSES =
        SpeechModel(
            id = "silero-vad-v5",
            title = "Silero VAD v5",
            engine = "sherpa-onnx",
            kind = "silero_vad",
            params = 2_000_000,
            license = "MIT",
            source = "https://huggingface.co/csukuangfj/vad",
            note = "Finds the pauses a locked take is cut at.",
            librispeechWer = null,
            fleursWer = null,
            files =
                listOf(
                    ModelFile(
                        "silero_vad_v5.onnx",
                        "https://huggingface.co/csukuangfj/vad/resolve/fba88cd2e921609e7675c3aaf51e0b9b295da4bc/silero_vad_v5.onnx",
                        2_313_101,
                        "6b99cbfd39246b6706f98ec13c7c50c6b299181f2474fa05cbc8046acc274396",
                    ),
                ),
        )

    fun load(context: Context): List<SpeechModel> {
        val json =
            JSONObject(
                context.assets
                    .open("lab/models.json")
                    .bufferedReader()
                    .readText(),
            )
        val models = json.getJSONArray("models")
        return (0 until models.length()).map { i ->
            val m = models.getJSONObject(i)
            val files = m.getJSONArray("files")
            SpeechModel(
                id = m.getString("id"),
                title = m.getString("title"),
                engine = m.getString("engine"),
                kind = m.getString("kind"),
                params = m.getLong("params"),
                license = m.getString("license"),
                source = m.getString("source"),
                note = m.getString("note"),
                librispeechWer = m.optDouble("librispeechWer").takeUnless { it.isNaN() },
                fleursWer = m.optDouble("fleursWer").takeUnless { it.isNaN() },
                files =
                    (0 until files.length()).map { j ->
                        val f = files.getJSONObject(j)
                        ModelFile(f.getString("name"), f.getString("url"), f.getLong("bytes"), f.getString("sha256"))
                    },
            )
        }
    }

    fun clips(context: Context): List<TestClip> =
        parseClips(
            context.assets
                .open("lab/clips.json")
                .bufferedReader()
                .readText(),
        )

    /** Reads a `clips.json`; [prefix] goes in front of each id ("edacc/" for a set copied onto the phone). */
    fun parseClips(
        json: String,
        prefix: String = "",
    ): List<TestClip> {
        val clips = JSONObject(json).getJSONArray("clips")
        return (0 until clips.length()).map { i ->
            val c = clips.getJSONObject(i)
            TestClip(
                prefix + c.getString("id"),
                c.getString("file"),
                c.getDouble("seconds"),
                c.getString("voice"),
                c.getString("reference"),
            )
        }
    }

    fun clipAudio(
        context: Context,
        clip: TestClip,
    ): FloatArray = context.assets.open("lab/${clip.file}").use { VoiceEngine.readWav(it.readBytes()) }
}

/**
 * Where model files live: the app's own files folder, `models/<id>/`. For testing, debug builds can be filled
 * without downloading: `adb push` to /data/local/tmp, then `adb shell run-as <app id> cp` into that folder.
 * Downloads go to a `.part` file first, resume after a dropped connection, and are checked against their SHA-256.
 */
class ModelStore(
    context: Context,
) {
    private val root = File(context.filesDir, "models")
    private val downloader = ModelDownload()

    fun dir(model: SpeechModel) = File(root, model.id)

    fun file(
        model: SpeechModel,
        name: String,
    ) = File(dir(model), name)

    fun isInstalled(model: SpeechModel) = model.files.all { file(model, it.name).length() == it.bytes }

    /** What's on the phone of [model] so far, whole files and `.part`s, for "Stopped at 120 of 640 MB". */
    fun bytesOnPhone(model: SpeechModel): Long =
        model.files.sumOf { f ->
            val whole = file(model, f.name).length()
            if (whole == f.bytes) whole else File(dir(model), f.name + ".part").length().coerceAtMost(f.bytes)
        }

    /** A download waiting to retry tries again now ([ModelDownload.tryNow]). */
    fun tryNow() = downloader.tryNow()

    fun delete(model: SpeechModel) {
        dir(model).deleteRecursively()
    }

    /**
     * Deletes what no listed model, nor the pause detector ([SpeechModels.PAUSES]), uses: other models' folders, and
     * loose files such as the GGUF models (and their half-done `.part` downloads) of the removed transcribe.cpp engine.
     * Returns the names it removed.
     */
    fun removeUnlisted(models: List<SpeechModel>): List<String> {
        val keep = models.map { it.id }.toSet() + SpeechModels.PAUSES.id
        return root
            .listFiles()
            .orEmpty()
            .filter { !(it.isDirectory && it.name in keep) }
            .onEach { it.deleteRecursively() }
            .map { it.name }
    }

    /**
     * Downloads the files that are missing, carrying on a `.part` an earlier attempt left ([ModelDownload]).
     * [progress] gets bytes on the phone and bytes in all; while the connection is down, [retrying] gets the seconds
     * to the next attempt ([ModelDownload.fetch]).
     */
    fun download(
        model: SpeechModel,
        retrying: (Int) -> Unit = {},
        progress: (Long, Long) -> Unit,
    ) {
        dir(model).mkdirs()
        var before = 0L
        for (f in model.files) {
            val target = file(model, f.name)
            if (target.length() != f.bytes) downloader.fetch(f, target, { progress(before + it, model.bytes) }, retrying)
            before += f.bytes
            progress(before, model.bytes)
        }
    }
}
