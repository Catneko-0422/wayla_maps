package cc.nekocat.waylamaps.service

import android.annotation.SuppressLint
import android.app.Application
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.RoadSnappedLocationProvider

/** Where the ride service gets its positions from. */
interface LocationSource {
    fun start(onLocation: (Location) -> Unit)
    fun stop()
}

/** Raw GPS at 1 Hz, used when not navigating. Callers check location permission first. */
class GpsLocationSource(private val application: Application) : LocationSource {
    private var listener: LocationListener? = null

    @SuppressLint("MissingPermission")
    override fun start(onLocation: (Location) -> Unit) {
        stop()
        val newListener = LocationListener { onLocation(it) }
        listener = newListener
        application.getSystemService(LocationManager::class.java)
            .requestLocationUpdates(LocationManager.GPS_PROVIDER, UPDATE_INTERVAL_MS, 0f, newListener, Looper.getMainLooper())
    }

    override fun stop() {
        listener?.let { application.getSystemService(LocationManager::class.java).removeUpdates(it) }
        listener = null
    }

    private companion object {
        const val UPDATE_INTERVAL_MS = 1_000L
    }
}

/**
 * Road-snapped positions from the Navigation SDK, used while navigating. These follow the route
 * and are also what the SDK's simulator produces, so simulated drives exercise the alerts too.
 */
class RoadSnappedLocationSource(private val application: Application) : LocationSource {
    private var provider: RoadSnappedLocationProvider? = null
    private var listener: RoadSnappedLocationProvider.LocationListener? = null

    override fun start(onLocation: (Location) -> Unit) {
        stop()
        val newListener = RoadSnappedLocationProvider.LocationListener { onLocation(it) }
        provider = NavigationApi.getRoadSnappedLocationProvider(application)?.also { it.addLocationListener(newListener) }
        listener = newListener
    }

    override fun stop() {
        listener?.let { provider?.removeLocationListener(it) }
        provider = null
        listener = null
    }
}
