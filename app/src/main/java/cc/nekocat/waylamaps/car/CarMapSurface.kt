package cc.nekocat.waylamaps.car

import android.app.Presentation
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.libraries.navigation.NavigationViewForAuto
import cc.nekocat.waylamaps.R

/**
 * The Navigation SDK's map on the car screen: a [NavigationViewForAuto] shown through a
 * Presentation on a virtual display backed by the host's surface. Shares the phone's Navigator,
 * in the phone's black, white and blue style, day or night as the car says.
 */
class CarMapSurface(private val carContext: CarContext) : SurfaceCallback {
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var view: NavigationViewForAuto? = null
    private var map: GoogleMap? = null

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        val surface = container.surface ?: return
        if (container.width == 0 || container.height == 0 || container.dpi == 0) return
        release()
        val display = carContext.getSystemService(DisplayManager::class.java).createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            container.width,
            container.height,
            container.dpi,
            surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        )
        val navigationView = NavigationViewForAuto(carContext).apply {
            onCreate(null)
            onStart()
            onResume()
        }
        presentation = Presentation(carContext, display.display).apply {
            setContentView(navigationView)
            show()
        }
        navigationView.getMapAsync {
            map = it
            applyDayNight()
        }
        virtualDisplay = display
        view = navigationView
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) = release()

    override fun onScroll(distanceX: Float, distanceY: Float) {
        map?.moveCamera(CameraUpdateFactory.scrollBy(distanceX, distanceY))
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        map?.animateCamera(CameraUpdateFactory.zoomBy(scaleFactor - 1, Point(focusX.toInt(), focusY.toInt())))
    }

    /**
     * Follows the car's day or night mode; call on car configuration changes too. The car view has
     * no night mode switch of its own, so the map style carries it.
     */
    fun applyDayNight() {
        val dark = carContext.isDarkMode
        map?.setMapStyle(MapStyleOptions.loadRawResourceStyle(carContext, if (dark) R.raw.map_style_night else R.raw.map_style_day))
    }

    fun release() {
        view?.apply {
            onPause()
            onStop()
            onDestroy()
        }
        presentation?.dismiss()
        virtualDisplay?.release()
        view = null
        presentation = null
        virtualDisplay = null
        map = null
    }

    private companion object {
        const val VIRTUAL_DISPLAY_NAME = "WaylaCarMap"
    }
}
