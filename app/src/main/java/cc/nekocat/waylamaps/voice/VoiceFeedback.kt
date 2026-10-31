package cc.nekocat.waylamaps.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Speaks confirmations for settings changed on screen (travel mode, voice demo), whether or not
 * the ride service is running. Its player is released after a short idle period.
 */
@Singleton
class VoiceFeedback @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val catalog: PhraseCatalog,
    private val tts: Provider<TtsSpeaker>,
    private val audioFocus: Provider<AudioFocusController>,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var player: VoicePlayer? = null
    private val release = Runnable {
        player?.release()
        player = null
    }

    /** Call on the main thread. */
    fun speak(phraseId: String) {
        val current = player ?: VoicePlayer(context, catalog, tts.get(), audioFocus.get()).also { player = it }
        current.speak(phraseId)
        handler.removeCallbacks(release)
        handler.postDelayed(release, IDLE_RELEASE_MS)
    }

    private companion object {
        const val IDLE_RELEASE_MS = 30_000L
    }
}
