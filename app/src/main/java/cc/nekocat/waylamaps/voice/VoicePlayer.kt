package cc.nekocat.waylamaps.voice

import android.content.Context
import android.os.SystemClock
import android.util.Log
import cc.nekocat.waylamaps.character.CharacterRepository
import cc.nekocat.waylamaps.service.RidePreferences
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ServiceScoped
import javax.inject.Inject

/**
 * Speaks phrases from the phrase table (CLAUDE.md voice pipeline).
 *
 * Plays the selected character's pre-generated fragments (`voice/<language>/` in its pack) back
 * to back. If any fragment of a sentence is missing, or playback fails, the whole sentence goes
 * to system TTS instead, so a prompt is never silently dropped.
 */
@ServiceScoped
class VoicePlayer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val catalog: PhraseCatalog,
    private val tts: TtsSpeaker,
    private val audioFocus: AudioFocusController,
    private val characters: CharacterRepository,
    private val speechState: SpeechState,
    private val ridePreferences: RidePreferences,
) : PromptOutput {
    private val language = if (context.resources.configuration.locales[0].language == "zh") "zh-TW" else "en"
    private val scheduler = PromptScheduler(this, SystemClock::elapsedRealtime)

    /** What is being said, for the character UI. */
    private var speech: Speech? = null

    /** The prompt ExoPlayer is playing, with its text in case playback fails. */
    private var audioPrompt: Pair<Prompt, String>? = null

    private val player = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            /* handleAudioFocus = */ false,
        )
        .build()
        .apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        audioPrompt?.let { (prompt, _) -> finish(prompt) }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    val (prompt, text) = audioPrompt ?: return
                    Log.e(TAG, "Audio playback failed for ${prompt.phraseId}, using TTS", error)
                    audioPrompt = null
                    speakWithTts(prompt, text)
                }
            })
        }

    fun speak(phraseId: String, args: Map<String, Any> = emptyMap()) {
        submit(Prompt(phraseId, args, catalog.priorityOf(phraseId)))
    }

    fun speak(prompt: Prompt) = submit(prompt)

    /** Speaks [text] with the system TTS; [key] replaces a queued prompt with the same key. */
    fun speakText(key: String, text: String, priority: Priority) {
        submit(Prompt(key, priority = priority, text = text))
    }

    /**
     * Whether the rider's voice mode applies. Off for the settings screen's confirmations and
     * demos, which the rider asked to hear.
     */
    var followVoiceMode = true

    /** Drops what the rider's voice mode leaves out (see [VoiceMode]). */
    private fun submit(prompt: Prompt) {
        if (!followVoiceMode || ridePreferences.voiceMode.value.allows(prompt.priority)) scheduler.submit(prompt)
    }

    fun release() {
        scheduler.clear()
        player.release()
        tts.shutdown()
        audioFocus.release()
    }

    override fun play(prompt: Prompt) {
        if (prompt.text != null) {
            Log.i(TAG, "Playing ${prompt.phraseId} with TTS: ${prompt.text}")
            audioFocus.acquire()
            announce(prompt, prompt.text)
            speakWithTts(prompt, prompt.text)
            return
        }
        val resolved = try {
            catalog.resolve(prompt.phraseId, prompt.args, language)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Cannot resolve ${prompt.phraseId} ${prompt.args}", e)
            scheduler.onFinished(prompt)
            return
        }

        audioFocus.acquire()
        announce(prompt, resolved.text)
        val character = characters.selected.value
        if (resolved.fragmentIds.all { character.hasVoice(language, it) }) {
            Log.i(TAG, "Playing ${prompt.phraseId} from ${character.id} audio: ${resolved.fragmentIds}")
            audioPrompt = prompt to resolved.text
            player.setMediaItems(resolved.fragmentIds.map { MediaItem.fromUri(character.voiceUri(language, it)) })
            player.prepare()
            player.play()
        } else {
            Log.i(TAG, "Playing ${prompt.phraseId} with TTS: ${resolved.text}")
            speakWithTts(prompt, resolved.text)
        }
    }

    override fun stop() {
        speechState.finished(speech)
        speech = null
        audioPrompt = null
        player.stop()
        tts.stop()
    }

    override fun onIdle() = audioFocus.release()

    private fun speakWithTts(prompt: Prompt, text: String) {
        tts.speak(text) { finish(prompt) }
    }

    private fun announce(prompt: Prompt, text: String) {
        speechState.finished(speech)
        speech = Speech(text, prompt.priority, SystemClock.elapsedRealtime()).also { speechState.started(it) }
    }

    private fun finish(prompt: Prompt) {
        speechState.finished(speech)
        speech = null
        if (audioPrompt?.first === prompt) audioPrompt = null
        scheduler.onFinished(prompt)
    }

    private companion object {
        const val TAG = "VoicePlayer"
    }
}
