package cc.nekocat.waylamaps.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * System TTS announcer.
 *
 * Plays as navigation guidance over the media route (A2DP on a helmet headset, not SCO)
 * and ducks other audio while speaking.
 */
class TtsAnnouncer @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : Announcer {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()

    private val speaking = AtomicInteger(0)
    private val pending = ArrayDeque<String>()
    private var ready = false
    private var nextUtteranceId = 0

    private val tts = TextToSpeech(context, ::onInit)

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TextToSpeech init failed: $status")
            return
        }
        tts.setAudioAttributes(attributes)
        val locale = context.resources.configuration.locales[0]
        if (tts.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) {
            Log.w(TAG, "No TTS voice for $locale, using the engine default")
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finished()
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished()

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished()
            override fun onError(utteranceId: String?, errorCode: Int) = finished()
        })
        ready = true
        while (pending.isNotEmpty()) speak(pending.removeFirst())
    }

    override fun announce(text: String) {
        if (ready) speak(text) else pending.addLast(text)
    }

    override fun shutdown() {
        tts.stop()
        tts.shutdown()
        if (speaking.getAndSet(0) > 0) audioManager.abandonAudioFocusRequest(focusRequest)
    }

    private fun speak(text: String) {
        if (speaking.getAndIncrement() == 0) audioManager.requestAudioFocus(focusRequest)
        val result = tts.speak(text, TextToSpeech.QUEUE_ADD, null, "alert-${nextUtteranceId++}")
        if (result != TextToSpeech.SUCCESS) finished()
    }

    private fun finished() {
        if (speaking.updateAndGet { (it - 1).coerceAtLeast(0) } == 0) {
            audioManager.abandonAudioFocusRequest(focusRequest)
        }
    }

    private companion object {
        const val TAG = "TtsAnnouncer"
    }
}
