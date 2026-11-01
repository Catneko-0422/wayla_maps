package cc.nekocat.waylamaps.navigation

import android.app.Activity
import android.app.Application
import android.util.Log
import cc.nekocat.waylamaps.BuildConfig
import com.google.android.libraries.navigation.AudioGuidanceSettings
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.TermsAndConditionsCheckOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

sealed interface NavigatorState {
    /** Not started yet: needs location permission and accepted terms first. */
    data object NotInitialized : NavigatorState
    data object Initializing : NavigatorState
    data class Ready(val navigator: Navigator) : NavigatorState
    data class Error(val reason: Reason) : NavigatorState

    enum class Reason {
        /** local.properties has no MAPS_API_KEY. */
        MISSING_API_KEY,
        NOT_AUTHORIZED,
        TERMS_NOT_ACCEPTED,
        NETWORK_ERROR,
        LOCATION_PERMISSION_MISSING,

        /** The SDK never called back, which is what it does with an invalid key. */
        TIMED_OUT,
    }
}

/**
 * Owns the Navigation SDK's [Navigator], shared by the phone UI, the ride service and Android Auto.
 *
 * The SDK's built-in voice is silenced: prompts go through our own voice pipeline.
 */
@Singleton
class NavigationController @Inject constructor(
    private val application: Application,
) {
    private val _state = MutableStateFlow<NavigatorState>(NavigatorState.NotInitialized)
    val state: StateFlow<NavigatorState> = _state.asStateFlow()

    val navigator: Navigator? get() = (_state.value as? NavigatorState.Ready)?.navigator

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watchdog: Job? = null

    fun areTermsAccepted(): Boolean = NavigationApi.areTermsAccepted(application)

    /**
     * Starts the navigator. Requires location permission. With an [activity], the SDK shows its
     * terms dialog first if they are not accepted yet; without one, unaccepted terms are an error.
     *
     * Every failure ends in [NavigatorState.Error]. The SDK does not report an invalid API key
     * (it only logs "Authorization failure"), so a watchdog turns silence into [Reason.TIMED_OUT].
     * `showTermsAndConditionsDialog` is not used for the same reason: with a bad key it neither
     * shows the dialog nor calls back.
     */
    fun initialize(activity: Activity? = null) {
        when (_state.value) {
            NavigatorState.Initializing, is NavigatorState.Ready -> return
            else -> Unit
        }
        if (BuildConfig.MAPS_API_KEY.isBlank() || BuildConfig.MAPS_API_KEY == PLACEHOLDER_API_KEY) {
            _state.value = NavigatorState.Error(NavigatorState.Reason.MISSING_API_KEY)
            return
        }

        _state.value = NavigatorState.Initializing
        startWatchdog()
        val listener = object : NavigationApi.NavigatorListener {
            override fun onNavigatorReady(navigator: Navigator) {
                if (_state.value != NavigatorState.Initializing) return
                watchdog?.cancel()
                navigator.silenceSdkVoice()
                Log.i(TAG, "Navigator ready (Navigation SDK ${NavigationApi.getNavSDKVersion()})")
                _state.value = NavigatorState.Ready(navigator)
            }

            override fun onError(errorCode: Int) {
                if (_state.value != NavigatorState.Initializing) return
                watchdog?.cancel()
                Log.w(TAG, "Navigator unavailable: error $errorCode")
                _state.value = NavigatorState.Error(reasonFor(errorCode))
            }
        }
        if (activity != null) {
            NavigationApi.getNavigator(activity, listener, TermsAndConditionsCheckOption.ENABLED)
        } else {
            NavigationApi.getNavigator(application, listener)
        }
    }

    /**
     * Allows [TERMS_TIMEOUT_MS] while the terms dialog may be open (the user is reading), then
     * [CALLBACK_TIMEOUT_MS] once the terms are accepted.
     */
    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            var termsAccepted = areTermsAccepted()
            var waited = 0L
            while (_state.value == NavigatorState.Initializing) {
                val limit = if (termsAccepted) CALLBACK_TIMEOUT_MS else TERMS_TIMEOUT_MS
                if (waited >= limit) {
                    Log.w(TAG, "Navigation SDK did not respond in ${limit / 1000} s; check the API key")
                    _state.value = NavigatorState.Error(NavigatorState.Reason.TIMED_OUT)
                    return@launch
                }
                delay(POLL_MS)
                waited += POLL_MS
                if (!termsAccepted && areTermsAccepted()) {
                    termsAccepted = true
                    waited = 0
                }
            }
        }
    }

    private fun reasonFor(errorCode: Int) = when (errorCode) {
        NavigationApi.ErrorCode.NOT_AUTHORIZED -> NavigatorState.Reason.NOT_AUTHORIZED
        NavigationApi.ErrorCode.TERMS_NOT_ACCEPTED -> NavigatorState.Reason.TERMS_NOT_ACCEPTED
        NavigationApi.ErrorCode.LOCATION_PERMISSION_MISSING -> NavigatorState.Reason.LOCATION_PERMISSION_MISSING
        else -> NavigatorState.Reason.NETWORK_ERROR
    }

    private companion object {
        const val TAG = "NavigationController"

        /** Value in local.defaults.properties when no real key is configured. */
        const val PLACEHOLDER_API_KEY = "DEFAULT_API_KEY"
        const val POLL_MS = 1_000L
        const val CALLBACK_TIMEOUT_MS = 30_000L
        const val TERMS_TIMEOUT_MS = 120_000L
    }
}

/**
 * Mutes the Navigation SDK's own spoken guidance, which would talk over the character. Set again
 * whenever guidance starts: on some devices the SDK does not keep it from navigator creation.
 */
fun Navigator.silenceSdkVoice() {
    setAudioGuidanceSettings(
        AudioGuidanceSettings.builder()
            .setGuidanceMode(AudioGuidanceSettings.GuidanceMode.SILENT)
            .setVibrationEnabled(false)
            .build(),
    )
}
