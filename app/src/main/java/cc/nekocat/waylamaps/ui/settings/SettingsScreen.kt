package cc.nekocat.waylamaps.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.nekocat.waylamaps.R
import cc.nekocat.waylamaps.alerts.UserPoint
import cc.nekocat.waylamaps.character.CharacterPackImporter
import cc.nekocat.waylamaps.character.CharacterSource
import cc.nekocat.waylamaps.navigation.TravelMode
import cc.nekocat.waylamaps.service.ThemeMode
import cc.nekocat.waylamaps.ui.labelRes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Ride settings. Opened only while not navigating; nothing here needs typing. */
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenLegal: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val travelMode by viewModel.travelMode.collectAsStateWithLifecycle()
    val matchElevation by viewModel.matchElevation.collectAsStateWithLifecycle()
    val autoLock by viewModel.autoLock.collectAsStateWithLifecycle()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val userPoints by viewModel.userPoints.collectAsStateWithLifecycle()
    val characters by viewModel.characters.collectAsStateWithLifecycle()
    val selectedCharacter by viewModel.selectedCharacter.collectAsStateWithLifecycle()
    val importMessage by viewModel.importMessage.collectAsStateWithLifecycle()
    val importPack = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importCharacter)
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
        ) {
            item {
                TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
                SectionTitle(stringResource(R.string.settings_travel_mode))
            }
            TravelMode.entries.forEach { mode ->
                item {
                    RadioRow(stringResource(mode.labelRes()), mode == travelMode) { viewModel.setTravelMode(mode) }
                }
            }
            item { SectionTitle(stringResource(R.string.settings_character)) }
            items(characters, key = { "character-${it.id}" }) { character ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        RadioRow(character.manifest.name, character.id == selectedCharacter.id) {
                            viewModel.selectCharacter(character)
                        }
                        Text(
                            stringResource(
                                R.string.character_voice_count,
                                character.voiceFragments[VOICE_LANGUAGE].orEmpty().size,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 40.dp),
                        )
                    }
                    if (character.source == CharacterSource.IMPORTED) {
                        TextButton(onClick = { viewModel.deleteCharacter(character) }) {
                            Text(stringResource(R.string.action_delete))
                        }
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = { importPack.launch(arrayOf("application/zip", "application/octet-stream")) },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.character_import))
                }
                importMessage?.let { Text(describe(it), style = MaterialTheme.typography.bodyMedium) }
            }
            item { SectionTitle(stringResource(R.string.settings_theme)) }
            ThemeMode.entries.forEach { mode ->
                item {
                    RadioRow(stringResource(mode.labelRes()), mode == theme) { viewModel.setTheme(mode) }
                }
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.settings_match_elevation),
                    summary = stringResource(R.string.settings_match_elevation_summary),
                    checked = matchElevation,
                    onCheckedChange = viewModel::setMatchElevation,
                )
                SwitchRow(
                    title = stringResource(R.string.settings_auto_lock),
                    summary = stringResource(R.string.settings_auto_lock_summary),
                    checked = autoLock,
                    onCheckedChange = viewModel::setAutoLock,
                )
            }
            item {
                OutlinedButton(onClick = viewModel::playVoiceDemo, modifier = Modifier.padding(top = 16.dp)) {
                    Text(stringResource(R.string.settings_voice_demo))
                }
                SectionTitle(stringResource(R.string.settings_user_points))
                if (userPoints.isEmpty()) {
                    Text(
                        stringResource(R.string.settings_user_points_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(userPoints, key = { it.id }) { point ->
                ListItem(
                    headlineContent = { Text(stringResource(R.string.user_point_label, point.id)) },
                    supportingContent = { Text(describe(point)) },
                    trailingContent = {
                        TextButton(onClick = { viewModel.deletePoint(point) }) { Text(stringResource(R.string.action_delete)) }
                    },
                )
            }
        }
    }
}

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

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 4.dp))
}

@Composable
private fun SwitchRow(title: String, summary: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.padding(start = 16.dp))
    }
}
