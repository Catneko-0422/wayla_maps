package cc.nekocat.waylamaps.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.nekocat.waylamaps.BuildConfig
import cc.nekocat.waylamaps.R
import cc.nekocat.waylamaps.alerts.UserPoint
import cc.nekocat.waylamaps.character.Character
import cc.nekocat.waylamaps.character.CharacterPackImporter
import cc.nekocat.waylamaps.character.CharacterSource
import cc.nekocat.waylamaps.navigation.TravelMode
import cc.nekocat.waylamaps.service.ThemeMode
import cc.nekocat.waylamaps.ui.labelRes
import cc.nekocat.waylamaps.voice.VoiceBoost
import cc.nekocat.waylamaps.voice.VoiceMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Ride settings in groups, one card each. Opened only while not navigating; nothing here needs
 * typing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenLegal: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val travelMode by viewModel.travelMode.collectAsStateWithLifecycle()
    val matchElevation by viewModel.matchElevation.collectAsStateWithLifecycle()
    val autoLock by viewModel.autoLock.collectAsStateWithLifecycle()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val voiceMode by viewModel.voiceMode.collectAsStateWithLifecycle()
    val voiceBoost by viewModel.voiceBoost.collectAsStateWithLifecycle()
    val userPoints by viewModel.userPoints.collectAsStateWithLifecycle()
    val characters by viewModel.characters.collectAsStateWithLifecycle()
    val selectedCharacter by viewModel.selectedCharacter.collectAsStateWithLifecycle()
    val importMessage by viewModel.importMessage.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val data by viewModel.data.collectAsStateWithLifecycle()
    val update by viewModel.update.collectAsStateWithLifecycle()
    val importPack = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importCharacter)
    }

    importMessage?.let { result ->
        AlertDialog(
            onDismissRequest = viewModel::dismissImportMessage,
            confirmButton = { TextButton(onClick = viewModel::dismissImportMessage) { Text(stringResource(R.string.action_ok)) } },
            title = { Text(stringResource(R.string.character_import_result)) },
            text = { Text(describe(result)) },
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                SettingsGroup(stringResource(R.string.settings_group_route)) {
                    Choice(TravelMode.entries, travelMode, { stringResource(it.labelRes()) }, viewModel::setTravelMode)
                    SwitchRow(
                        title = stringResource(R.string.settings_match_elevation),
                        summary = stringResource(R.string.settings_match_elevation_summary),
                        checked = matchElevation,
                        onCheckedChange = viewModel::setMatchElevation,
                    )
                }
            }
            item {
                SettingsGroup(stringResource(R.string.settings_character)) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        modifier = Modifier.padding(vertical = 8.dp),
                    ) {
                        items(characters, key = { it.id }) { character ->
                            CharacterCard(
                                character = character,
                                selected = character.id == selectedCharacter.id,
                                onSelect = { viewModel.selectCharacter(character) },
                                onDelete = { viewModel.deleteCharacter(character) },
                            )
                        }
                    }
                    Label(stringResource(R.string.settings_voice_mode))
                    Choice(VoiceMode.entries, voiceMode, { stringResource(it.shortLabelRes()) }, viewModel::setVoiceMode)
                    if (voiceMode == VoiceMode.ESSENTIAL) Summary(stringResource(R.string.voice_mode_essential_summary))
                    Label(stringResource(R.string.settings_voice_boost))
                    Choice(VoiceBoost.entries, voiceBoost, { stringResource(it.labelRes()) }, viewModel::setVoiceBoost)
                    Summary(stringResource(R.string.settings_voice_boost_summary))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(16.dp),
                    ) {
                        OutlinedButton(onClick = viewModel::playVoiceDemo) {
                            Icon(painterResource(R.drawable.ic_volume_up), contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.settings_voice_demo), modifier = Modifier.padding(start = 6.dp))
                        }
                        OutlinedButton(
                            onClick = { importPack.launch(arrayOf("application/zip", "application/octet-stream")) },
                            enabled = !importing,
                        ) {
                            if (importing) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(stringResource(R.string.character_importing), modifier = Modifier.padding(start = 6.dp))
                            } else {
                                Text(stringResource(R.string.character_import))
                            }
                        }
                    }
                    HowToMakeVoicePack()
                }
            }
            item {
                SettingsGroup(stringResource(R.string.settings_group_alerts)) {
                    Label(stringResource(R.string.settings_user_points))
                    if (userPoints.isEmpty()) Summary(stringResource(R.string.settings_user_points_empty))
                    userPoints.forEach { point ->
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.user_point_label, point.id)) },
                            supportingContent = { Text(describe(point)) },
                            trailingContent = {
                                TextButton(onClick = { viewModel.deletePoint(point) }) { Text(stringResource(R.string.action_delete)) }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
            }
            item {
                SettingsGroup(stringResource(R.string.settings_group_display)) {
                    Label(stringResource(R.string.settings_theme))
                    Choice(ThemeMode.entries, theme, { stringResource(it.labelRes()) }, viewModel::setTheme)
                    SwitchRow(
                        title = stringResource(R.string.settings_auto_lock),
                        summary = stringResource(R.string.settings_auto_lock_summary),
                        checked = autoLock,
                        onCheckedChange = viewModel::setAutoLock,
                    )
                }
            }
            item {
                SettingsGroup(stringResource(R.string.settings_group_data)) {
                    data?.let { summary ->
                        Text(
                            stringResource(R.string.data_cameras, summary.cameras) +
                                (summary.cameraVersion?.let { "・" + stringResource(R.string.data_version, it) } ?: ""),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        Text(
                            stringResource(R.string.data_enforcement, summary.enforcementPoints),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    Summary(stringResource(R.string.data_source))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(16.dp),
                    ) {
                        OutlinedButton(onClick = viewModel::updateDataNow, enabled = update != SettingsViewModel.UpdateState.RUNNING) {
                            Text(stringResource(R.string.data_update_now))
                        }
                        val status = when (update) {
                            SettingsViewModel.UpdateState.IDLE -> null
                            SettingsViewModel.UpdateState.RUNNING -> R.string.data_updating
                            SettingsViewModel.UpdateState.DONE -> R.string.data_update_done
                            SettingsViewModel.UpdateState.FAILED -> R.string.data_update_failed
                        }
                        status?.let {
                            Text(
                                stringResource(it),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (update == SettingsViewModel.UpdateState.FAILED) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
            }
            item {
                SettingsGroup(stringResource(R.string.settings_group_about)) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.legal_title)) },
                        supportingContent = { Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME)) },
                        modifier = Modifier.clickable(onClick = onOpenLegal),
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }
}

/** One settings group: a small title over a rounded card. */
@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
        )
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) { content() }
        }
    }
}

@Composable
private fun <T> Choice(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) {
                Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * A character to pick: a round initial (packs have no picture), the name, how much is recorded
 * and where it came from. Tapping it selects it and plays a sample.
 */
@Composable
private fun CharacterCard(character: Character, selected: Boolean, onSelect: () -> Unit, onDelete: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onSelect,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) colors.primaryContainer else colors.surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant),
        modifier = Modifier.width(CHARACTER_CARD_WIDTH),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = colors.primary, modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            character.manifest.name.take(1).uppercase(Locale.ROOT),
                            color = colors.onPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                if (selected) {
                    Text(
                        stringResource(R.string.character_in_use),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.primary,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
            Text(
                character.manifest.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                stringResource(R.string.character_phrases, character.voiceFragments[VOICE_LANGUAGE].orEmpty().size),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            val tags = listOfNotNull(
                stringResource(
                    if (character.source == CharacterSource.IMPORTED) R.string.character_source_imported else R.string.character_source_bundled,
                ),
                character.manifest.model?.let { stringResource(R.string.character_has_model) },
            )
            Text(tags.joinToString("・"), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (character.source == CharacterSource.IMPORTED) {
                TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_delete))
                }
            }
        }
    }
}

@Composable
private fun HowToMakeVoicePack() {
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = !open }, modifier = Modifier.padding(horizontal = 4.dp)) {
        Text(stringResource(R.string.character_import_how))
    }
    if (open) Summary(stringResource(R.string.character_import_how_body))
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
}

@Composable
private fun Summary(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun SwitchRow(title: String, summary: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.padding(start = 16.dp))
    }
}

private fun VoiceMode.shortLabelRes() = when (this) {
    VoiceMode.ALL -> R.string.voice_mode_short_all
    VoiceMode.ESSENTIAL -> R.string.voice_mode_short_essential
    VoiceMode.MUTED -> R.string.voice_mode_short_muted
}

private fun VoiceBoost.labelRes() = when (this) {
    VoiceBoost.NORMAL -> R.string.voice_boost_normal
    VoiceBoost.LOUD -> R.string.voice_boost_loud
    VoiceBoost.LOUDEST -> R.string.voice_boost_loudest
}

private val CHARACTER_CARD_WIDTH = 168.dp

/** Fragment counts are shown for the language the voice packs are made for. */
private const val VOICE_LANGUAGE = "zh-TW"

@Composable
private fun describe(result: CharacterPackImporter.Result): String = when (result) {
    is CharacterPackImporter.Result.Imported ->
        stringResource(R.string.character_imported, result.name, result.recorded, result.missing)
    is CharacterPackImporter.Result.Failed -> stringResource(
        when (result.reason) {
            CharacterPackImporter.Reason.NOT_A_PACK, CharacterPackImporter.Reason.INVALID_MANIFEST ->
                R.string.character_import_not_a_pack
            CharacterPackImporter.Reason.RESERVED_ID -> R.string.character_import_reserved
            CharacterPackImporter.Reason.UNSAFE_PATH, CharacterPackImporter.Reason.TOO_LARGE ->
                R.string.character_import_rejected
            CharacterPackImporter.Reason.IO_ERROR -> R.string.character_import_failed
        },
    )
}

private val POINT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun describe(point: UserPoint): String {
    val time = Instant.ofEpochMilli(point.createdAt).atZone(ZoneId.systemDefault()).format(POINT_TIME)
    return String.format(Locale.ROOT, "%s · %.5f, %.5f", time, point.lat, point.lng)
}

private fun ThemeMode.labelRes() = when (this) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}

