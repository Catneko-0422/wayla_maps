package cc.nekocat.waylamaps.ui.map

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.nekocat.waylamaps.R
import cc.nekocat.waylamaps.navigation.TurnUpdate
import cc.nekocat.waylamaps.ui.main.MainUiState
import cc.nekocat.waylamaps.ui.theme.WaylaPalette
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val CARD_SHAPE = RoundedCornerShape(20.dp)

/** The next maneuver: the SDK's picture of it on a blue tile, the distance, and the road. */
@Composable
fun TurnCard(turn: TurnUpdate, icon: Bitmap?, modifier: Modifier = Modifier) {
    Surface(shape = CARD_SHAPE, shadowElevation = 6.dp, modifier = modifier.widthIn(max = 380.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(14.dp), color = Color(WaylaPalette.DEEP_BLUE), modifier = Modifier.size(64.dp)) {
                val image = remember(icon) { icon?.asImageBitmap() }
                if (image != null) {
                    Image(image, contentDescription = null, modifier = Modifier.padding(6.dp))
                }
            }
            Column(Modifier.padding(start = 14.dp)) {
                turn.distanceToStepM?.let {
                    Text(formatDistance(it), fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
                (turn.roadName ?: turn.instructionText)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Speed and the next speed camera or enforcement point, under the turn card. */
@Composable
fun SpeedAlertCard(alerts: MainUiState, modifier: Modifier = Modifier) {
    Surface(shape = CARD_SHAPE, shadowElevation = 6.dp, modifier = modifier) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                alerts.speedKmh?.toString() ?: stringResource(R.string.speed_unknown),
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.speed_unit),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 4.dp, end = 12.dp),
            )
            val camera = alerts.nextCameraMeters?.let { meters ->
                stringResource(R.string.next_camera_distance, meters) +
                    (alerts.nextCameraLimitKmh?.let { "・" + stringResource(R.string.next_camera_limit, it) } ?: "")
            }
            val enforcement = alerts.nextEnforcementMeters?.let { stringResource(R.string.next_enforcement_distance, it) }
            (camera ?: enforcement)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Arrival time, distance and time left, with the button that ends the trip. */
@Composable
fun TripPill(turn: TurnUpdate?, onStop: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    Surface(shape = CARD_SHAPE, shadowElevation = 6.dp, modifier = modifier) {
        Row(
            Modifier.padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val seconds = turn?.remainingSeconds
            val meters = turn?.remainingMeters
            val parts = buildList {
                seconds?.let {
                    add(LocalTime.now().plusSeconds(it.toLong()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)))
                }
                meters?.let { add(formatDistance(it)) }
                seconds?.let { add(formatDuration(it)) }
            }
            Box(Modifier.widthIn(min = if (compact) 0.dp else 120.dp)) {
                if (compact) {
                    // Arrival time on top, distance and time left below, to fit beside the character.
                    Column {
                        parts.firstOrNull()?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                        Text(parts.drop(1).joinToString(" ・ "), style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    Text(parts.joinToString(" ・ "), style = MaterialTheme.typography.titleMedium)
                }
            }
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onSurface,
                    contentColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Text(stringResource(R.string.navigation_stop_short), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
internal fun formatDistance(meters: Int): String =
    if (meters < 1000) {
        stringResource(R.string.distance_meters, (meters / 10) * 10)
    } else {
        stringResource(R.string.distance_km, meters / 1000.0)
    }

@Composable
internal fun formatDuration(seconds: Int): String {
    val minutes = (seconds + 59) / 60
    return if (minutes < 60) {
        stringResource(R.string.duration_minutes, minutes)
    } else {
        stringResource(R.string.duration_hours_minutes, minutes / 60, minutes % 60)
    }
}
