package cc.nekocat.waylamaps.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import cc.nekocat.waylamaps.R
import cc.nekocat.waylamaps.alerts.AlertSessionState
import cc.nekocat.waylamaps.navigation.NavigationController
import cc.nekocat.waylamaps.navigation.NavigationSession
import cc.nekocat.waylamaps.navigation.PlacesSearch
import cc.nekocat.waylamaps.navigation.TurnByTurnFeed
import cc.nekocat.waylamaps.service.RideController
import cc.nekocat.waylamaps.voice.SpeechState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** What the car screens need from the rest of the app; the same singletons as on the phone. */
class CarDependencies(
    val controller: NavigationController,
    val navigation: NavigationSession,
    val turnByTurn: TurnByTurnFeed,
    val places: PlacesSearch,
    val alerts: AlertSessionState,
    val rideController: RideController,
    val speech: SpeechState,
)

/** Android Auto entry point. Navigation, prompts and alerts are shared with the phone. */
@AndroidEntryPoint
class WaylaCarAppService : CarAppService() {
    @Inject lateinit var controller: NavigationController
    @Inject lateinit var navigation: NavigationSession
    @Inject lateinit var turnByTurn: TurnByTurnFeed
    @Inject lateinit var places: PlacesSearch
    @Inject lateinit var alerts: AlertSessionState
    @Inject lateinit var rideController: RideController
    @Inject lateinit var speech: SpeechState

    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // Debug builds also run in the Desktop Head Unit.
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(R.array.car_hosts_allowlist)
                .build()
        }

    override fun onCreateSession(): Session =
        WaylaCarSession(CarDependencies(controller, navigation, turnByTurn, places, alerts, rideController, speech))
}

class WaylaCarSession(private val deps: CarDependencies) : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        val surface = CarMapSurface(carContext)
        carContext.getCarService(androidx.car.app.AppManager::class.java).setSurfaceCallback(surface)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = surface.release()
        })
        return NavigationCarScreen(carContext, deps)
    }
}
