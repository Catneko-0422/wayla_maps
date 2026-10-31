package cc.nekocat.waylamaps.ui.main

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cc.nekocat.waylamaps.R
import cc.nekocat.waylamaps.navigation.NavigatorState
import cc.nekocat.waylamaps.ui.permissions.rememberLocationPermissionRequest

/**
 * Walks the user through what navigation needs: location permission, then the Navigation SDK
 * terms (shown by the SDK during [onEnable]), then the navigator. Speed camera alerts work
 * without any of this.
 */
@Composable
fun NavigationSetupCard(navigatorState: NavigatorState, onEnable: (Activity?) -> Unit) {
    if (navigatorState is NavigatorState.Ready) return
    val activity = LocalActivity.current
    var locationDenied by remember { mutableStateOf(false) }

    val requestLocation = rememberLocationPermissionRequest { granted ->
        locationDenied = !granted
        if (granted) onEnable(activity)
    }

    val message = when {
        locationDenied -> R.string.navigation_error_location
        navigatorState is NavigatorState.Initializing -> R.string.navigation_initializing
        navigatorState is NavigatorState.Error -> navigatorState.messageRes()
        else -> R.string.navigation_not_enabled
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(message),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            if (navigatorState !is NavigatorState.Initializing) {
                OutlinedButton(onClick = requestLocation) {
                    Text(stringResource(R.string.navigation_enable))
                }
            }
        }
    }
}

private fun NavigatorState.Error.messageRes() = when (reason) {
    NavigatorState.Reason.MISSING_API_KEY -> R.string.navigation_error_missing_key
    NavigatorState.Reason.NOT_AUTHORIZED -> R.string.navigation_error_not_authorized
    NavigatorState.Reason.TERMS_NOT_ACCEPTED -> R.string.navigation_terms_declined
    NavigatorState.Reason.NETWORK_ERROR -> R.string.navigation_error_network
    NavigatorState.Reason.LOCATION_PERMISSION_MISSING -> R.string.navigation_error_location
    NavigatorState.Reason.TIMED_OUT -> R.string.navigation_error_timed_out
}
