package dev.opencode.voiceengine

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

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
 * Downloads go to a `.part` file first and are checked against their SHA-256.
 */
class ModelStore(
    context: Context,
) {
    private val root = File(context.filesDir, "models")

    fun dir(model: SpeechModel) = File(root, model.id)

    fun file(
        model: SpeechModel,
        name: String,
    ) = File(dir(model), name)

    fun isInstalled(model: SpeechModel) = model.files.all { file(model, it.name).length() == it.bytes }

    fun delete(model: SpeechModel) {
        dir(model).deleteRecursively()
    }

    /**
     * Deletes model folders that no listed model uses, such as the GGUF models of the removed transcribe.cpp engine.
     * Returns the ids it removed.
     */
    fun removeUnlisted(models: List<SpeechModel>): List<String> {
        val keep = models.map { it.id }.toSet()
        return root
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name !in keep }
            .onEach { it.deleteRecursively() }
            .map { it.name }
    }

    /** Downloads the files that are missing. [progress] gets bytes done and bytes in all. */
    fun download(
        model: SpeechModel,
        progress: (Long, Long) -> Unit,
    ) {
        dir(model).mkdirs()
        var before = 0L
        for (f in model.files) {
            val target = file(model, f.name)
            if (target.length() != f.bytes) fetch(f, target) { progress(before + it, model.bytes) }
            before += f.bytes
            progress(before, model.bytes)
        }
    }

    private fun fetch(
        f: ModelFile,
        target: File,
        progress: (Long) -> Unit,
    ) {
        val part = File(target.path + ".part")
        val connection = URL(f.url).openConnection() as HttpURLConnection
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
                    if (done - lastReport > 2_000_000) {
                        lastReport = done
                        progress(done)
                    }
                }
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (sha != f.sha256) {
            part.delete()
            error("${f.name} downloaded damaged. Try again.")
        }
        check(part.renameTo(target)) { "Could not save ${f.name}" }
    }
}
