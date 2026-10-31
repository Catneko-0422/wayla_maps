package cc.nekocat.waylamaps.ui.character

import kotlin.math.PI
import kotlin.math.sin

/**
 * Morph weights for a talking, blinking face, from the time alone (no audio analysis): while
 * speaking the mouth moves through the vowels in a steady rhythm; otherwise it rests closed.
 *
 * Pure Kotlin; [weights] is called once per frame.
 */
class FaceAnimator(
    private val blinkEveryMs: Long = 4_000,
    private val blinkMs: Long = 150,
    private val syllableMs: Long = 140,
) {
    enum class Morph { BLINK, A, I, U, E, O }

    private val vowels = listOf(Morph.A, Morph.I, Morph.U, Morph.E, Morph.O)

    /** @param speakingSinceMs when the current sentence started, or null when silent. */
    fun weights(nowMs: Long, speakingSinceMs: Long?): Map<Morph, Float> {
        val result = Morph.entries.associateWith { 0f }.toMutableMap()

        // Blink: a quick close and open, with a little irregularity so it does not look mechanical.
        val cycle = blinkEveryMs + (nowMs / blinkEveryMs % 3) * 700
        val inCycle = nowMs % cycle
        if (inCycle < blinkMs) result[Morph.BLINK] = sin(PI * inCycle / blinkMs).toFloat()

        if (speakingSinceMs != null) {
            val elapsed = (nowMs - speakingSinceMs).coerceAtLeast(0)
            val syllable = elapsed / syllableMs
            // A fixed but varied vowel sequence, opening and closing within each syllable.
            val vowel = vowels[((syllable * 7 + 3) % vowels.size).toInt()]
            val phase = (elapsed % syllableMs).toDouble() / syllableMs
            result[vowel] = (0.35 + 0.65 * sin(PI * phase)).toFloat()
        }
        return result
    }
}
