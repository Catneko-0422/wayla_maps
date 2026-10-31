package cc.nekocat.waylamaps.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.nekocat.waylamaps.R
import cc.nekocat.waylamaps.service.SpeedCameraService
import cc.nekocat.waylamaps.ui.permissions.openAppSettings
import cc.nekocat.waylamaps.ui.permissions.rememberLocationPermissionRequest
import cc.nekocat.waylamaps.ui.theme.WaylaTheme

@Composable
fun MainScreen(onOpenLegal: () -> Unit, viewModel: MainViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigatorState by viewModel.navigatorState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var locationDenied by remember { mutableStateOf(false) }

    val startAlerts = rememberLocationPermissionRequest { granted ->
        locationDenied = !granted
        if (granted) SpeedCameraService.start(context)
    }

    MainContent(
        state = state,
        locationDenied = locationDenied,
        onStartStop = { if (state.running) SpeedCameraService.stop(context) else startAlerts() },
        onOpenSettings = { context.openAppSettings() },
        onOpenLegal = onOpenLegal,
        navigationSetup = { NavigationSetupCard(viewModel, navigatorState) },
    )
}

@Composable
private fun MainContent(
    state: MainUiState,
    locationDenied: Boolean,
    onStartStop: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLegal: () -> Unit,
    navigationSetup: @Composable () -> Unit,
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Spacer(Modifier.weight(1f))
            Speed(state.speedKmh)
            NextCamera(state)
            Spacer(Modifier.weight(1f))
            navigationSetup()

            if (locationDenied) {
                Text(
                    text = stringResource(R.string.location_permission_needed),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                OutlinedButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.open_settings))
                }
            }

            Button(
                onClick = onStartStop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
                colors = if (state.running) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text(
                    text = stringResource(if (state.running) R.string.action_stop else R.string.action_start),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Text(
                text = stringResource(R.string.data_attribution),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onOpenLegal) {
                Text(stringResource(R.string.legal_title))
            }
        }
    }
}

@Composable
private fun Speed(speedKmh: Int?) {
    Row {
        Text(
            text = speedKmh?.toString() ?: stringResource(R.string.speed_unknown),
            fontSize = 120.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.alignByBaseline(),
        )
        Text(
            text = stringResource(R.string.speed_unit),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier
                .alignByBaseline()
                .padding(start = 8.dp),
        )
    }
}

@Composable
private fun NextCamera(state: MainUiState) {
    if (!state.running) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                state.waitingForGps -> Text(
                    text = stringResource(R.string.waiting_for_gps),
                    style = MaterialTheme.typography.headlineSmall,
                )
                state.nextCameraMeters == null -> Text(
                    text = stringResource(R.string.no_camera_nearby),
                    style = MaterialTheme.typography.headlineSmall,
                )
                else -> {
                    Text(
                        text = stringResource(R.string.next_camera_distance, state.nextCameraMeters),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    state.nextCameraLimitKmh?.let {
                        Text(
                            text = stringResource(R.string.next_camera_limit, it),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MainContentPreview() {
    WaylaTheme {
        MainContent(
            state = MainUiState(running = true, speedKmh = 48, nextCameraMeters = 420, nextCameraLimitKmh = 50),
            locationDenied = false,
            onStartStop = {},
            onOpenSettings = {},
            onOpenLegal = {},
            navigationSetup = {},
        )
    }
}
