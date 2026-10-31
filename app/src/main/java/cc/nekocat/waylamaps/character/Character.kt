package cc.nekocat.waylamaps.character

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `character.json` at the root of a character pack. A pack is a folder (bundled under
 * `assets/characters/<id>/`, or imported into the app's files) holding this file, the voice
 * fragments in `voice/<language>/<fragment id>.wav`, and optionally a 3D model.
 */
@Serializable
data class CharacterManifest(
    val id: String,
    val name: String,
    /** Shown on the about screen, e.g. "MANUKA ©ジンゴ". */
    val credit: String? = null,
    val model: ModelSpec? = null,
) {
    companion object {
        const val FILE_NAME = "character.json"
        private val ID = Regex("[a-z0-9_-]{1,40}")
        private val json = Json { ignoreUnknownKeys = true }

        /** @throws IllegalArgumentException when the JSON or the id is invalid. */
        fun parse(text: String): CharacterManifest {
            val manifest = try {
                json.decodeFromString<CharacterManifest>(text)
            } catch (e: kotlinx.serialization.SerializationException) {
                throw IllegalArgumentException("Invalid character.json", e)
            }
            require(ID.matches(manifest.id)) { "Invalid character id ${manifest.id}" }
            require(manifest.name.isNotBlank()) { "Character has no name" }
            return manifest
        }
    }
}

/** A glTF binary model and the morph targets (shape keys) used for expressions. */
@Serializable
data class ModelSpec(
    val file: String = "model.glb",
    val morphs: Morphs = Morphs(),
    /** Height of the camera target above the model origin, in meters (about chest height). */
    val focusHeight: Float = 1.2f,
    /** Distance of the camera from the target, in meters. */
    val cameraDistance: Float = 1.1f,
)

@Serializable
data class Morphs(
    val blink: String? = null,
    val a: String? = null,
    val i: String? = null,
    val u: String? = null,
    val e: String? = null,
    val o: String? = null,
    val joy: String? = null,
)

enum class CharacterSource { BUNDLED, IMPORTED }

/** A character the app found, with where its files are. */
data class Character(
    val manifest: CharacterManifest,
    val source: CharacterSource,
    /** Recorded fragment ids per language. */
    val voiceFragments: Map<String, Set<String>>,
    /** Base URI that the pack's relative paths resolve against, ending in "/". */
    val baseUri: String,
) {
    val id: String get() = manifest.id

    fun voiceUri(language: String, fragmentId: String): String = "${baseUri}voice/$language/$fragmentId.wav"

    fun hasVoice(language: String, fragmentId: String): Boolean = fragmentId in voiceFragments[language].orEmpty()
}
