package cc.nekocat.waylamaps.voice

import android.content.Context
import android.os.Bundle
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
    private val pending = ArrayDeque<Request>()
    private var state = State.INITIALIZING
    private var nextId = 0

    private enum class State { INITIALIZING, READY, FAILED }

    private data class Request(val text: String, val onDone: () -> Unit, val isRetry: Boolean = false)

    private var tts = TextToSpeech(context, ::onInit)

    /** The audio session to speak in, shared with the recorded prompts' loudness boost. */
    var audioSessionId: Int? = null

    fun speak(text: String, onDone: () -> Unit) = submit(Request(text, onDone))

    private fun submit(request: Request) {
        when (state) {
            State.INITIALIZING -> pending.addLast(request)
            State.READY -> speakNow(request)
            State.FAILED -> request.onDone()
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
            while (pending.isNotEmpty()) pending.removeFirst().onDone()
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
        while (pending.isNotEmpty()) speakNow(pending.removeFirst())
    }

    private fun speakNow(request: Request) {
        val id = "prompt-${nextId++}"
        callbacks[id] = request.onDone
        val params = Bundle().apply { audioSessionId?.let { putInt(TextToSpeech.Engine.KEY_PARAM_SESSION_ID, it) } }
        if (tts.speak(request.text, TextToSpeech.QUEUE_FLUSH, params, id) == TextToSpeech.SUCCESS) return

        callbacks.remove(id)
        if (request.isRetry) {
            Log.e(TAG, "TTS failed again after reconnecting, giving up on: ${request.text}")
            mainHandler.post(request.onDone)
            return
        }
        // The engine process died (killed, updated); this TextToSpeech never reconnects by itself.
        Log.w(TAG, "TTS speak failed, reconnecting to the engine")
        tts.shutdown()
        state = State.INITIALIZING
        pending.addFirst(request.copy(isRetry = true))
        tts = TextToSpeech(context, ::onInit)
    }

    private fun finished(utteranceId: String?) {
        val callback = utteranceId?.let { callbacks.remove(it) } ?: return
        mainHandler.post(callback)
    }

    private companion object {
        const val TAG = "TtsSpeaker"
    }
}
