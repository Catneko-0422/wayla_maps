package cc.nekocat.waylamaps.navigation

import android.content.Context
import cc.nekocat.waylamaps.BuildConfig
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.tasks.Task
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.model.CircularBounds
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class PlaceSuggestion(
    val placeId: String,
    val name: String,
    val address: String,
    val distanceMeters: Int?,
)

/**
 * Destination search with Places Autocomplete (New), limited to Taiwan.
 *
 * Only autocomplete is used: the Navigation SDK routes to a place ID directly, so no Place
 * Details call (billed separately) is needed. A session token groups the keystrokes of one search.
 */
@Singleton
class PlacesSearch @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val client: PlacesClient by lazy {
        if (!Places.isInitialized()) {
            Places.initializeWithNewPlacesApiEnabled(context, BuildConfig.MAPS_API_KEY, Locale.getDefault())
        }
        Places.createClient(context)
    }
    private var session: AutocompleteSessionToken? = null

    suspend fun suggestions(query: String, near: LatLng?): List<PlaceSuggestion> {
        val token = session ?: AutocompleteSessionToken.newInstance().also { session = it }
        val request = FindAutocompletePredictionsRequest.builder()
            .setQuery(query)
            .setCountries("TW")
            .setSessionToken(token)
            .apply {
                if (near != null) {
                    setOrigin(near)
                    setLocationBias(CircularBounds.newInstance(near, BIAS_RADIUS_M))
                }
            }
            .build()
        return client.findAutocompletePredictions(request).await().autocompletePredictions.map {
            PlaceSuggestion(
                placeId = it.placeId,
                name = it.getPrimaryText(null).toString(),
                address = it.getSecondaryText(null).toString(),
                distanceMeters = it.distanceMeters,
            )
        }
    }

    /** Ends the current search session once a destination is chosen or the search is abandoned. */
    fun endSession() {
        session = null
    }

    private companion object {
        const val BIAS_RADIUS_M = 30_000.0
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        val error = task.exception
        when {
            error != null -> continuation.resumeWithException(error)
            task.isCanceled -> continuation.cancel()
            else -> continuation.resume(task.result)
        }
    }
}
