package cc.nekocat.waylamaps.data

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cc.nekocat.waylamaps.data.enforcement.EnforcementImporter
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Weekly refresh of the speed cameras and the county enforcement points from open data. */
@HiltWorker
class OpenDataUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val speedCameras: SpeedCameraImporter,
    private val enforcement: EnforcementImporter,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val enforcementResults = enforcement.updateFromNetwork()
        Log.i(TAG, "Enforcement update: $enforcementResults")
        return try {
            Log.i(TAG, "Speed camera update: ${speedCameras.updateFromNetwork()}")
            Result.success()
        } catch (e: IOException) {
            Log.w(TAG, "Speed camera download failed, will retry", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "OpenDataUpdate"
        private const val WORK_NAME = "open-data-update"

        /** Replaced by [WORK_NAME]; its worker class no longer exists. */
        private const val OLD_WORK_NAME = "speed-camera-update"

        fun schedule(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(OLD_WORK_NAME)
            val request = PeriodicWorkRequestBuilder<OpenDataUpdateWorker>(7, TimeUnit.DAYS)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .build()
            workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
