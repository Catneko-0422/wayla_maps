package cc.nekocat.waylamaps

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import cc.nekocat.waylamaps.data.SpeedCameraImporter
import cc.nekocat.waylamaps.data.SpeedCameraUpdateWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class WaylaApp : Application(), Configuration.Provider {
    @Inject lateinit var speedCameraImporter: SpeedCameraImporter
    @Inject lateinit var workerFactory: HiltWorkerFactory

    private val appScope = CoroutineScope(SupervisorJob())

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            runCatching { speedCameraImporter.importBundledIfNeeded() }
                .onFailure { Log.e("WaylaApp", "Speed camera import failed", it) }
        }
        SpeedCameraUpdateWorker.schedule(this)
    }
}
