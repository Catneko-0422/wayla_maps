package cc.nekocat.waylamaps.voice

import android.os.SystemClock
import android.util.Log
import cc.nekocat.waylamaps.BuildConfig
import cc.nekocat.waylamaps.character.RoadNameVoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

/** Turns a short text into WAV audio in a character's voice. */
interface SpeechSynthesizer {
    /** Null when this synthesizer cannot say it now (offline, not configured, failed). */
    suspend fun synthesize(text: String, voice: RoadNameVoice): ByteArray?
}

/**
 * The home speech server (CLAUDE.md voice pipeline rule 2), reached on the home network or over
 * Tailscale. Configured with TTS_SERVER_URL and TTS_API_KEY in local.properties.
 */
class ServerSpeechSynthesizer @Inject constructor() : SpeechSynthesizer {
    private val serverUrl = BuildConfig.TTS_SERVER_URL.trim().trimEnd('/')

    /** After a failed connection the server is left alone a while, so the phone takes over at once. */
    @Volatile
    private var unreachableUntilMs = 0L

    override suspend fun synthesize(text: String, voice: RoadNameVoice): ByteArray? {
        if (serverUrl.isEmpty() || SystemClock.elapsedRealtime() < unreachableUntilMs) return null
        return withContext(Dispatchers.IO) {
            try {
                val connection = URL("$serverUrl/v1/audio/speech").openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = CONNECT_TIMEOUT_MS
                    // The server may first have to move its GPU over from another job.
                    connection.readTimeout = READ_TIMEOUT_MS
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    BuildConfig.TTS_API_KEY.takeIf { it.isNotBlank() }?.let {
                        connection.setRequestProperty("Authorization", "Bearer $it")
                    }
                    val body = buildJsonObject {
                        put("input", text)
                        put("voice", voice.voice)
                        put("language", voice.language)
                    }
                    connection.outputStream.use { it.write(Json.encodeToString(body).toByteArray()) }
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        Log.w(TAG, "Speech server answered ${connection.responseCode} for $text")
                        return@withContext null
                    }
                    connection.inputStream.use { it.readBytes() }
                } finally {
                    connection.disconnect()
                }
            } catch (e: IOException) {
                Log.i(TAG, "Speech server unreachable: ${e.message}")
                unreachableUntilMs = SystemClock.elapsedRealtime() + RETRY_AFTER_MS
                null
            }
        }
    }

    private companion object {
        const val TAG = "ServerSpeech"
        const val CONNECT_TIMEOUT_MS = 3_000
        const val READ_TIMEOUT_MS = 60_000
        const val RETRY_AFTER_MS = 120_000L
    }
}
