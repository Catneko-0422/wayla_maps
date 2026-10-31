package cc.nekocat.waylamaps.service

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import cc.nekocat.waylamaps.R
import cc.nekocat.waylamaps.navigation.GuidanceState
import cc.nekocat.waylamaps.navigation.NavigationSession
import cc.nekocat.waylamaps.ui.MainActivity
import com.google.android.libraries.navigation.NotificationContentProviderBase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one ongoing notification, shared by [RideService] and the Navigation SDK's own foreground
 * service through the SDK's ForegroundServiceManager, so riders never see two of them.
 */
@Singleton
class RideNotificationProvider @Inject constructor(
    private val application: Application,
    private val navigation: NavigationSession,
    private val rideController: RideController,
) : NotificationContentProviderBase(application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        application.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                application.getString(R.string.notification_channel_alerts),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        scope.launch { combine(navigation.state, rideController.alertsEnabled, ::Pair).collect { updateNotification() } }
    }

    override fun getNotification(): Notification {
        val guidance = navigation.state.value
        val title = when {
            guidance is GuidanceState.Guiding ->
                application.getString(R.string.notification_navigating, guidance.destination.name)
            rideController.alertsEnabled.value -> application.getString(R.string.notification_alerts_running)
            // The SDK keeps its own foreground service while its map is on screen.
            else -> application.getString(R.string.notification_map_active)
        }
        val openApp = PendingIntent.getActivity(
            application,
            0,
            Intent(application, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            application,
            0,
            RideService.stopIntent(application),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(application, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentIntent(openApp)
            .addAction(0, application.getString(R.string.action_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "speed_camera_alerts"
    }
}
