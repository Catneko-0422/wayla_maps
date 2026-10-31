package cc.nekocat.waylamaps.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavJoinerTest {
    private fun wav(samples: ShortArray, rate: Int = 1000, extraChunk: Boolean = false): ByteArray {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { samples.forEach { putShort(it) } }.array()
        val extra = if (extraChunk) 8 + 3 + 1 else 0
        return ByteBuffer.allocate(44 + extra + data.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(36 + extra + data.size).put("WAVE".toByteArray())
            put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            // An odd-sized chunk before the data, padded to even length.
            if (extraChunk) put("LIST".toByteArray()).putInt(3).put(byteArrayOf(1, 2, 3, 0))
            put("data".toByteArray()).putInt(data.size).put(data)
        }.array()
    }

    private fun samplesOf(wav: ByteArray): ShortArray {
        val buffer = ByteBuffer.wrap(wav, 44, wav.size - 44).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray((wav.size - 44) / 2) { buffer.short }
    }

    @Test
    fun joinsClipsWithSilenceBetween() {
        val joined = WavJoiner.join(listOf(wav(shortArrayOf(1, 2)), wav(shortArrayOf(3), extraChunk = true)), gapMs = 3)!!

        assertArrayEquals(shortArrayOf(1, 2, 0, 0, 0, 3), samplesOf(joined))
        assertEquals(joined.size - 8, ByteBuffer.wrap(joined, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int)
    }

    @Test
    fun refusesMixedFormats() {
        assertNull(WavJoiner.join(listOf(wav(shortArrayOf(1), rate = 1000), wav(shortArrayOf(2), rate = 2000)), gapMs = 0))
    }

    @Test
    fun refusesWhatIsNotWav() {
        assertNull(WavJoiner.join(listOf(byteArrayOf(1, 2, 3)), gapMs = 0))
    }
}
