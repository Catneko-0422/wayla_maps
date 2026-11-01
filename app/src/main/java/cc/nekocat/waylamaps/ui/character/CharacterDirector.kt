package cc.nekocat.waylamaps.ui.character

import cc.nekocat.waylamaps.voice.Speech

/** The character's face beyond talking and blinking; keys match a pack's "expressions". */
enum class CharacterMood {
    NEUTRAL,
    HAPPY,
    JOY,
    SERIOUS,
    WORRIED,
    OOPS,
    SURPRISED,
    SLEEPY,
    ;

    val key: String get() = name.lowercase()
}

/** Body motions; keys match a pack's "motions". IDLE loops, TALK loops while speaking. */
enum class CharacterMotion {
    IDLE,
    TALK,
    NOD,
    TILT,
    WAVE,
    SALUTE,
    CHEER,
    ;

    val key: String get() = name.lowercase()
}

/** How the character reacts to one sentence: a mood held while she says it, and maybe a gesture. */
data class Reaction(val mood: CharacterMood, val gesture: CharacterMotion? = null)

/**
 * Picks a reaction for what is being said and keeps it a little after the sentence ends, so a
 * smile does not snap off with the last syllable.
 *
 * Pure Kotlin; [update] is called once per frame.
 */
class CharacterDirector(private val holdMs: Long = 2_500) {
    data class State(
        val mood: CharacterMood,
        /** The gesture being played and when it started, or null. */
        val gesture: CharacterMotion?,
        val gestureStartMs: Long,
        /** When the current sentence started, or null when silent. */
        val speakingSinceMs: Long?,
    )

    private var speech: Speech? = null
    private var reaction = Reaction(CharacterMood.NEUTRAL)
    private var endedAtMs: Long? = null

    fun update(nowMs: Long, current: Speech?): State {
        if (current != null && current != speech) {
            speech = current
            reaction = reactionFor(current.phraseId)
            endedAtMs = null
        }
        if (current == null && speech != null && endedAtMs == null) endedAtMs = nowMs
        val ended = endedAtMs
        val mood = if (ended != null && nowMs - ended > holdMs) CharacterMood.NEUTRAL else reaction.mood
        return State(mood, reaction.gesture, speech?.startedAtMs ?: 0, current?.startedAtMs)
    }

    companion object {
        /** Calm and factual for alerts; warm for arrivals and confirmations. */
        fun reactionFor(phraseId: String?): Reaction {
            val id = phraseId ?: return Reaction(CharacterMood.NEUTRAL)
            return when {
                id == "arrived" -> Reaction(CharacterMood.JOY, CharacterMotion.SALUTE)
                id == "detect_start" -> Reaction(CharacterMood.HAPPY, CharacterMotion.NOD)
                id == "voice_demo" -> Reaction(CharacterMood.HAPPY, CharacterMotion.WAVE)
                id == "stop_reached" || id == "point_added" -> Reaction(CharacterMood.HAPPY, CharacterMotion.NOD)
                id == "gps_recovered" || id == "gps_enabled" || id == "doze_end" -> Reaction(CharacterMood.HAPPY, CharacterMotion.CHEER)
                id == "detect_stop" || id == "passed" || id == "point_deleted" -> Reaction(CharacterMood.NEUTRAL, CharacterMotion.NOD)
                id.startsWith("mode_") || id.startsWith("elevation_") || id.startsWith("lock_") ->
                    Reaction(CharacterMood.NEUTRAL, CharacterMotion.NOD)
                id == "point_add_failed" -> Reaction(CharacterMood.OOPS, CharacterMotion.TILT)
                id == "rerouting" -> Reaction(CharacterMood.SURPRISED, CharacterMotion.TILT)
                id == "over_limit" -> Reaction(CharacterMood.WORRIED)
                id in setOf("gps_lost", "gps_disabled", "no_fix", "no_internet") -> Reaction(CharacterMood.WORRIED, CharacterMotion.TILT)
                id == "doze_begin" || id == "stationary" -> Reaction(CharacterMood.SLEEPY)
                id.startsWith("alert_") || id.startsWith("average_speed") || id == "parking_zone" -> Reaction(CharacterMood.SERIOUS)
                else -> Reaction(CharacterMood.NEUTRAL)
            }
        }
    }
}
