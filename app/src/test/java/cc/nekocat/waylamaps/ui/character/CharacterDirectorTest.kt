package cc.nekocat.waylamaps.ui.character

import cc.nekocat.waylamaps.voice.Priority
import cc.nekocat.waylamaps.voice.Speech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CharacterDirectorTest {
    private fun speech(id: String, at: Long) = Speech("…", Priority.TURN, at, id)

    @Test
    fun arrivalIsAJoyfulSalute() {
        assertEquals(Reaction(CharacterMood.JOY, CharacterMotion.SALUTE), CharacterDirector.reactionFor("arrived"))
    }

    @Test
    fun alertsAreSeriousWithoutGestures() {
        assertEquals(Reaction(CharacterMood.SERIOUS), CharacterDirector.reactionFor("alert_ahead_context_limit"))
        assertEquals(Reaction(CharacterMood.SERIOUS), CharacterDirector.reactionFor("average_speed_enter"))
    }

    @Test
    fun turnsAndFreeTextStayNeutral() {
        assertEquals(Reaction(CharacterMood.NEUTRAL), CharacterDirector.reactionFor("turn_ahead"))
        assertEquals(Reaction(CharacterMood.NEUTRAL), CharacterDirector.reactionFor(null))
    }

    @Test
    fun moodOutlastsTheSentenceBriefly() {
        val director = CharacterDirector(holdMs = 1_000)
        val arrived = speech("arrived", at = 100)

        val speaking = director.update(150, arrived)
        assertEquals(CharacterMood.JOY, speaking.mood)
        assertEquals(CharacterMotion.SALUTE, speaking.gesture)
        assertEquals(100L, speaking.gestureStartMs)
        assertEquals(100L, speaking.speakingSinceMs)

        assertEquals(CharacterMood.JOY, director.update(2_000, null).mood)
        assertNull(director.update(2_500, null).speakingSinceMs)
        assertEquals(CharacterMood.NEUTRAL, director.update(3_100, null).mood)
    }

    @Test
    fun aNewSentenceBringsANewReaction() {
        val director = CharacterDirector()
        director.update(0, speech("arrived", at = 0))

        val next = director.update(500, speech("over_limit", at = 500))
        assertEquals(CharacterMood.WORRIED, next.mood)
        assertNull(next.gesture)
    }
}
