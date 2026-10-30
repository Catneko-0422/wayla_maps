package cc.nekocat.waylamaps

import android.app.Application
import android.util.Log
import cc.nekocat.waylamaps.data.SpeedCameraImporter
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class WaylaApp : Application() {
    @Inject lateinit var speedCameraImporter: SpeedCameraImporter

    private val appScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            runCatching { speedCameraImporter.importIfNeeded() }
                .onFailure { Log.e("WaylaApp", "Speed camera import failed", it) }
        }
    }
}
