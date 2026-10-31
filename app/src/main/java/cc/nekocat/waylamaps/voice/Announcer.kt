package cc.nekocat.waylamaps.voice

/** Speaks prompts to the rider. Will gain a GPT-SoVITS implementation with TTS fallback. */
interface Announcer {
    fun announce(text: String)

    fun shutdown()
}
