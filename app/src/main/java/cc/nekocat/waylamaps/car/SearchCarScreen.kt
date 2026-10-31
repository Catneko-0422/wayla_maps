package cc.nekocat.waylamaps.car

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import cc.nekocat.waylamaps.R
import cc.nekocat.waylamaps.navigation.PlaceSuggestion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Destination search on the car screen, using the same Places search as the phone. The host
 * restricts typing while driving, as Android Auto requires.
 */
class SearchCarScreen(carContext: CarContext, private val deps: CarDependencies) : Screen(carContext) {
    private var results: List<PlaceSuggestion> = emptyList()
    private var loading = false
    private var job: Job? = null

    override fun onGetTemplate(): Template {
        val builder = SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) = search(searchText, DEBOUNCE_MS)
            override fun onSearchSubmitted(searchText: String) = search(searchText, 0)
        })
            .setHeaderAction(Action.BACK)
            .setSearchHint(carContext.getString(R.string.search_hint))
            .setShowKeyboardByDefault(true)
        if (loading) {
            builder.setLoading(true)
        } else {
            val list = ItemList.Builder()
            results.take(MAX_RESULTS).forEach { place ->
                list.addItem(
                    Row.Builder()
                        .setTitle(place.name)
                        .addText(place.address)
                        .setOnClickListener { start(place) }
                        .build(),
                )
            }
            builder.setItemList(list.build())
        }
        return builder.build()
    }

    private fun search(text: String, delayMs: Long) {
        job?.cancel()
        if (text.trim().length < MIN_QUERY_LENGTH) {
            results = emptyList()
            invalidate()
            return
        }
        job = lifecycleScope.launch {
            delay(delayMs)
            loading = true
            invalidate()
            results = try {
                deps.places.suggestions(text.trim(), null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Car search failed", e)
                emptyList()
            }
            loading = false
            invalidate()
        }
    }

    private fun start(place: PlaceSuggestion) {
        deps.places.endSession()
        deps.navigation.start(place)
        screenManager.pop()
    }

    private companion object {
        const val TAG = "SearchCarScreen"
        const val DEBOUNCE_MS = 400L
        const val MIN_QUERY_LENGTH = 2
        const val MAX_RESULTS = 6
    }
}
