package cc.nekocat.waylamaps.voice

/**
 * Extra loudness for prompts on top of the phone's media volume, for wind noise in a helmet or
 * a loud cabin. Applied with a LoudnessEnhancer, so it lifts quiet speech more than it clips.
 */
enum class VoiceBoost(val gainMillibels: Int) {
    NORMAL(0),
    LOUD(600),
    LOUDEST(1200),
}
