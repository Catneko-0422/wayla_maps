package cc.nekocat.waylamaps.ui.map

import android.app.Activity
import android.graphics.Bitmap
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.nekocat.waylamaps.R
import cc.nekocat.waylamaps.navigation.GuidanceState
import cc.nekocat.waylamaps.navigation.SavedPlacesData
import cc.nekocat.waylamaps.navigation.TurnUpdate
import cc.nekocat.waylamaps.service.ThemeMode
import cc.nekocat.waylamaps.ui.character.CharacterView
import cc.nekocat.waylamaps.ui.main.NavigationSetupCard
import cc.nekocat.waylamaps.ui.main.NextCamera
import cc.nekocat.waylamaps.ui.main.Speed
import cc.nekocat.waylamaps.ui.permissions.openAppSettings
import cc.nekocat.waylamaps.ui.permissions.rememberLocationPermissionRequest
import cc.nekocat.waylamaps.voice.Speech
import kotlinx.coroutines.withTimeoutOrNull

/** How the map is padded and followed, set by the layout that is showing. */
private data class MapFrame(
    val left: Dp = 0.dp,
    val right: Dp = 0.dp,
    val bottom: Dp = 0.dp,
    val top: Dp = 0.dp,
    val topDown: Boolean = false,
    val logoOnRight: Boolean = false,
)

/**
 * Main screen: a full-screen map in every mode.
 * - Browsing: a search bar and a panel with saved places, results and the route preview; a
 *   bottom sheet in portrait, a card on the left in landscape (handlebar mounts, dashboards).
 * - Guiding: turn, speed and trip cards over the map, with the character beside them.
 */
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun MapScreen(
    themeMode: ThemeMode,
    onOpenSettings: () -> Unit,
    viewModel: MapViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val query = viewModel.query
    val context = LocalContext.current
    var locationDenied by remember { mutableStateOf(false) }
    val enableAlerts = rememberLocationPermissionRequest { granted ->
        locationDenied = !granted
        if (granted) viewModel.setAlertsEnabled(true)
    }
    val actions = BrowseActions(
        onQueryChange = viewModel::onQueryChange,
        onSelect = viewModel::select,
        onClearSearch = viewModel::clearSearch,
        onTravelMode = viewModel::setTravelMode,
        onStart = viewModel::startNavigation,
        onSetHome = viewModel::setHome,
        onSetWork = viewModel::setWork,
        onRemoveRecent = viewModel::removeRecent,
        onAlertsChange = { on -> if (on) enableAlerts() else viewModel.setAlertsEnabled(false) },
        onAddPointHere = viewModel::addPointHere,
        onOpenSettings = onOpenSettings,
    )
    val characterModel by viewModel.characterModel.collectAsStateWithLifecycle()
    val turn by viewModel.turn.collectAsStateWithLifecycle()
    val turnIcon by viewModel.turnIcon.collectAsStateWithLifecycle()
    val speech by viewModel.speech.collectAsStateWithLifecycle()
    val characterName by viewModel.selectedCharacterName.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val route by viewModel.route.collectAsStateWithLifecycle()
    val voiceMode by viewModel.voiceMode.collectAsStateWithLifecycle()

    // Guidance camera: whole-route overview on request, and whether the map still follows the
    // vehicle (a drag or the overview stops it).
    val guidingNow = state.guidance is GuidanceState.Guiding
    var routeOverview by remember { mutableStateOf(false) }
    var following by remember { mutableStateOf(true) }
    var followRequests by remember { mutableIntStateOf(0) }
    LaunchedEffect(guidingNow) { if (!guidingNow) routeOverview = false }

    // One map instance moved between layouts, so rotating or starting guidance keeps it.
    var frame by remember { mutableStateOf(MapFrame()) }
    val currentFrame by rememberUpdatedState(frame)
    val currentTheme by rememberUpdatedState(themeMode)
    val currentGuiding by rememberUpdatedState(state.guiding)
    // While previewing, the camera shows the whole route.
    val showRoute = state.guidance is GuidanceState.Previewing || (guidingNow && routeOverview)
    val currentOverview by rememberUpdatedState(if (showRoute) route else emptyList())
    val currentFollowRequests by rememberUpdatedState(followRequests)
    val map = remember {
        movableContentOf {
            NavigationMap(
                currentTheme,
                // The turn card covers the map's top right while guiding.
                showCompass = !currentGuiding,
                modifier = Modifier.fillMaxSize(),
                leftInset = currentFrame.left,
                rightInset = currentFrame.right,
                bottomInset = currentFrame.bottom,
                topInset = currentFrame.top,
                logoOnRight = currentFrame.logoOnRight,
                topDown = currentFrame.topDown,
                overview = currentOverview,
                followRequests = currentFollowRequests,
                onFollowingChanged = { following = it },
            )
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val landscape = maxWidth > maxHeight
            // A new destination starts at the top of the panel, not where the last list was scrolled.
            val panelScroll = remember(state.search.selected) { ScrollState(0) }
            // The character would cover the route's far end while previewing.
            val showCharacter = state.guidance !is GuidanceState.Previewing
            // Browsing after a drag; the route preview's overview is not a drag.
            val showFollow = !following && state.guidance !is GuidanceState.Previewing
            val onFollow: () -> Unit = { followRequests++ }
            // How much of the map the portrait sheet covers, so the route preview stays visible.
            var sheetCover by remember { mutableStateOf(SHEET_PEEK_HEIGHT) }
            val guiding = state.navigatorReady && state.guidance is GuidanceState.Guiding
            val wanted = when {
                // Equal side padding keeps the vehicle mid-screen; the bottom padding puts the
                // logo just above the trip pill's end button. Portrait follows from above, which
                // keeps the vehicle near the middle of the tall map (the tilted view puts it low).
                // The route overview keeps the whole route clear of the character, the cards and
                // the buttons. Uneven side padding only frames the route right left to right, so
                // the logo goes bottom left here, just past the character.
                guiding && routeOverview && landscape -> MapFrame(
                    left = if (characterModel != null) IMMERSIVE_CHARACTER_WIDTH else SIDE_INSET,
                    right = SIDE_INSET,
                    bottom = TRIP_PILL_CLEARANCE,
                )
                guiding && routeOverview -> MapFrame(
                    left = SIDE_INSET,
                    right = PORTRAIT_CONTROLS_CLEARANCE,
                    bottom = if (characterModel != null) PORTRAIT_CHARACTER_HEIGHT else TRIP_PILL_CLEARANCE,
                    top = PORTRAIT_CARDS_CLEARANCE,
                )
                guiding -> MapFrame(SIDE_INSET, SIDE_INSET, TRIP_PILL_CLEARANCE, topDown = !landscape, logoOnRight = true)
                landscape -> MapFrame(left = LANDSCAPE_PANEL_WIDTH + PANEL_MARGIN * 2)
                // As in portrait guidance: the character on the left, the logo bottom right.
                else -> MapFrame(bottom = sheetCover + SHEET_MAP_GAP, logoOnRight = true)
            }
            LaunchedEffect(wanted) { frame = wanted }

            when {
                !state.navigatorReady -> SetupScreen(state, locationDenied, actions, viewModel::initializeNavigation) {
                    context.openAppSettings()
                }
                guiding -> GuidanceLayout(
                    landscape = landscape,
                    state = state,
                    turn = turn,
                    turnIcon = turnIcon,
                    speech = speech,
                    characterModel = characterModel,
                    characterName = characterName,
                    onStop = viewModel::stopNavigation,
                    controls = { vertical, modifier ->
                        GuidanceControls(
                            voiceMode = voiceMode,
                            onVoiceMode = viewModel::cycleVoiceMode,
                            followingVehicle = following && !routeOverview,
                            onOverview = { routeOverview = true },
                            onFollow = {
                                routeOverview = false
                                followRequests++
                            },
                            vertical = vertical,
                            modifier = modifier,
                        )
                    },
                    map = map,
                )
                landscape -> Box(Modifier.fillMaxSize()) {
                    map()
                    if (showFollow) FollowVehicleButton(onFollow, Modifier.align(Alignment.TopEnd).padding(FOLLOW_BUTTON_PADDING))
                    characterModel?.takeIf { showCharacter }?.let { model ->
                        FloatingCharacter(model, speech, Modifier.align(Alignment.BottomEnd).padding(end = 16.dp))
                    }
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        shadowElevation = 8.dp,
                        modifier = Modifier
                            .padding(PANEL_MARGIN)
                            .width(LANDSCAPE_PANEL_WIDTH)
                            .fillMaxHeight(),
                    ) {
                        // The search bar stays put while the list below it scrolls.
                        // Tighter than the portrait sheet: a landscape phone is only ~400dp tall and
                        // the route preview's start button must fit without scrolling.
                        Column(
                            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SearchBar(query, actions)
                            Column(
                                Modifier
                                    .weight(1f)
                                    .verticalScroll(panelScroll),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                BrowseBody(state, saved, query, locationDenied, actions, viewModel::unlockManually, context)
                            }
                        }
                    }
                }
                else -> {
                    val sheetState = rememberBottomSheetScaffoldState()
                    // Results and the route preview need room; an idle sheet stays small.
                    val wantsRoom = query.isNotBlank() || state.search.selected != null
                    // Read here: inside sheetContent the implicit receiver hides maxHeight.
                    val sheetMaxHeight = maxHeight * SHEET_MAX_FRACTION
                    LaunchedEffect(wantsRoom) {
                        if (wantsRoom) sheetState.bottomSheetState.expand() else sheetState.bottomSheetState.partialExpand()
                    }
                    val density = LocalDensity.current
                    val layoutHeight = maxHeight
                    LaunchedEffect(sheetState) {
                        // Only settled positions: moving the camera on every drag frame is jumpy.
                        snapshotFlow { runCatching { sheetState.bottomSheetState.requireOffset() }.getOrNull() }
                            .filterNotNull()
                            .debounce(SHEET_SETTLE_MS)
                            .collect { offset -> sheetCover = layoutHeight - with(density) { offset.toDp() } }
                    }
                    BottomSheetScaffold(
                        scaffoldState = sheetState,
                        sheetPeekHeight = SHEET_PEEK_HEIGHT,
                        sheetContainerColor = MaterialTheme.colorScheme.surface,
                        sheetShadowElevation = 8.dp,
                        sheetContent = {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = sheetMaxHeight)
                                    .verticalScroll(panelScroll)
                                    .padding(horizontal = 16.dp)
                                    .padding(bottom = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                BrowseBody(state, saved, query, locationDenied, actions, viewModel::unlockManually, context)
                            }
                        },
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            map()
                            if (showFollow) {
                                FollowVehicleButton(onFollow, Modifier.align(Alignment.TopEnd).padding(FOLLOW_BUTTON_PADDING))
                            }
                            characterModel?.takeIf { showCharacter }?.let { model ->
                                FloatingCharacter(
                                    model,
                                    speech,
                                    Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(start = 8.dp, bottom = SHEET_PEEK_HEIGHT),
                                    bubbleFirst = false,
                                )
                            }
                            if (characterModel == null) {
                                speech?.let {
                                    SpeechBubble(
                                        characterName,
                                        it.text,
                                        Modifier
                                            .align(Alignment.BottomStart)
                                            .widthIn(max = PORTRAIT_BUBBLE_MAX_WIDTH)
                                            .padding(start = 12.dp, bottom = SHEET_PEEK_HEIGHT + 12.dp),
                                    )
                                }
                            }
                            // Clear of the compass in the top right corner.
                            SearchBar(query, actions, Modifier.padding(start = 12.dp, top = 12.dp, end = 72.dp))
                        }
                    }
                }
            }
        }
    }
}

/** The browse panel's content, shared by the portrait sheet and the landscape card. */
@Composable
private fun BrowseBody(
    state: MapUiState,
    saved: SavedPlacesData,
    query: String,
    locationDenied: Boolean,
    actions: BrowseActions,
    onUnlock: () -> Unit,
    context: android.content.Context,
) {
    if (state.locked) {
        LockedPanel(state, null, onUnlock, Modifier.fillMaxWidth())
        return
    }
    // A chosen destination needs the room for its route and the start button.
    if (state.search.selected == null) RideStatusRow(state, actions.onAlertsChange)
    if (locationDenied) LocationDeniedNotice { context.openAppSettings() }
    DestinationContent(state, saved, query, actions)
    if (state.search.selected == null) PanelFooter(actions)
}

@Composable
private fun LocationDeniedNotice(onOpenSettings: () -> Unit) {
    Text(
        text = stringResource(R.string.location_permission_needed),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedButton(onClick = onOpenSettings) { Text(stringResource(R.string.open_settings)) }
}

/** Before the navigator is ready: terms, permission, and the alerts that work without it. */
@Composable
private fun SetupScreen(
    state: MapUiState,
    locationDenied: Boolean,
    actions: BrowseActions,
    onEnableNavigation: (Activity?) -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = actions.onOpenSettings) {
                Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings_title))
            }
        }
        RideStatusRow(state, actions.onAlertsChange)
        if (locationDenied) LocationDeniedNotice(onOpenAppSettings)
        NavigationSetupCard(state.navigator, onEnableNavigation)
    }
}

/** Landscape and portrait guidance: full-screen map, cards and the character. */
@Composable
private fun GuidanceLayout(
    landscape: Boolean,
    state: MapUiState,
    turn: TurnUpdate?,
    turnIcon: Bitmap?,
    speech: Speech?,
    characterModel: CharacterModel?,
    characterName: String,
    onStop: () -> Unit,
    /** Voice and camera buttons; laid out in a column when the first argument is true. */
    controls: @Composable (vertical: Boolean, modifier: Modifier) -> Unit,
    map: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        map()
        // Landscape: the character stands on the left, the full height of the screen, with what
        // she says as a caption over her. Portrait: her upper body sits in the bottom left corner,
        // cut off by the screen edge, and her captions go under the speed card.
        characterModel?.let { model ->
            val characterModifier = if (landscape) {
                Modifier.width(IMMERSIVE_CHARACTER_WIDTH).fillMaxHeight()
            } else {
                Modifier
                    .align(Alignment.BottomStart)
                    .size(PORTRAIT_CHARACTER_WIDTH, PORTRAIT_CHARACTER_HEIGHT)
            }
            Box(characterModifier) {
                key(model.id) {
                    CharacterView(model.bytes, model.spec, speech, Modifier.fillMaxSize())
                }
                if (landscape) {
                    speech?.let {
                        SpeechBubble(model.name, it.text, Modifier.align(Alignment.BottomCenter).padding(12.dp))
                    }
                }
            }
        }
        if (characterModel == null && landscape) {
            speech?.let {
                SpeechBubble(
                    characterName,
                    it.text,
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 12.dp, bottom = 12.dp)
                        .widthIn(max = BUBBLE_MAX_WIDTH),
                )
            }
        }
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            turn?.let { TurnCard(it, turnIcon) }
            SpeedAlertCard(state.alerts)
            if (!landscape) {
                speech?.let {
                    SpeechBubble(characterModel?.name ?: characterName, it.text, Modifier.widthIn(max = PORTRAIT_BUBBLE_MAX_WIDTH))
                }
            }
        }
        if (landscape) {
            // Beside the trip pill: the right edge above it is kept for the Google logo.
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                controls(false, Modifier)
                TripPill(turn, onStop)
            }
        } else {
            // Halfway down the right edge, between the cards and the logo above the trip pill.
            controls(true, Modifier.align(Alignment.CenterEnd).padding(end = 12.dp))
            TripPill(
                turn,
                onStop,
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp),
                // Two lines, narrow enough to sit beside the character.
                compact = characterModel != null,
            )
        }
    }
}

/**
 * What remains while the auto-lock is on: large speed, the next alerts, ending navigation, and
 * a press-and-hold area for a passenger to unlock.
 */
@Composable
private fun LockedPanel(state: MapUiState, onStop: (() -> Unit)?, onUnlock: () -> Unit, modifier: Modifier) {
    // Compact enough for a landscape phone: both buttons share one row under the alerts.
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Speed(state.alerts.speedKmh, fontSize = 72.sp)
        NextCamera(state.alerts)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onStop != null && state.guidance is GuidanceState.Guiding) {
                Button(
                    onClick = onStop,
                    colors = endTripColors(),
                    modifier = Modifier
                        .weight(1f)
                        .height(72.dp),
                ) {
                    Text(stringResource(R.string.navigation_stop), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .weight(1f)
                    .height(72.dp)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown()
                            val released = withTimeoutOrNull(UNLOCK_HOLD_MS) { waitForUpOrCancellation() }
                            if (released == null) onUnlock()
                        }
                    },
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(8.dp)) {
                    Text(
                        stringResource(R.string.locked_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun FloatingCharacter(model: CharacterModel, speech: Speech?, modifier: Modifier, bubbleFirst: Boolean = true) {
    // The bubble goes on the side facing the middle of the screen.
    val bubble = @Composable {
        speech?.let {
            SpeechBubble(model.name, it.text, Modifier.widthIn(max = BUBBLE_MAX_WIDTH).padding(bottom = 8.dp))
        }
    }
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        if (bubbleFirst) bubble()
        key(model.id) {
            CharacterView(model.bytes, model.spec, speech, Modifier.size(CHARACTER_WIDTH, CHARACTER_HEIGHT))
        }
        if (!bubbleFirst) bubble()
    }
}

@Composable
private fun SpeechBubble(name: String, text: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 3)
        }
    }
}

/** Black on light, white on dark: strong but inside the black, white and blue palette. */
@Composable
private fun endTripColors() = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.onSurface,
    contentColor = MaterialTheme.colorScheme.surface,
)

private val LANDSCAPE_PANEL_WIDTH = 360.dp

/** Under the 48dp compass in the top right corner. */
private val FOLLOW_BUTTON_PADDING = PaddingValues(top = 72.dp, end = 12.dp)
private val PANEL_MARGIN = 12.dp
private val SHEET_PEEK_HEIGHT = 128.dp
private val SHEET_MAP_GAP = 8.dp
private const val SHEET_SETTLE_MS = 250L
private const val SHEET_MAX_FRACTION = 0.7f

/** The character's column in landscape guidance, over the map's left side. */
private val IMMERSIVE_CHARACTER_WIDTH = 330.dp
private val SIDE_INSET = 16.dp
private val PORTRAIT_CHARACTER_WIDTH = 170.dp
private val PORTRAIT_CHARACTER_HEIGHT = 240.dp
private val PORTRAIT_BUBBLE_MAX_WIDTH = 300.dp

/** The trip pill's height plus margins, so the logo sits right above it. */
private val TRIP_PILL_CLEARANCE = 92.dp

/** Portrait guidance: the turn and speed cards at the top, the buttons on the right. */
private val PORTRAIT_CARDS_CLEARANCE = 200.dp
private val PORTRAIT_CONTROLS_CLEARANCE = 88.dp
private val BUBBLE_MAX_WIDTH = 170.dp

private val CHARACTER_WIDTH = 240.dp

/** Below the turn card even with lane guidance (about 155 dp of a 385 dp landscape map). */
private val CHARACTER_HEIGHT = 210.dp
private const val UNLOCK_HOLD_MS = 2_000L
