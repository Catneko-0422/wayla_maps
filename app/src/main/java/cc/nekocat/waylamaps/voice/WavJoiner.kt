package cc.nekocat.waylamaps.voice

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Joins PCM WAV clips into one WAV with a short pause between them. A playlist of separate
 * clips stalls briefly at every switch, which makes a sentence sound choppy; one file plays
 * through evenly.
 */
object WavJoiner {
    private data class Pcm(val channels: Int, val sampleRate: Int, val bitsPerSample: Int, val data: ByteArray) {
        val format get() = Triple(channels, sampleRate, bitsPerSample)
    }

    /** Null when a clip is not plain PCM WAV or the clips' formats differ. */
    fun join(clips: List<ByteArray>, gapMs: Int): ByteArray? {
        val parsed = clips.map { parse(it) ?: return null }
        val first = parsed.firstOrNull() ?: return null
        if (parsed.any { it.format != first.format }) return null
        val frameBytes = first.channels * first.bitsPerSample / 8
        val gap = ByteArray(first.sampleRate * gapMs / 1000 * frameBytes)
        val dataSize = parsed.sumOf { it.data.size } + gap.size * (parsed.size - 1)
        val out = ByteBuffer.allocate(HEADER_BYTES + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVE".toByteArray())
        out.put("fmt ".toByteArray()).putInt(16).putShort(PCM_FORMAT.toShort()).putShort(first.channels.toShort())
            .putInt(first.sampleRate).putInt(first.sampleRate * frameBytes).putShort(frameBytes.toShort())
            .putShort(first.bitsPerSample.toShort())
        out.put("data".toByteArray()).putInt(dataSize)
        parsed.forEachIndexed { index, clip ->
            if (index > 0) out.put(gap)
            out.put(clip.data)
        }
        return out.array()
    }

    /**
     * Cuts leading and trailing silence (below [threshold] of full scale) from 16-bit PCM, keeping
     * [keepMs] on each side, so a clip joins the sentence without a gap. Unchanged if not 16-bit
     * PCM WAV.
     */
    fun trimSilence(bytes: ByteArray, threshold: Double = 0.02, keepMs: Int = 60): ByteArray {
        val pcm = parse(bytes) ?: return bytes
        if (pcm.bitsPerSample != 16) return bytes
        val frameBytes = pcm.channels * 2
        val frames = pcm.data.size / frameBytes
        val samples = ByteBuffer.wrap(pcm.data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val limit = (threshold * Short.MAX_VALUE).toInt()
        fun loud(frame: Int) = (0 until pcm.channels).any { kotlin.math.abs(samples.get(frame * pcm.channels + it).toInt()) > limit }
        val first = (0 until frames).firstOrNull(::loud) ?: return bytes
        val last = (frames - 1 downTo 0).first(::loud)
        val keep = pcm.sampleRate * keepMs / 1000
        val start = (first - keep).coerceAtLeast(0)
        val end = (last + keep + 1).coerceAtMost(frames)
        val trimmed = pcm.copy(data = pcm.data.copyOfRange(start * frameBytes, end * frameBytes))
        return join(listOf(write(trimmed)), gapMs = 0) ?: bytes
    }

    /**
     * Plays 16-bit PCM at [speed] (below 1 is slower) without changing its pitch, using Media3's
     * Sonic time stretcher. Unchanged if not 16-bit PCM WAV.
     */
    @OptIn(UnstableApi::class)
    fun changeSpeed(bytes: ByteArray, speed: Float): ByteArray {
        val pcm = parse(bytes) ?: return bytes
        if (pcm.bitsPerSample != 16 || speed == 1f) return bytes
        val sonic = SonicAudioProcessor().apply { setSpeed(speed) }
        sonic.configure(AudioProcessor.AudioFormat(pcm.sampleRate, pcm.channels, C.ENCODING_PCM_16BIT))
        sonic.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val input = ByteBuffer.allocateDirect(pcm.data.size).order(ByteOrder.nativeOrder())
        input.put(pcm.data).flip()
        val out = java.io.ByteArrayOutputStream()
        fun drain() {
            val chunk = sonic.output
            val array = ByteArray(chunk.remaining())
            chunk.get(array)
            out.write(array)
        }
        while (input.hasRemaining()) {
            sonic.queueInput(input)
            drain()
        }
        sonic.queueEndOfStream()
        while (!sonic.isEnded) drain()
        sonic.reset()
        return write(pcm.copy(data = out.toByteArray()))
    }

    /** How long a PCM WAV clip plays, or null if it is not one. */
    fun durationSeconds(bytes: ByteArray): Double? {
        val pcm = parse(bytes) ?: return null
        val frameBytes = pcm.channels * pcm.bitsPerSample / 8
        return pcm.data.size.toDouble() / frameBytes / pcm.sampleRate
    }

    /** A mono 16-bit PCM WAV file of [samples]. */
    fun encode(samples: ShortArray, sampleRate: Int): ByteArray {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { data.putShort(it) }
        return write(Pcm(channels = 1, sampleRate = sampleRate, bitsPerSample = 16, data = data.array()))
    }

    private fun write(pcm: Pcm): ByteArray {
        val frameBytes = pcm.channels * pcm.bitsPerSample / 8
        return ByteBuffer.allocate(HEADER_BYTES + pcm.data.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(36 + pcm.data.size).put("WAVE".toByteArray())
            put("fmt ".toByteArray()).putInt(16).putShort(PCM_FORMAT.toShort()).putShort(pcm.channels.toShort())
                .putInt(pcm.sampleRate).putInt(pcm.sampleRate * frameBytes).putShort(frameBytes.toShort())
                .putShort(pcm.bitsPerSample.toShort())
            put("data".toByteArray()).putInt(pcm.data.size).put(pcm.data)
        }.array()
    }

    private fun parse(bytes: ByteArray): Pcm? {
        if (bytes.size < 12 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var format: Triple<Int, Int, Int>? = null
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4)
            val size = buffer.getInt(offset + 4)
            val body = offset + 8
            if (size < 0 || body + size > bytes.size) return null
            when (id) {
                "fmt " -> {
                    if (buffer.getShort(body).toInt() != PCM_FORMAT) return null
                    format = Triple(buffer.getShort(body + 2).toInt(), buffer.getInt(body + 4), buffer.getShort(body + 14).toInt())
                }
                "data" -> {
                    val (channels, rate, bits) = format ?: return null
                    return Pcm(channels, rate, bits, bytes.copyOfRange(body, body + size))
                }
            }
            // Chunks are padded to an even size.
            offset = body + size + (size and 1)
        }
        return null
    }

    private const val PCM_FORMAT = 1
    private const val HEADER_BYTES = 44
}
