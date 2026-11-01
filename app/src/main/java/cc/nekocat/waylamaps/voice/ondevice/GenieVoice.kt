package cc.nekocat.waylamaps.voice.ondevice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** ondevice.json, written by tools/voice/export_ondevice.py. */
@Serializable
data class OnDeviceManifest(
    val format: Int,
    val sampleRate: Int,
    val bertDim: Int,
    val graphs: Map<String, String>,
    /** fp32 weight file the graphs read -> fp16 file shipped in its place. */
    val widen: Map<String, String> = emptyMap(),
    val tensors: Map<String, TensorFile>,
) {
    @Serializable
    data class TensorFile(val file: String, val shape: List<Long>, val type: String)

    companion object {
        const val FILE_NAME = "ondevice.json"
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): OnDeviceManifest = json.decodeFromString(text)
    }
}

/**
 * One GPT-SoVITS v2ProPlus voice (Genie-TTS's ONNX export) running on the phone with ONNX
 * Runtime: the text encoder, the autoregressive semantic decoder and the VITS vocoder. The
 * reference clip's features were worked out ahead of time, and there are no BERT features
 * (zeros), as Genie does without RoBERTa. Not thread-safe; [close] when done.
 */
class GenieVoice(private val dir: File, private val g2p: ChineseG2P, threads: Int = 4) : AutoCloseable {
    private val manifest = OnDeviceManifest.parse(File(dir, OnDeviceManifest.FILE_NAME).readText())
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val encoder = session("encoder")
    private val firstStage = session("firstStageDecoder")
    private val stage = session("stageDecoder")
    private val vocoder = session("vocoder")
    private val stageInputs = stage.inputNames.toList()

    private val refSeq = longTensor("ref_seq")
    private val refBert = zeros(refSeq.info.shape[1], manifest.bertDim.toLong())
    private val ssl = floatTensor("ssl_content")
    private val ge = floatTensor("ge")
    private val geAdvanced = floatTensor("ge_advanced")

    val sampleRate: Int get() = manifest.sampleRate

    /**
     * 16-bit mono PCM for [text], or null if it has nothing to say. Sampling is random: a run that
     * stops at once or rambles on is tried again.
     */
    fun synthesize(text: String): ShortArray? {
        // Genie puts a full stop in front so the first word is not swallowed.
        val ids = g2p.ids("。$text")
        if (ids.size <= 1) return null
        val maxSeconds = MAX_SECONDS_PER_CHAR * text.length + MAX_SECONDS_SLACK
        repeat(ATTEMPTS) {
            val audio = run(ids) ?: return@repeat
            if (audio.size in MIN_SAMPLES..(maxSeconds * sampleRate).toInt()) return audio.toPcm()
        }
        return null
    }

    private fun run(ids: LongArray): FloatArray? {
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { textSeq ->
            zeros(ids.size.toLong(), manifest.bertDim.toLong()).use { textBert ->
                val semantic = semanticTokens(textSeq, textBert) ?: return null
                OnnxTensor.createTensor(env, LongBuffer.wrap(semantic), longArrayOf(1, 1, semantic.size.toLong())).use { pred ->
                    vocoder.run(mapOf("text_seq" to textSeq, "pred_semantic" to pred, "ge" to ge, "ge_advanced" to geAdvanced)).use {
                        return (it[0].value as FloatArray)
                    }
                }
            }
        }
    }

    /** The semantic tokens after the prompt's, without the end token; null if none came. */
    private fun semanticTokens(textSeq: OnnxTensor, textBert: OnnxTensor): LongArray? {
        encoder.run(
            mapOf("ref_seq" to refSeq, "text_seq" to textSeq, "ref_bert" to refBert, "text_bert" to textBert, "ssl_content" to ssl),
        ).use { encoded ->
            var result = firstStage.run(mapOf("x" to encoded[0] as OnnxTensor, "prompts" to encoded[1] as OnnxTensor))
            try {
                var steps = 0
                for (step in 0 until MAX_STEPS) {
                    steps = step
                    // y and y_emb, then the key/value cache: present_k_layer_N feeds past_k_layer_N.
                    val inputs = stageInputs.associateWith { name ->
                        val output = when (name) {
                            stageInputs[0] -> "y"
                            stageInputs[1] -> "y_emb"
                            else -> name.replaceFirst("past_", "present_")
                        }
                        result.get(output).get() as OnnxTensor
                    }
                    val next = stage.run(inputs)
                    result.close()
                    result = next
                    if (next.get("stop_condition_tensor").get().value as Boolean) break
                }
                // Stopping at once would take the whole prompt as the answer.
                if (steps == 0) return null
                @Suppress("UNCHECKED_CAST")
                val y = (result.get("y").get().value as Array<LongArray>)[0]
                val tokens = y.copyOfRange(y.size - steps, y.size)
                tokens[tokens.lastIndex] = 0
                val end = tokens.indexOfFirst { it >= END_TOKEN }.let { if (it < 0) tokens.size else it }
                return tokens.copyOf(end).takeIf { it.isNotEmpty() }
            } finally {
                result.close()
            }
        }
    }

    override fun close() {
        listOf(refSeq, refBert, ssl, ge, geAdvanced).forEach { it.close() }
        listOf(encoder, firstStage, stage, vocoder).forEach { it.close() }
        options.close()
    }

    private fun session(graph: String): OrtSession =
        env.createSession(File(dir, manifest.graphs.getValue(graph)).absolutePath, options)

    private fun bytes(name: String): ByteBuffer {
        val spec = manifest.tensors.getValue(name)
        return ByteBuffer.wrap(File(dir, spec.file).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun longTensor(name: String): OnnxTensor =
        OnnxTensor.createTensor(env, bytes(name).asLongBuffer(), manifest.tensors.getValue(name).shape.toLongArray())

    private fun floatTensor(name: String): OnnxTensor =
        OnnxTensor.createTensor(env, bytes(name).asFloatBuffer(), manifest.tensors.getValue(name).shape.toLongArray())

    private fun zeros(rows: Long, columns: Long): OnnxTensor =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray((rows * columns).toInt())), longArrayOf(rows, columns))

    private fun FloatArray.toPcm(): ShortArray =
        ShortArray(size) { (this[it].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }

    private companion object {
        const val MAX_STEPS = 500
        const val END_TOKEN = 1024
        const val ATTEMPTS = 3

        /** Road names run about 0.25 s a character; a clip far longer has gone wrong. */
        const val MAX_SECONDS_PER_CHAR = 0.6
        const val MAX_SECONDS_SLACK = 0.6
        const val MIN_SAMPLES = 1_600
    }
}
