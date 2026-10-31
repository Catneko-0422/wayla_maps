package cc.nekocat.waylamaps.voice

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The phrase table in `assets/voice/phrases.json`, shared with `tools/voice/generate_voice.py`.
 *
 * A fragment is one pre-generated audio file; a phrase is a per-language sequence of fragment ids,
 * where `{slot}` placeholders are filled in at playback time.
 */
class PhraseCatalog(private val table: Table) {
    @Serializable
    data class Table(
        val languages: Map<String, Language>,
        /** Allowed values per slot, as strings: distances, limits and maneuver fragment ids. */
        val slots: Map<String, List<String>> = emptyMap(),
        val fragments: Map<String, Map<String, String>>,
        val phrases: Map<String, Phrase>,
    )

    @Serializable
    data class Language(val joiner: String)

    @Serializable
    data class Phrase(val priority: Priority, val fragments: Map<String, List<String>>)

    data class Resolved(
        val fragmentIds: List<String>,
        /** The whole sentence, for the TTS fallback. */
        val text: String,
    )

    val languages: Set<String> get() = table.languages.keys
    val phraseIds: Set<String> get() = table.phrases.keys
    val slots: Map<String, List<String>> get() = table.slots
    val fragmentIds: Set<String> get() = table.fragments.keys

    fun priorityOf(phraseId: String): Priority = phrase(phraseId).priority

    /** Names of the slots [phraseId] uses, in any language. */
    fun slotsOf(phraseId: String): Set<String> =
        phrase(phraseId).fragments.values.flatten().flatMap { SLOT.findAll(it).map { m -> m.groupValues[1] } }.toSet()

    /** @throws IllegalArgumentException for an unknown phrase, language, slot or fragment. */
    fun resolve(phraseId: String, args: Map<String, Any>, language: String): Resolved {
        val joiner = requireNotNull(table.languages[language]) { "Unknown language $language" }.joiner
        val templates = requireNotNull(phrase(phraseId).fragments[language]) {
            "Phrase $phraseId has no $language version"
        }
        val ids = templates.map { template ->
            SLOT.replace(template) { match ->
                val name = match.groupValues[1]
                requireNotNull(args[name]) { "Phrase $phraseId needs argument $name" }.toString()
            }
        }
        val texts = ids.map { id ->
            val fragment = requireNotNull(table.fragments[id]) { "Unknown fragment $id in $phraseId" }
            requireNotNull(fragment[language]) { "Fragment $id has no $language text" }
        }
        return Resolved(ids, texts.joinToString(joiner))
    }

    private fun phrase(id: String) = requireNotNull(table.phrases[id]) { "Unknown phrase $id" }

    companion object {
        const val ASSET_PATH = "voice/phrases.json"

        private val SLOT = Regex("\\{(\\w+)\\}")
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String) = PhraseCatalog(json.decodeFromString<Table>(text))
    }
}
