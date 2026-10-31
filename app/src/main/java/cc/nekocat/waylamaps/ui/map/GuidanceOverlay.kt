package cc.nekocat.waylamaps.ui.map

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import cc.nekocat.waylamaps.voice.VoiceMode
import kotlinx.coroutines.delay
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
    // The limit is the next camera's; it states a fact, so there is no other cue when over it.
    val limit = alerts.nextCameraLimitKmh
    val over = limit != null && alerts.speedKmh != null && alerts.speedKmh > limit
    Surface(shape = CARD_SHAPE, shadowElevation = 6.dp, modifier = modifier) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            limit?.let { SpeedLimitSign(it, Modifier.padding(end = 10.dp)) }
            Text(
                alerts.speedKmh?.toString() ?: stringResource(R.string.speed_unknown),
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = if (over) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
            Text(
                stringResource(R.string.speed_unit),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 4.dp, end = 12.dp),
            )
            val camera = alerts.nextCameraMeters?.let { stringResource(R.string.next_camera_distance, it) }
            val enforcement = alerts.nextEnforcementMeters?.let { stringResource(R.string.next_enforcement_distance, it) }
            (camera ?: enforcement)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A round speed limit sign as on Taiwan's roads: red ring, white face, black number. */
@Composable
private fun SpeedLimitSign(limitKmh: Int, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.next_camera_limit, limitKmh)
    Box(
        modifier
            .size(44.dp)
            .background(Color.White, CircleShape)
            .border(5.dp, Color(WaylaPalette.SIGN_RED), CircleShape)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(limitKmh.toString(), color = Color.Black, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * Voice and camera buttons for guidance. The camera button shows the whole route, or, once the
 * map shows anything but the vehicle, follows the vehicle again.
 */
@Composable
fun GuidanceControls(
    voiceMode: VoiceMode,
    onVoiceMode: () -> Unit,
    followingVehicle: Boolean,
    onOverview: () -> Unit,
    onFollow: () -> Unit,
    vertical: Boolean,
    modifier: Modifier = Modifier,
) {
    // A short label says which voice mode a tap picked; the icon alone is easy to misread.
    var announced by remember { mutableStateOf<VoiceMode?>(null) }
    var firstMode by remember { mutableStateOf(voiceMode) }
    LaunchedEffect(voiceMode) {
        if (voiceMode == firstMode) return@LaunchedEffect
        firstMode = voiceMode
        announced = voiceMode
        delay(VOICE_LABEL_MS)
        announced = null
    }
    val buttons = @Composable {
        RoundControl(
            icon = when (voiceMode) {
                VoiceMode.ALL -> R.drawable.ic_volume_up
                VoiceMode.ESSENTIAL -> R.drawable.ic_volume_down
                VoiceMode.MUTED -> R.drawable.ic_volume_off
            },
            description = stringResource(voiceMode.labelRes()),
            onClick = onVoiceMode,
        )
        if (followingVehicle) {
            RoundControl(R.drawable.ic_map, stringResource(R.string.action_route_overview), onOverview)
        } else {
            RoundControl(R.drawable.ic_navigation, stringResource(R.string.action_follow_vehicle), onFollow, highlighted = true)
        }
    }
    val label = @Composable {
        announced?.let {
            Surface(shape = CARD_SHAPE, color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = 4.dp) {
                Text(
                    stringResource(it.labelRes()),
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
    if (vertical) {
        Row(modifier, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            label()
            Column(verticalArrangement = Arrangement.spacedBy(CONTROL_GAP)) { buttons() }
        }
    } else {
        Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            label()
            Row(horizontalArrangement = Arrangement.spacedBy(CONTROL_GAP)) { buttons() }
        }
    }
}

@Composable
private fun RoundControl(@DrawableRes icon: Int, description: String, onClick: () -> Unit, highlighted: Boolean = false) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor = if (highlighted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        shadowElevation = 6.dp,
        modifier = Modifier.size(CONTROL_SIZE),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = description, modifier = Modifier.size(28.dp))
        }
    }
}

/** Shown while browsing once the map has been dragged away from the vehicle. */
@Composable
fun FollowVehicleButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier) {
        RoundControl(R.drawable.ic_navigation, stringResource(R.string.action_follow_vehicle), onClick, highlighted = true)
    }
}

private fun VoiceMode.labelRes() = when (this) {
    VoiceMode.ALL -> R.string.voice_mode_all
    VoiceMode.ESSENTIAL -> R.string.voice_mode_essential
    VoiceMode.MUTED -> R.string.voice_mode_muted
}

private val CONTROL_SIZE = 56.dp
private val CONTROL_GAP = 10.dp
private const val VOICE_LABEL_MS = 2_000L

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
