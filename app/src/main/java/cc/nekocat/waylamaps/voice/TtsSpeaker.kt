package cc.nekocat.waylamaps.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * System TTS, the fallback when a prompt has no pre-generated audio.
 *
 * [speak]'s callback runs on the main thread once the text is spoken, stopped or failed,
 * so a broken engine never leaves the prompt queue waiting.
 */
class TtsSpeaker @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()
    private val pending = ArrayDeque<Pair<String, () -> Unit>>()
    private var state = State.INITIALIZING
    private var nextId = 0

    private enum class State { INITIALIZING, READY, FAILED }

    private val tts = TextToSpeech(context, ::onInit)

    fun speak(text: String, onDone: () -> Unit) {
        when (state) {
            State.INITIALIZING -> pending.addLast(text to onDone)
            State.READY -> speakNow(text, onDone)
            State.FAILED -> onDone()
        }
    }

    fun stop() {
        pending.clear()
        tts.stop()
    }

    fun shutdown() {
        pending.clear()
        tts.shutdown()
        callbacks.clear()
    }

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TextToSpeech init failed: $status")
            state = State.FAILED
            while (pending.isNotEmpty()) pending.removeFirst().second()
            return
        }
        tts.setAudioAttributes(PROMPT_AUDIO_ATTRIBUTES)
        val locale = context.resources.configuration.locales[0]
        if (tts.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) {
            Log.w(TAG, "No TTS voice for $locale, using the engine default")
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finished(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId)
        })
        state = State.READY
        while (pending.isNotEmpty()) pending.removeFirst().let { (text, onDone) -> speakNow(text, onDone) }
    }

    private fun speakNow(text: String, onDone: () -> Unit) {
        val id = "prompt-${nextId++}"
        callbacks[id] = onDone
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) finished(id)
    }

    private fun finished(utteranceId: String?) {
        val callback = utteranceId?.let { callbacks.remove(it) } ?: return
        mainHandler.post(callback)
    }

    private companion object {
        const val TAG = "TtsSpeaker"
    }
}
