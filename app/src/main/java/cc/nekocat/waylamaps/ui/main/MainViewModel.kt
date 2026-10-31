package cc.nekocat.waylamaps.ui.main

import android.app.Activity
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.nekocat.waylamaps.alerts.AlertSession
import cc.nekocat.waylamaps.alerts.AlertSessionState
import cc.nekocat.waylamaps.navigation.NavigationController
import cc.nekocat.waylamaps.navigation.NavigatorState
import cc.nekocat.waylamaps.ui.permissions.hasLocationPermission
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import kotlin.math.roundToInt

data class MainUiState(
    val running: Boolean = false,
    val waitingForGps: Boolean = false,
    val speedKmh: Int? = null,
    /** Distance to the next camera ahead, rounded to 10 m. */
    val nextCameraMeters: Int? = null,
    val nextCameraLimitKmh: Int? = null,
)

fun AlertSession.toUiState() = MainUiState(
    running = running,
    waitingForGps = running && !hasFix,
    speedKmh = speedKmh,
    nextCameraMeters = next?.let { (it.distanceM / 10).roundToInt() * 10 },
    nextCameraLimitKmh = next?.camera?.limitKmh,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext context: Context,
    sessionState: AlertSessionState,
    private val navigation: NavigationController,
) : ViewModel() {
    val uiState: StateFlow<MainUiState> = sessionState.session
        .map { it.toUiState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), sessionState.session.value.toUiState())

    val navigatorState: StateFlow<NavigatorState> = navigation.state

    init {
        // Returning users have already granted everything; start the navigator straight away.
        if (context.hasLocationPermission() && navigation.areTermsAccepted()) navigation.initialize()
    }

    /** Shows the Navigation SDK terms on [activity] first if they are not accepted yet. */
    fun initializeNavigation(activity: Activity?) = navigation.initialize(activity)
}
