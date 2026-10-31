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
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Weekly refresh of the speed camera table from government open data. */
@HiltWorker
class SpeedCameraUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val importer: SpeedCameraImporter,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        Log.i(TAG, "Speed camera update: ${importer.updateFromNetwork()}")
        Result.success()
    } catch (e: IOException) {
        Log.w(TAG, "Speed camera download failed, will retry", e)
        Result.retry()
    }

    companion object {
        private const val TAG = "SpeedCameraUpdate"
        private const val WORK_NAME = "speed-camera-update"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SpeedCameraUpdateWorker>(7, TimeUnit.DAYS)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
