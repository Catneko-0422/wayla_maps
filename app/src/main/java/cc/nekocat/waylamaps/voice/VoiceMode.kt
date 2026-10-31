package cc.nekocat.waylamaps.voice

/** How much the voice says during guidance, switched from the guidance screen. */
enum class VoiceMode {
    /** Every prompt, including traffic and the character's chatter. */
    ALL,

    /** Turns and speed cameras only. */
    ESSENTIAL,

    /** Nothing; the turn card still shows each instruction. */
    MUTED,
    ;

    fun allows(priority: Priority): Boolean = when (this) {
        ALL -> true
        ESSENTIAL -> priority >= Priority.SPEED_CAMERA
        MUTED -> false
    }

    /** The next mode for a single button that cycles through all three. */
    fun next(): VoiceMode = entries[(ordinal + 1) % entries.size]
}
