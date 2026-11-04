package dev.opencode.voiceengine

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Google's LiteRT runtime with Parakeet TDT exported as separate graphs (litert-community/parakeet-ultra): a log-mel
 * front end and an encoder per window length, and a one-step decoder with `predict` and `joint` signatures. Greedy
 * TDT decoding runs here. On the GPU the encoder runs in float16 on the graphics chip; the rest stays on the CPU.
 *
 * Adapted from BucketedTdtRunner.kt in google-ai-edge/litert-samples (samples/litert/speech_recognition),
 * Copyright 2026 Google LLC, Apache License 2.0.
 */
internal class LiteRtEngine(
    store: ModelStore,
    model: SpeechModel,
    threads: Int,
    gpu: Boolean,
    cacheDir: File,
    private val mask: Long,
) : SpeechEngine {
    /** The mel front end and the encoder for one window length, with their buffers by name. */
    private class Bucket(
        val samples: Int,
        val mel: CompiledModel,
        val encoder: CompiledModel,
    ) {
        val melInputs = listOf("audio", "n_valid").associateWith { mel.createInputBuffer(it, SERVING) }
        val melOutputs = listOf("features", "frame_mask", "mel_mask").associateWith { mel.createOutputBuffer(it, SERVING) }
        val encoderInputs = listOf("features", "mel_mask").associateWith { encoder.createInputBuffer(it, SERVING) }
        val encoderOutputs = mapOf("enc" to encoder.createOutputBuffer("enc", SERVING))

        fun close() {
            mel.close()
            encoder.close()
        }
    }

    private val loaded = mutableListOf<CompiledModel>()
    private val buckets: List<Bucket>
    private val decoder: CompiledModel
    private val vocabulary: Array<String>

    init {
        val env = Environment.create()

        fun cpu(name: String) =
            CompiledModel.Options(setOf(Accelerator.CPU)).apply {
                // XNNPACK packs the weights once and keeps them in this file, which makes later loads faster
                cpuOptions = CompiledModel.CpuOptions(threads, null, File(cacheDir, "xnnpack-${model.id}-$name.cache").path)
            }

        fun open(
            name: String,
            options: CompiledModel.Options,
        ) = CompiledModel.create(store.file(model, name).path, options, env).also { loaded += it }
        try {
            val windows =
                model.files
                    .mapNotNull {
                        Regex("""_encoder_(\d+)s_""")
                            .find(it.name)
                            ?.groupValues
                            ?.get(1)
                            ?.toInt()
                    }.sorted()
            buckets =
                onCores(mask) {
                    windows.map { seconds ->
                        val encoderFile = model.files.first { it.name.contains("_encoder_${seconds}s_") }.name
                        val encoderOptions =
                            if (gpu) {
                                CompiledModel.Options(setOf(Accelerator.CPU, Accelerator.GPU)).apply {
                                    // the float16 encoders rescale their LayerNorm inputs so float16 arithmetic does not overflow
                                    gpuOptions = CompiledModel.GpuOptions(precision = CompiledModel.GpuOptions.Precision.FP16)
                                }
                            } else {
                                cpu(encoderFile)
                            }
                        Bucket(
                            seconds * VoiceEngine.SAMPLE_RATE,
                            open(model.files.first { it.name.contains("_mel_${seconds}s") }.name, cpu("mel$seconds")),
                            open(encoderFile, encoderOptions),
                        )
                    }
                }
            decoder =
                onCores(
                    mask,
                ) { open(model.files.first { it.name.contains("_decoder_") }.name, CompiledModel.Options(setOf(Accelerator.CPU))) }
            vocabulary = readVocabulary(store.file(model, "tokenizer.json"))
        } catch (e: Exception) {
            loaded.forEach { it.close() }
            throw e
        }
    }

    // predict: inputs c, h, token; outputs c_out, dec_out, h_out. joint: inputs dec_out, enc_frame; output logits.
    // joint reads predict's dec_out buffer directly, and the LSTM state buffers swap roles each step.
    private val token = decoder.createInputBuffer("token", PREDICT)
    private val decoderOut = decoder.createOutputBuffer("dec_out", PREDICT)
    private val encoderFrame = decoder.createInputBuffer("enc_frame", JOINT)
    private val jointInputs = mapOf("dec_out" to decoderOut, "enc_frame" to encoderFrame)
    private val jointOutputs = mapOf("logits" to decoder.createOutputBuffer("logits", JOINT))
    private val states =
        listOf(
            listOf("c", "h").map { decoder.createInputBuffer(it, PREDICT) },
            listOf("c_out", "h_out").map { decoder.createOutputBuffer(it, PREDICT) },
        )
    private val predictInputs: List<Map<String, TensorBuffer>> = states.map { (c, h) -> mapOf("c" to c, "h" to h, "token" to token) }
    private val predictOutputs: List<Map<String, TensorBuffer>> =
        states.map { (c, h) ->
            mapOf(
                "c_out" to c,
                "dec_out" to decoderOut,
                "h_out" to h,
            )
        }
    private var stateIndex = 0

    override suspend fun transcribe(pcm: FloatArray): String =
        withContext(Dispatchers.Default) {
            onCores(mask) {
                // takes longer than the longest window run in pieces of that length
                val longest = buckets.last().samples
                (0 until pcm.size step longest)
                    .map { start -> window(pcm.copyOfRange(start, minOf(pcm.size, start + longest))) }
                    .filter { it.isNotEmpty() }
                    .joinToString(" ")
            }
        }

    private fun window(audio: FloatArray): String {
        if (audio.size < MIN_SAMPLES) return ""
        val bucket = buckets.first { audio.size <= it.samples }
        bucket.melInputs.getValue("audio").writeFloat(audio.copyOf(bucket.samples))
        bucket.melInputs.getValue("n_valid").writeInt(intArrayOf(audio.size))
        bucket.mel.run(bucket.melInputs, bucket.melOutputs, SERVING)
        // the two graphs may run on different accelerators, so copy rather than share the buffers
        for ((name, buffer) in bucket.encoderInputs) buffer.writeFloat(bucket.melOutputs.getValue(name).readFloat())
        bucket.encoder.run(bucket.encoderInputs, bucket.encoderOutputs, SERVING)
        val encoded = bucket.encoderOutputs.getValue("enc").readFloat()
        val frames =
            bucket.melOutputs
                .getValue("frame_mask")
                .readFloat()
                .sum()
                .toInt()
        return text(decodeGreedy(encoded, frames))
    }

    /** Greedy TDT decoding of one window, as in the model card's "How it works". */
    private fun decodeGreedy(
        encoded: FloatArray,
        frames: Int,
    ): List<Int> {
        val tokens = mutableListOf<Int>()
        states[stateIndex].forEach { it.writeFloat(FloatArray(STATES)) }
        predict(BLANK)
        var t = 0
        var steps = 0
        var atFrame = 0
        while (t < frames && steps++ < MAX_STEPS) {
            encoderFrame.writeFloat(encoded.copyOfRange(t * ENCODER_DIM, (t + 1) * ENCODER_DIM))
            decoder.run(jointInputs, jointOutputs, JOINT)
            val logits = jointOutputs.getValue("logits").readFloat()
            val id = argmax(logits, 0, VOCAB)
            var duration = argmax(logits, VOCAB, VOCAB + DURATIONS) - VOCAB
            if (id == BLANK) {
                if (duration == 0) duration = 1
            } else {
                if (id == END_OF_TEXT) break
                tokens += id
                predict(id)
            }
            if (duration == 0 && ++atFrame >= MAX_TOKENS_PER_FRAME) duration = 1
            if (duration > 0) atFrame = 0
            t += duration
        }
        return tokens
    }

    private fun predict(id: Int) {
        token.writeInt(intArrayOf(id))
        decoder.run(predictInputs[stateIndex], predictOutputs[1 - stateIndex], PREDICT)
        stateIndex = 1 - stateIndex
    }

    /** SentencePiece pieces to text: "▁" starts a word; control tokens like `<|endoftext|>` are skipped. */
    private fun text(tokens: List<Int>): String =
        tokens
            .map { vocabulary.getOrElse(it) { "" } }
            .filterNot { it.startsWith("<") && it.endsWith(">") }
            .joinToString("")
            .replace('▁', ' ')
            .trim()

    override fun close() {
        buckets.forEach { it.close() }
        decoder.close()
    }

    private companion object {
        const val SERVING = "serving_default"
        const val PREDICT = "predict"
        const val JOINT = "joint"
        const val MIN_SAMPLES = 320
        const val ENCODER_DIM = 640
        const val STATES = 2 * 640
        const val VOCAB = 8193
        const val BLANK = 8192
        const val END_OF_TEXT = 3
        const val DURATIONS = 5
        const val MAX_TOKENS_PER_FRAME = 10
        const val MAX_STEPS = 3750

        fun argmax(
            values: FloatArray,
            from: Int,
            until: Int,
        ): Int {
            var best = from
            for (i in from until until) if (values[i] > values[best]) best = i
            return best
        }

        fun readVocabulary(tokenizer: File): Array<String> {
            val vocab = JSONObject(tokenizer.readText()).getJSONObject("model").getJSONObject("vocab")
            val out = Array(vocab.length()) { "" }
            for (piece in vocab.keys()) {
                val id = vocab.getInt(piece)
                if (id in out.indices) out[id] = piece
            }
            return out
        }
    }
}
