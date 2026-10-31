package cc.nekocat.waylamaps.ui.character

import cc.nekocat.waylamaps.ui.character.FaceAnimator.Morph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceAnimatorTest {
    private val face = FaceAnimator()
    private val vowels = listOf(Morph.A, Morph.I, Morph.U, Morph.E, Morph.O)

    @Test
    fun mouthRestsWhileSilent() {
        for (t in 1_000L..3_000L step 37) {
            val weights = face.weights(t, speakingSinceMs = null)
            assertTrue(vowels.all { weights.getValue(it) == 0f })
        }
    }

    @Test
    fun mouthMovesWhileSpeaking() {
        val open = (10_000L..12_000L step 20).map { t -> vowels.sumOf { face.weights(t, 10_000L).getValue(it).toDouble() } }
        assertTrue(open.all { it > 0 })
        // One vowel at a time.
        val counts = (10_000L..12_000L step 20).map { t -> vowels.count { face.weights(t, 10_000L).getValue(it) > 0f } }
        assertEquals(setOf(1), counts.toSet())
    }

    @Test
    fun blinksBriefly() {
        val closed = (0L..20_000L step 10).count { face.weights(it, null).getValue(Morph.BLINK) > 0.5f }
        // A few blinks of about a tenth of a second each in 20 s.
        assertTrue("closed for $closed samples", closed in 20..80)
    }
}
