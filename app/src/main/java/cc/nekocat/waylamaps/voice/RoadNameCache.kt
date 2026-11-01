package cc.nekocat.waylamaps.voice

import android.content.Context
import android.util.Log
import cc.nekocat.waylamaps.character.RoadNameVoice
import cc.nekocat.waylamaps.voice.ondevice.OnDeviceSpeechSynthesizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Road names in a character's voice, made ahead of time and kept on the phone (CLAUDE.md voice
 * pipeline rule 2). The whole route's roads are fetched when guidance starts, so a prompt never
 * waits for one: a road not ready yet is simply left out of the sentence.
 */
@Singleton
class RoadNameCache @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val synthesizers: RoadNameSynthesizers,
) {
    private val root = File(context.filesDir, "road_names")
    private val mutex = Mutex()

    /** The clip for [name], or null if it has not been made yet. */
    fun clip(voice: RoadNameVoice, name: String): File? = file(voice, name).takeIf { it.exists() }

    /** Makes clips for the names not cached yet, in order; stops early when no synthesizer can. */
    suspend fun prefetch(voice: RoadNameVoice, names: Collection<String>) = mutex.withLock {
        for (name in names.map(String::trim).distinct()) {
            if (name.isEmpty() || name.length > MAX_NAME_LENGTH) continue
            val target = file(voice, name)
            if (target.exists()) continue
            val audio = synthesizers.synthesize(name, voice) ?: break
            withContext(Dispatchers.IO) {
                try {
                    target.parentFile?.mkdirs()
                    val temporary = File(target.parentFile, "${target.name}.tmp")
                    temporary.writeBytes(WavJoiner.trimSilence(audio))
                    temporary.renameTo(target)
                    Log.i(TAG, "Road name ready: $name")
                } catch (e: IOException) {
                    Log.w(TAG, "Could not store road name $name", e)
                }
            }
        }
    }

    private fun file(voice: RoadNameVoice, name: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(name.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(root, "${voice.voice}_${voice.language}/$digest.wav")
    }

    private companion object {
        const val TAG = "RoadNameCache"

        /** Longer "names" are whole instructions, not road names. */
        const val MAX_NAME_LENGTH = 24
    }
}

/**
 * The ways to make a road name clip, tried in order: the home server, whose voice also has BERT
 * features for natural phrasing, then the phone itself when the server cannot be reached.
 */
class RoadNameSynthesizers @Inject constructor(server: ServerSpeechSynthesizer, phone: OnDeviceSpeechSynthesizer) {
    private val all: List<SpeechSynthesizer> = listOf(server, phone)

    suspend fun synthesize(text: String, voice: RoadNameVoice): ByteArray? =
        all.firstNotNullOfOrNull { it.synthesize(text, voice) }
}
