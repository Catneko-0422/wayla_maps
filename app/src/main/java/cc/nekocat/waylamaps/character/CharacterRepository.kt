package cc.nekocat.waylamaps.character

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import cc.nekocat.waylamaps.voice.PhraseCatalog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The characters the rider can choose from: bundled packs in `assets/characters/` (licensed
 * ones come from the uncommitted `src/local` source set) and packs imported into app storage.
 */
@Singleton
class CharacterRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val catalog: PhraseCatalog,
) {
    private val prefs = context.getSharedPreferences("characters", Context.MODE_PRIVATE)
    private val importedDir = File(context.filesDir, "characters")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _characters = MutableStateFlow(scanBlocking())
    val characters: StateFlow<List<Character>> = _characters.asStateFlow()

    private val selectedId = MutableStateFlow(prefs.getString(KEY_SELECTED, null) ?: DEFAULT_ID)

    /** The chosen character, or the first available one if it was deleted. */
    val selected: StateFlow<Character> = combine(_characters, selectedId) { list, id ->
        list.firstOrNull { it.id == id } ?: list.first()
    }.stateIn(scope, SharingStarted.Eagerly, _characters.value.let { list -> list.firstOrNull { it.id == selectedId.value } ?: list.first() })

    fun select(id: String) {
        selectedId.value = id
        prefs.edit { putString(KEY_SELECTED, id) }
    }

    suspend fun import(uri: Uri): CharacterPackImporter.Result = withContext(Dispatchers.IO) {
        importedDir.mkdirs()
        val importer = CharacterPackImporter(
            charactersDir = importedDir,
            reservedIds = _characters.value.filter { it.source == CharacterSource.BUNDLED }.map { it.id }.toSet(),
            knownFragments = catalog.fragmentIds,
        )
        val result = try {
            context.contentResolver.openInputStream(uri)?.use { importer.import(it) }
                ?: CharacterPackImporter.Result.Failed(CharacterPackImporter.Reason.IO_ERROR)
        } catch (e: IOException) {
            Log.w(TAG, "Cannot read $uri", e)
            CharacterPackImporter.Result.Failed(CharacterPackImporter.Reason.IO_ERROR)
        }
        Log.i(TAG, "Import: $result")
        _characters.value = scanBlocking()
        result
    }

    fun delete(character: Character) {
        if (character.source != CharacterSource.IMPORTED) return
        scope.launch {
            File(importedDir, character.id).deleteRecursively()
            _characters.value = scanBlocking()
        }
    }

    /** Opens the character's model file. */
    fun openModel(character: Character): ByteArray? {
        val file = character.manifest.model?.file ?: return null
        return try {
            when (character.source) {
                CharacterSource.BUNDLED -> context.assets.open("$ASSET_DIR/${character.id}/$file").use { it.readBytes() }
                CharacterSource.IMPORTED -> File(importedDir, "${character.id}/$file").readBytes()
            }
        } catch (e: IOException) {
            Log.w(TAG, "No model for ${character.id}", e)
            null
        }
    }

    private fun scanBlocking(): List<Character> {
        val bundled = context.assets.list(ASSET_DIR).orEmpty().sorted().mapNotNull { id -> bundledCharacter(id) }
        val imported = importedDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty()
            .sortedBy { it.name }
            .mapNotNull { importedCharacter(it) }
        return (bundled + imported).ifEmpty { listOf(fallback()) }
    }

    private fun bundledCharacter(id: String): Character? = try {
        val manifest = CharacterManifest.parse(
            context.assets.open("$ASSET_DIR/$id/${CharacterManifest.FILE_NAME}").bufferedReader().use { it.readText() },
        )
        val voice = context.assets.list("$ASSET_DIR/$id/voice").orEmpty().associateWith { language ->
            context.assets.list("$ASSET_DIR/$id/voice/$language").orEmpty()
                .filter { it.endsWith(".wav") }.map { it.removeSuffix(".wav") }.toSet()
        }
        Character(manifest, CharacterSource.BUNDLED, voice, "asset:///$ASSET_DIR/$id/")
    } catch (e: Exception) {
        Log.w(TAG, "Skipping bundled character $id", e)
        null
    }

    private fun importedCharacter(dir: File): Character? = try {
        val manifest = CharacterManifest.parse(File(dir, CharacterManifest.FILE_NAME).readText())
        val voice = File(dir, "voice").listFiles { f -> f.isDirectory }.orEmpty().associate { language ->
            language.name to language.listFiles { f -> f.extension == "wav" }.orEmpty().map { it.nameWithoutExtension }.toSet()
        }
        Character(manifest, CharacterSource.IMPORTED, voice, Uri.fromFile(dir).toString() + "/")
    } catch (e: Exception) {
        Log.w(TAG, "Skipping imported character ${dir.name}", e)
        null
    }

    private fun fallback() = Character(CharacterManifest(DEFAULT_ID, "Wayla"), CharacterSource.BUNDLED, emptyMap(), "asset:///$ASSET_DIR/$DEFAULT_ID/")

    private companion object {
        const val TAG = "CharacterRepository"
        const val ASSET_DIR = "characters"
        const val KEY_SELECTED = "selected"
        const val DEFAULT_ID = "wayla"
    }
}
