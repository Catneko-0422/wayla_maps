package cc.nekocat.waylamaps.navigation

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** A destination kept on the phone: enough to route to it again without a search. */
@Serializable
data class SavedPlace(val placeId: String, val name: String, val address: String) {
    fun toSuggestion() = PlaceSuggestion(placeId, name, address, distanceMeters = null)
}

@Serializable
data class SavedPlacesData(
    val home: SavedPlace? = null,
    val work: SavedPlace? = null,
    val recent: List<SavedPlace> = emptyList(),
) {
    /** [place] first, without an older entry for the same place, at most [max] entries. */
    fun withRecent(place: SavedPlace, max: Int = MAX_RECENT): SavedPlacesData =
        copy(recent = (listOf(place) + recent.filterNot { it.placeId == place.placeId }).take(max))

    companion object {
        const val MAX_RECENT = 8
    }
}

/** Home, work and recent destinations, shared by the phone and Android Auto. Phone only. */
@Singleton
class SavedPlaces @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("saved_places", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _data = MutableStateFlow(load())
    val data: StateFlow<SavedPlacesData> = _data.asStateFlow()

    fun addRecent(place: PlaceSuggestion) = update { it.withRecent(place.toSaved()) }

    fun setHome(place: PlaceSuggestion?) = update { it.copy(home = place?.toSaved()) }

    fun setWork(place: PlaceSuggestion?) = update { it.copy(work = place?.toSaved()) }

    fun removeRecent(placeId: String) = update { data -> data.copy(recent = data.recent.filterNot { it.placeId == placeId }) }

    private fun update(transform: (SavedPlacesData) -> SavedPlacesData) {
        val next = transform(_data.value)
        _data.value = next
        prefs.edit { putString(KEY, json.encodeToString(SavedPlacesData.serializer(), next)) }
    }

    private fun load(): SavedPlacesData =
        prefs.getString(KEY, null)
            ?.let { runCatching { json.decodeFromString(SavedPlacesData.serializer(), it) }.getOrNull() }
            ?: SavedPlacesData()

    private fun PlaceSuggestion.toSaved() = SavedPlace(placeId, name, address)

    private companion object {
        const val KEY = "places"
    }
}
