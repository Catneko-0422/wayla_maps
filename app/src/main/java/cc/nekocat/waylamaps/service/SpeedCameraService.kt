package cc.nekocat.waylamaps.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import cc.nekocat.waylamaps.R
import cc.nekocat.waylamaps.alerts.AlertSession
import cc.nekocat.waylamaps.alerts.AlertSessionState
import cc.nekocat.waylamaps.alerts.CameraAhead
import cc.nekocat.waylamaps.alerts.CameraAheadDetector
import cc.nekocat.waylamaps.alerts.Fix
import cc.nekocat.waylamaps.data.SpeedCameraRepository
import cc.nekocat.waylamaps.ui.MainActivity
import cc.nekocat.waylamaps.voice.Announcer
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/** Foreground service that follows GPS and announces speed cameras ahead. */
@AndroidEntryPoint
class SpeedCameraService : LifecycleService() {
    @Inject lateinit var repository: SpeedCameraRepository
    @Inject lateinit var sessionState: AlertSessionState
    @Inject lateinit var announcer: Announcer

    private val detector = CameraAheadDetector()
    private val locations = Channel<Location>(Channel.CONFLATED)
    private val locationListener = LocationListener { locations.trySend(it) }
    private var started = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            else -> start()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        getSystemService(LocationManager::class.java).removeUpdates(locationListener)
        announcer.shutdown()
        sessionState.update { AlertSession() }
        super.onDestroy()
    }

    private fun start() {
        if (started) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Location permission missing, not starting")
            stopSelf()
            return
        }
        started = true

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
        )
        sessionState.update { it.copy(running = true) }

        getSystemService(LocationManager::class.java).requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            UPDATE_INTERVAL_MS,
            0f,
            locationListener,
            Looper.getMainLooper(),
        )
        lifecycleScope.launch {
            locations.consumeAsFlow().collect { onLocation(it) }
        }
    }

    private suspend fun onLocation(location: Location) {
        val fix = Fix(
            lat = location.latitude,
            lng = location.longitude,
            bearingDeg = if (location.hasBearing()) location.bearing.toDouble() else null,
            speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
        )
        val candidates = repository.camerasNear(fix.lat, fix.lng, SEARCH_RADIUS_M)
        val result = detector.update(fix, candidates)

        sessionState.update {
            it.copy(
                hasFix = true,
                speedKmh = fix.speedMps?.let { mps -> (mps * 3.6).roundToInt() },
                next = result.next,
            )
        }
        result.announce?.let { announcer.announce(phraseFor(it)) }
    }

    private fun phraseFor(ahead: CameraAhead): String {
        val meters = CameraAheadDetector.spokenDistanceMeters(ahead.distanceM)
        val limit = ahead.camera.limitKmh
        return if (limit != null) {
            getString(R.string.alert_speed_camera_with_limit, meters, limit)
        } else {
            getString(R.string.alert_speed_camera, meters)
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_alerts),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            0,
            stopIntent(this),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notification_alerts_running))
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.action_stop), stop)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "SpeedCameraService"
        private const val ACTION_STOP = "cc.nekocat.waylamaps.action.STOP_ALERTS"
        private const val CHANNEL_ID = "speed_camera_alerts"
        private const val NOTIFICATION_ID = 1
        private const val UPDATE_INTERVAL_MS = 1_000L
        private const val SEARCH_RADIUS_M = 1_000.0

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, SpeedCameraService::class.java))
        }

        fun stop(context: Context) {
            context.startService(stopIntent(context))
        }

        private fun stopIntent(context: Context) =
            Intent(context, SpeedCameraService::class.java).setAction(ACTION_STOP)
    }
}
