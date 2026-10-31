package cc.nekocat.waylamaps.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Prompts play as navigation guidance on the media route (A2DP on a helmet headset, not SCO). */
val PROMPT_AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

/**
 * Holds transient, ducking audio focus while prompts play.
 *
 * Kept for a whole run of prompts rather than per prompt, so music ducks once instead of
 * pumping between sentences.
 */
class AudioFocusController @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(PROMPT_AUDIO_ATTRIBUTES)
        .build()
    private var held = false

    fun acquire() {
        if (held) return
        audioManager.requestAudioFocus(request)
        held = true
    }

    fun release() {
        if (!held) return
        audioManager.abandonAudioFocusRequest(request)
        held = false
    }
}
