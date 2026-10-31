package cc.nekocat.waylamaps

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import cc.nekocat.waylamaps.data.SpeedCameraImporter
import cc.nekocat.waylamaps.data.SpeedCameraUpdateWorker
import cc.nekocat.waylamaps.service.RideController
import cc.nekocat.waylamaps.service.RideNotificationProvider
import com.google.android.libraries.navigation.NavigationApi
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class WaylaApp : Application(), Configuration.Provider {
    @Inject lateinit var speedCameraImporter: SpeedCameraImporter
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var rideNotificationProvider: RideNotificationProvider

    // Injected so it starts watching alerts and guidance as soon as the app runs.
    @Inject lateinit var rideController: RideController

    private val appScope = CoroutineScope(SupervisorJob())

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            runCatching { speedCameraImporter.importBundledIfNeeded() }
                .onFailure { Log.e(TAG, "Speed camera import failed", it) }
        }
        SpeedCameraUpdateWorker.schedule(this)

        // One notification for both RideService and the Navigation SDK's foreground service.
        try {
            NavigationApi.initForegroundServiceManagerProvider(
                this,
                RideNotificationProvider.NOTIFICATION_ID,
                rideNotificationProvider,
            )
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Foreground service manager was already set up", e)
        }
    }

    private companion object {
        const val TAG = "WaylaApp"
    }
}
