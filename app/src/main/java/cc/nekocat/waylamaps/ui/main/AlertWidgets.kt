package cc.nekocat.waylamaps.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.nekocat.waylamaps.R

/** Current speed in large type, readable at a glance on a handlebar mount. */
@Composable
fun Speed(speedKmh: Int?, modifier: Modifier = Modifier, fontSize: TextUnit = 120.sp) {
    Row(modifier = modifier) {
        Text(
            text = speedKmh?.toString() ?: stringResource(R.string.speed_unknown),
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.alignByBaseline(),
        )
        Text(
            text = stringResource(R.string.speed_unit),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .alignByBaseline()
                .padding(start = 8.dp),
        )
    }
}

/**
 * Next speed camera ahead, or why there is none, and the next enforcement intersection.
 * Hidden while alerts are off.
 */
@Composable
fun NextCamera(state: MainUiState, modifier: Modifier = Modifier) {
    if (!state.running) return
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                state.waitingForGps -> Text(
                    text = stringResource(R.string.waiting_for_gps),
                    style = MaterialTheme.typography.titleLarge,
                )
                state.nextCameraMeters == null -> Text(
                    text = stringResource(R.string.no_camera_nearby),
                    style = MaterialTheme.typography.titleLarge,
                )
                else -> {
                    Text(
                        text = stringResource(R.string.next_camera_distance, state.nextCameraMeters),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    state.nextCameraLimitKmh?.let {
                        Text(
                            text = stringResource(R.string.next_camera_limit, it),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
            }
            if (state.inAverageZone) {
                Text(
                    text = stringResource(R.string.average_zone_active),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp),
                )
                state.zoneAverageKmh?.let {
                    Text(stringResource(R.string.average_zone_speed, it), style = MaterialTheme.typography.titleLarge)
                }
                state.zoneTraveledKm?.let { traveled ->
                    val text = state.zoneLengthKm?.let { length ->
                        stringResource(R.string.average_zone_progress, traveled, length)
                    } ?: stringResource(R.string.average_zone_traveled, traveled)
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (!state.waitingForGps) {
                state.nextEnforcementMeters?.let {
                    Text(
                        text = stringResource(R.string.next_enforcement_distance, it),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}
