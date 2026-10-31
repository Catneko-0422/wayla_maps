package cc.nekocat.waylamaps.voice

import android.content.Context
import java.io.IOException
import java.io.File
import android.net.Uri
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.os.SystemClock
import android.util.Log
import cc.nekocat.waylamaps.character.CharacterRepository
import cc.nekocat.waylamaps.service.RidePreferences
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.annotation.OptIn
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
    private val phoneLanguage = if (context.resources.configuration.locales[0].language == "zh") "zh-TW" else "en"
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

    // One audio session for recorded prompts and TTS, so a single enhancer boosts both.
    private val audioSessionId = context.getSystemService(AudioManager::class.java).generateAudioSessionId()
    private val enhancer: LoudnessEnhancer? = try {
        LoudnessEnhancer(audioSessionId)
    } catch (e: RuntimeException) {
        Log.w(TAG, "No loudness boost on this device", e)
        null
    }

    init {
        attachAudioSession()
        tts.audioSessionId = audioSessionId
    }

    // A stable call since ExoPlayer 2; Media3 still marks it unstable.
    @OptIn(UnstableApi::class)
    private fun attachAudioSession() = player.setAudioSessionId(audioSessionId)

    /** Reads the boost setting before each prompt, so a change applies to the next one. */
    private fun applyBoost() {
        val enhancer = enhancer ?: return
        val gain = ridePreferences.voiceBoost.value.gainMillibels
        try {
            enhancer.setTargetGain(gain)
            enhancer.enabled = gain > 0
        } catch (e: RuntimeException) {
            Log.w(TAG, "Loudness boost failed", e)
        }
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
        enhancer?.release()
        tts.shutdown()
        audioFocus.release()
    }

    override fun play(prompt: Prompt) {
        applyBoost()
        if (prompt.text != null) {
            Log.i(TAG, "Playing ${prompt.phraseId} with TTS: ${prompt.text}")
            audioFocus.acquire()
            announce(prompt, prompt.text)
            speakWithTts(prompt, prompt.text)
            return
        }
        val character = characters.selected.value
        val language = character.manifest.voiceLanguage?.takeIf { it in catalog.languages } ?: phoneLanguage
        val resolved = try {
            catalog.resolve(prompt.phraseId, prompt.args, language)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Cannot resolve ${prompt.phraseId} ${prompt.args}", e)
            scheduler.onFinished(prompt)
            return
        }

        audioFocus.acquire()
        announce(prompt, resolved.text)
        if (resolved.fragmentIds.all { character.hasVoice(language, it) }) {
            Log.i(TAG, "Playing ${prompt.phraseId} from ${character.id} audio: ${resolved.fragmentIds}")
            audioPrompt = prompt to resolved.text
            val uris = resolved.fragmentIds.map { character.voiceUri(language, it) }
            val joined = if (uris.size > 1) joinClips(uris) else null
            player.setMediaItems(if (joined != null) listOf(MediaItem.fromUri(joined)) else uris.map(MediaItem::fromUri))
            player.prepare()
            player.play()
        } else {
            Log.i(TAG, "Playing ${prompt.phraseId} with TTS: ${resolved.text}")
            speakWithTts(prompt, resolved.text, language)
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

    private var nextJoinedFile = 0

    /**
     * The fragments as one WAV in the cache (see [WavJoiner]), or null to play them as a
     * playlist. Two files take turns, so the one still playing is never overwritten.
     */
    private fun joinClips(uris: List<String>): Uri? = try {
        val clips = uris.map { uri ->
            if (uri.startsWith(ASSET_URI)) {
                context.assets.open(uri.removePrefix(ASSET_URI)).use { it.readBytes() }
            } else {
                File(requireNotNull(Uri.parse(uri).path)).readBytes()
            }
        }
        WavJoiner.join(clips, FRAGMENT_GAP_MS)?.let { bytes ->
            val file = File(context.cacheDir, "prompt-${nextJoinedFile++ % 2}.wav")
            file.writeBytes(bytes)
            Uri.fromFile(file)
        }
    } catch (e: IOException) {
        Log.w(TAG, "Could not join voice fragments, playing them one by one", e)
        null
    }

    /** [language] is a phrase table language; null speaks in the phone's language. */
    private fun speakWithTts(prompt: Prompt, text: String, language: String? = null) {
        tts.speak(text, language?.takeIf { it != phoneLanguage }) { finish(prompt) }
    }

    private fun announce(prompt: Prompt, text: String) {
        speechState.finished(speech)
        speech = Speech(text, prompt.priority, SystemClock.elapsedRealtime(), prompt.phraseId).also { speechState.started(it) }
    }

    private fun finish(prompt: Prompt) {
        speechState.finished(speech)
        speech = null
        if (audioPrompt?.first === prompt) audioPrompt = null
        scheduler.onFinished(prompt)
    }

    private companion object {
        const val TAG = "VoicePlayer"
        const val ASSET_URI = "asset:///"

        /** The pause between joined fragments, about a comma's worth. */
        const val FRAGMENT_GAP_MS = 90
    }
}
