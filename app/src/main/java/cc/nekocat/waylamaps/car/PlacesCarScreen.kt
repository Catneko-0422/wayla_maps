package cc.nekocat.waylamaps.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import cc.nekocat.waylamaps.R
import cc.nekocat.waylamaps.navigation.SavedPlace
import kotlinx.coroutines.launch

/**
 * Home, work and recent destinations on the car screen: one tap starts guidance, with no typing.
 * Search is in the header for anything else.
 */
class PlacesCarScreen(carContext: CarContext, private val deps: CarDependencies) : Screen(carContext) {
    init {
        lifecycleScope.launch { deps.savedPlaces.data.collect { invalidate() } }
    }

    override fun onGetTemplate(): Template {
        val saved = deps.savedPlaces.data.value
        val list = ItemList.Builder()
        saved.home?.let { list.addItem(row(it, R.string.place_home, R.drawable.ic_home)) }
        saved.work?.let { list.addItem(row(it, R.string.place_work, R.drawable.ic_work)) }
        saved.recent.take(MAX_RECENT).forEach { list.addItem(row(it, null, R.drawable.ic_history)) }
        list.setNoItemsMessage(carContext.getString(R.string.car_no_places))
        val search = Action.Builder()
            .setIcon(icon(R.drawable.ic_search))
            .setOnClickListener { screenManager.push(SearchCarScreen(carContext, deps)) }
            .build()
        val header = Header.Builder()
            .setTitle(carContext.getString(R.string.car_places))
            .setStartHeaderAction(Action.BACK)
            .addEndHeaderAction(search)
            .build()
        return ListTemplate.Builder().setHeader(header).setSingleList(list.build()).build()
    }

    private fun row(place: SavedPlace, labelRes: Int?, iconRes: Int): Row {
        val builder = Row.Builder()
            .setTitle(labelRes?.let { carContext.getString(it) } ?: place.name)
            .setImage(icon(iconRes))
            .setOnClickListener {
                deps.savedPlaces.addRecent(place.toSuggestion())
                deps.navigation.start(place.toSuggestion())
                screenManager.pop()
            }
        // Home and work show the place under the label; recent places show the address.
        builder.addText(if (labelRes != null) place.name else place.address)
        return builder.build()
    }

    // The drawables are black; the host tints them to suit its day or night theme.
    private fun icon(res: Int) = CarIcon.Builder(IconCompat.createWithResource(carContext, res)).setTint(CarColor.DEFAULT).build()

    private companion object {
        /** A list template shows six rows at most while driving; home and work take two. */
        const val MAX_RECENT = 4
    }
}
