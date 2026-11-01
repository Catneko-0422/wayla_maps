package cc.nekocat.waylamaps.voice.ondevice

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Half
import android.util.Log
import cc.nekocat.waylamaps.character.Character
import cc.nekocat.waylamaps.character.CharacterRepository
import cc.nekocat.waylamaps.character.CharacterSource
import cc.nekocat.waylamaps.character.RoadNameVoice
import cc.nekocat.waylamaps.voice.SpeechSynthesizer
import cc.nekocat.waylamaps.voice.WavJoiner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Says road names with the selected character's own voice model on the phone, for when the
 * home server is out of reach. Works for a pack with an `ondevice/` folder (see
 * tools/voice/export_ondevice.py) and Chinese road names.
 *
 * The model is unpacked into app storage on first use, its fp16 weights widened to the fp32 the
 * graphs read, and is kept in memory only while names are being made.
 */
@Singleton
class OnDeviceSpeechSynthesizer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val characters: CharacterRepository,
) : SpeechSynthesizer {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loaded: Pair<String, GenieVoice>? = null
    private var release: Job? = null

    private val g2p: ChineseG2P by lazy {
        val open = { name: String -> context.assets.open("$G2P_DIR/$name") }
        ChineseG2P(open("symbols.txt"), open("opencpop.txt"), open("chars.txt"), open("words.txt"), open("t2s.txt"))
    }

    override suspend fun synthesize(text: String, voice: RoadNameVoice): ByteArray? {
        val character = characters.selected.value
        if (character.manifest.roadNames != voice || voice.language != "zh") return null
        return mutex.withLock {
            withContext(Dispatchers.Default) {
                try {
                    val genie = load(character) ?: return@withContext null
                    val started = System.nanoTime()
                    val pcm = genie.synthesize(text)
                    Log.i(TAG, "Made \"$text\" on the phone in ${(System.nanoTime() - started) / 1_000_000} ms")
                    pcm?.let { WavJoiner.encode(it, genie.sampleRate) }
                } catch (e: Exception) {
                    // ONNX Runtime reports failures as OrtException; nothing here may stop the ride.
                    Log.w(TAG, "On-device speech failed for $text", e)
                    null
                } finally {
                    scheduleRelease()
                }
            }
        }
    }

    private fun load(character: Character): GenieVoice? {
        loaded?.let { (id, genie) -> if (id == character.id) return genie }
        unload()
        val dir = install(character) ?: return null
        val started = System.nanoTime()
        return GenieVoice(dir, g2p).also {
            loaded = character.id to it
            Log.i(TAG, "Loaded ${character.id}'s voice in ${(System.nanoTime() - started) / 1_000_000} ms")
        }
    }

    private fun unload() {
        loaded?.second?.close()
        loaded = null
    }

    private fun scheduleRelease() {
        release?.cancel()
        release = scope.launch {
            delay(IDLE_RELEASE_MS)
            mutex.withLock { unload() }
        }
    }

    /**
     * The pack's model in app storage, copied (and widened) once per app version. Null when the
     * pack has none.
     */
    private fun install(character: Character): File? {
        val source = PackFiles.of(context, character) ?: return null
        val manifestText = source.open(OnDeviceManifest.FILE_NAME)?.use { it.readBytes().decodeToString() } ?: return null
        val manifest = OnDeviceManifest.parse(manifestText)
        val target = File(context.filesDir, "ondevice/${character.id}")
        val stamp = File(target, ".installed")
        val version = "${manifestText.hashCode()}-${source.version}"
        if (stamp.exists() && stamp.readText() == version) return target

        Log.i(TAG, "Unpacking ${character.id}'s voice model")
        target.deleteRecursively()
        target.mkdirs()
        val widened = manifest.widen.values.toSet()
        try {
            for (name in source.list()) {
                if (name in widened) continue
                source.open(name)?.use { input -> File(target, name).outputStream().use { input.copyTo(it) } }
            }
            for ((fp32, fp16) in manifest.widen) {
                source.open(fp16)?.use { widen(it, File(target, fp32)) } ?: return null
            }
        } catch (e: IOException) {
            Log.w(TAG, "Could not unpack ${character.id}'s voice model", e)
            target.deleteRecursively()
            return null
        }
        stamp.writeText(version)
        return target
    }

    /** fp16 little-endian values to fp32, element by element, so the graphs' offsets stay valid. */
    @SuppressLint("HalfFloat") // Widening the raw halves to float is the point here.
    private fun widen(input: InputStream, output: File) {
        val inBuffer = ByteArray(CHUNK)
        val outBuffer = ByteBuffer.allocate(CHUNK * 2).order(ByteOrder.LITTLE_ENDIAN)
        output.outputStream().buffered().use { out ->
            while (true) {
                val read = input.readNBytesCompat(inBuffer)
                if (read <= 0) break
                outBuffer.clear()
                val halves = ByteBuffer.wrap(inBuffer, 0, read).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                while (halves.hasRemaining()) outBuffer.putFloat(Half.toFloat(halves.get()))
                out.write(outBuffer.array(), 0, outBuffer.position())
            }
        }
    }

    /** Fills [buffer] as far as the stream allows (an even count, as halves are two bytes). */
    private fun InputStream.readNBytesCompat(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        return total
    }

    /** The files in a pack's `ondevice/` folder, bundled in assets or imported. */
    private class PackFiles(
        val version: String,
        val list: () -> List<String>,
        val open: (String) -> InputStream?,
    ) {
        companion object {
            fun of(context: Context, character: Character): PackFiles? = when (character.source) {
                CharacterSource.BUNDLED -> {
                    val dir = "characters/${character.id}/$PACK_DIR"
                    val files = context.assets.list(dir).orEmpty().toList()
                    if (files.isEmpty()) {
                        null
                    } else {
                        val installed = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
                        PackFiles(installed.toString(), { files }) { name ->
                            try {
                                context.assets.open("$dir/$name")
                            } catch (e: IOException) {
                                null
                            }
                        }
                    }
                }
                CharacterSource.IMPORTED -> {
                    val dir = File(requireNotNull(Uri.parse(character.baseUri).path), PACK_DIR)
                    if (!dir.isDirectory) {
                        null
                    } else {
                        PackFiles(dir.lastModified().toString(), { dir.list().orEmpty().toList() }) { name ->
                            File(dir, name).takeIf { it.isFile }?.inputStream()
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "OnDeviceSpeech"
        const val G2P_DIR = "g2p_zh"
        const val PACK_DIR = "ondevice"
        const val IDLE_RELEASE_MS = 60_000L
        const val CHUNK = 1 shl 20
    }
}
