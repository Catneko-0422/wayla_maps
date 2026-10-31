package cc.nekocat.waylamaps.ui.legal

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cc.nekocat.waylamaps.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val NOTICE_ASSET = "legal/navigation_notice.txt"
private const val LICENSES_ASSET = "legal/navigation_licenses.txt"
private const val LINES_PER_ITEM = 200

/** Attribution and open source licenses, as the Navigation SDK terms require. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegalScreen(credits: List<String>, onBack: () -> Unit) {
    val context = LocalContext.current
    var notice by remember { mutableStateOf("") }
    // The license file is ~50k lines; chunk it so the list stays lazy.
    var licenseChunks by remember { mutableStateOf(emptyList<String>()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            notice = context.readAsset(NOTICE_ASSET)
            licenseChunks = context.readAsset(LICENSES_ASSET).lines().chunked(LINES_PER_ITEM) { it.joinToString("\n") }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.legal_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        // A readable column in landscape instead of text stretched across the whole screen.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .wrapContentWidth()
                .widthIn(max = LEGAL_MAX_WIDTH),
            contentPadding = PaddingValues(16.dp),
        ) {
            item {
                Section(
                    stringResource(R.string.legal_data_section),
                    stringResource(R.string.data_attribution) + "\n\n" + stringResource(R.string.enforcement_attribution),
                )
            }
            if (credits.isNotEmpty()) {
                item { Section(stringResource(R.string.legal_characters_section), credits.joinToString("\n")) }
            }
            item { Section(stringResource(R.string.legal_navigation_section), notice) }
            items(licenseChunks) { chunk ->
                Text(chunk, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private val LEGAL_MAX_WIDTH = 720.dp

@Composable
private fun Section(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
    Text(body, style = MaterialTheme.typography.bodyMedium)
}

private fun Context.readAsset(path: String) = assets.open(path).bufferedReader().use { it.readText() }
