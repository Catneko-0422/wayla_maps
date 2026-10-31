package cc.nekocat.waylamaps.ui.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Returns a function that asks for precise location (and notifications on 13+) if needed,
 * then reports whether precise location is granted. Notifications are optional.
 */
@Composable
fun rememberLocationPermissionRequest(onResult: (granted: Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val currentOnResult by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        currentOnResult(context.hasLocationPermission())
    }
    return remember(launcher, context) {
        {
            val missing = requestedPermissions().filterNot { context.isGranted(it) }
            if (missing.isEmpty()) currentOnResult(true) else launcher.launch(missing.toTypedArray())
        }
    }
}

fun Context.hasLocationPermission() = isGranted(Manifest.permission.ACCESS_FINE_LOCATION)

fun Context.openAppSettings() {
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
}

private fun requestedPermissions(): List<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}

private fun Context.isGranted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
